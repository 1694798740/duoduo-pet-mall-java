package com.duoduo.ai.llm;

import com.duoduo.ai.agent.tool.ToolSpec;
import com.duoduo.ai.support.AiMetrics;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主链路模型回退（2026-10-29）。
 *
 * <p>这组测试用一个本地 HTTP 服务模拟 DashScope，验证四件事：
 * <ol>
 *   <li>5xx 会换下一个模型，并且第二次请求带的是备选模型名；</li>
 *   <li>401 不会换模型（换也没用，只会浪费配额）；</li>
 *   <li>流式在「尚未输出任何内容」时能回退；</li>
 *   <li>流式在「已经输出内容后」中断时不再回退，避免用户看到两段开头。</li>
 * </ol>
 */
class DashScopeToolClientTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> seenModels = new CopyOnWriteArrayList<>();
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AiMetrics metrics = new AiMetrics(providerOf(registry));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = mapper.readTree(body).path("model").asText("");
            boolean stream = mapper.readTree(body).path("stream").asBoolean(false);
            seenModels.add(model);
            try {
                if (model.startsWith("slow")) {
                    // 模拟一个慢模型：客户端必须按调用方给的整轮预算快速放弃
                    Thread.sleep(3000);
                    respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"SLOW-OK\"}}]}");
                    return;
                }
                int status = decideStatus(model);
                if (status != 200) {
                    // 404 模拟 DashScope 的「模型不存在」：必须带回 model_not_found
                    respond(exchange, status, status == 404
                            ? "{\"error\":{\"code\":\"model_not_found\"}}"
                            : "{\"error\":\"boom\"}");
                    return;
                }
                if (!stream) {
                    respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
                    return;
                }
                if (model.startsWith("broken")) {
                    // 先吐一段合法内容，再吐坏 JSON：模拟「已输出后中断」
                    streamText(exchange, "已经开始", "{|not-json");
                    return;
                }
                streamText(exchange, "你好", null);
            } catch (Exception e) {
                // 测试服务端异常不应影响断言，直接关闭连接
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("整轮预算：调用方传入的预算用尽时快速失败")
    void respectsCallerBudget() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "slow-model", 30, metrics);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of(),
                Duration.ofMillis(900)))
                .isInstanceOf(BizException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(2500L);
    }

    @Test
    @DisplayName("非流式：5xx 回退到下一个模型")
    void fallsBackOnServerError() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "bad-model,good-model", 5, metrics);

        LlmTurn turn = client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of());

        assertThat(turn.content()).isEqualTo("OK");
        assertThat(seenModels).containsExactly("bad-model", "good-model");
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("非流式：404 model_not_found 回退（DashScope 实测行为）")
    void fallsBackOnMissingModel() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "missing-model,good-model", 5, metrics);

        LlmTurn turn = client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of());

        assertThat(turn.content()).isEqualTo("OK");
        assertThat(seenModels).containsExactly("missing-model", "good-model");
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("非流式：400 且不是模型问题时不回退")
    void doesNotFallbackOnGenericBadRequest() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "badrequest-model,good-model", 5, metrics);

        assertThatThrownBy(() -> client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of()))
                .isInstanceOf(BizException.class);
        assertThat(seenModels).hasSize(1);
    }

    @Test
    @DisplayName("非流式：401 不回退（只请求一次）")
    void doesNotFallbackOnAuthError() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "unauthorized-model,good-model", 5, metrics);

        assertThatThrownBy(() -> client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of()))
                .isInstanceOf(BizException.class);
        assertThat(seenModels).hasSize(1);
    }

    @Test
    @DisplayName("CR-09：全部模型冷却后仍放行一次探针，而不是直接 503")
    void probesFirstModelWhenAllModelsCooling() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "bad-model,bad2-model", 5, metrics);

        // 第一次：两个模型都失败 → 双双进入冷却
        assertThatThrownBy(() -> client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of()))
                .isInstanceOf(BizException.class);
        assertThat(seenModels).containsExactly("bad-model", "bad2-model");

        // 第二次紧接在上一次之后：此时两个模型都在冷却期。
        // 必须仍对首个模型发一次真实请求（探针）—— 否则冷却窗口内所有用户直接 503。
        assertThatThrownBy(() -> client.chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.<ToolSpec>of()))
                .isInstanceOf(BizException.class);
        assertThat(seenModels).containsExactly("bad-model", "bad2-model", "bad-model");
    }

    @Test
    @DisplayName("流式：尚未输出内容时回退")
    void streamFallsBackBeforeFirstDelta() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "bad-model,good-model", 5, metrics);
        StringBuilder answer = new StringBuilder();

        client.streamChat(List.of(Map.of("role", "user", "content", "hi")), answer::append);

        assertThat(answer.toString()).isEqualTo("你好");
        assertThat(seenModels).containsExactly("bad-model", "good-model");
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("流式：已输出内容后中断不再回退（避免重复开头）")
    void streamDoesNotRetryAfterEmission() {
        DashScopeToolClient client = new DashScopeToolClient(
                mapper, "test-key", baseUrl, "broken-model,good-model", 5, metrics);
        StringBuilder answer = new StringBuilder();

        assertThatThrownBy(() ->
                client.streamChat(List.of(Map.of("role", "user", "content", "hi")), answer::append))
                .isInstanceOf(BizException.class);

        // 已经收到第一段，但绝不能再请求第二个模型
        assertThat(answer.toString()).isEqualTo("已经开始");
        assertThat(seenModels).containsExactly("broken-model");
        assertThat(fallbackCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private double fallbackCount() {
        return registry.get("ai_llm_fallback_count").functionCounter().count();
    }

    // ---------------- 模拟服务端 ----------------

    private int decideStatus(String model) {
        // 顺序很重要：badrequest-model 也以 bad 开头，必须先判 400
        if (model.startsWith("badrequest")) {
            return 400;
        }
        if (model.startsWith("bad")) {
            return 500;
        }
        if (model.startsWith("unauthorized")) {
            return 401;
        }
        if (model.startsWith("missing")) {
            return 404;
        }
        return 200;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void streamText(com.sun.net.httpserver.HttpExchange exchange,
                            String firstDelta, String badLine) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + firstDelta + "\"}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (badLine != null) {
                out.write(("data: " + badLine + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
