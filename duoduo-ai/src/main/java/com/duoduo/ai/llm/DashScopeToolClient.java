package com.duoduo.ai.llm;

import com.duoduo.ai.agent.tool.ToolCall;
import com.duoduo.ai.agent.tool.ToolSpec;
import com.duoduo.ai.support.AiMetrics;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * DashScope OpenAI 兼容客户端（2026-10-28 · Function Calling + SSE）。
 *
 * <p>与旧 {@code DashScopeClient} 的区别：这里只服务 Agent 链路，
 * 支持 tools / tool_calls / stream；旧客户端保留给摘要等简单文本场景。
 *
 * <h2>模型回退（2026-10-29）</h2>
 * {@code duoduo.ai.model} 是逗号分隔的优先级列表。此前这里只取第一个模型，
 * 主模型一挂整条对话就 DEGRADED；现在**主链路也按顺序回退**：
 * <ul>
 *   <li>只对「可重试」的失败回退：429、5xx、超时、网络异常、响应解析失败；</li>
 *   <li>401/403/400 不回退 —— 换模型不会让无效 Key 或错误请求变好，只会浪费配额；</li>
 *   <li>单个模型失败后冷却 60 秒：4 步工具循环里不必每一步都先把同一个坏模型撞一遍；</li>
 *   <li><b>流式一旦已经输出内容就不再回退</b>：否则用户会看到两段开头拼在一起；</li>
 *   <li>整次调用有总预算（默认 55 秒），避免「三个模型各超时 30 秒」把网关拖成 504。</li>
 * </ul>
 *
 * <p>安全：Key 只从环境变量读取；日志只记状态码与耗时，不落用户消息与模型回复。
 */
@Component
public class DashScopeToolClient implements LlmToolClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeToolClient.class);

    /** 模型失败后的冷却窗口：够短，能自愈；够长，能避免同一轮里反复撞墙。 */
    private static final long COOLDOWN_MS = 60_000L;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String baseUrl;
    private final List<String> models;
    private final Duration attemptTimeout;
    private final Duration totalBudget;
    private final AiMetrics metrics;
    /** 每个模型的下一次可用时间（epoch millis）；0 表示可用 */
    private final AtomicLongArray cooldownUntil;

    public DashScopeToolClient(
            ObjectMapper mapper,
            @Value("${duoduo.ai.api-key:}") String apiKey,
            @Value("${duoduo.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${duoduo.ai.model:qwen-plus}") String model,
            @Value("${duoduo.ai.timeout-seconds:60}") int timeoutSeconds,
            AiMetrics metrics) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.models = parseModels(model);
        this.metrics = metrics;
        // 单次模型调用最多 30 秒：超过就降级/报错，不能让网关 60 秒超时把请求变成 504。
        this.attemptTimeout = Duration.ofSeconds(Math.min(Math.max(timeoutSeconds, 1), 30));
        // 留给回退的总预算：比单次超时大，但必须小于网关超时，否则回退救不了请求。
        this.totalBudget = Duration.ofSeconds(Math.min(Math.max(timeoutSeconds, 1), 55));
        this.cooldownUntil = new AtomicLongArray(this.models.size());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public LlmTurn chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools) {
        return chatWithTools(messages, tools, totalBudget);
    }

    @Override
    public LlmTurn chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools, Duration budget) {
        requireConfigured();
        long start = System.nanoTime();
        long budgetMs = Math.max(1L, budget.toMillis());
        BizException lastError = null;
        boolean fallbackAttempt = false;

        for (int i = 0; i < models.size(); i++) {
            if (remainingMs(start, budgetMs) <= 0) {
                break;
            }
            if (cooling(i) && i != probeIndex()) {
                log.info("模型 {} 处于冷却期，跳过", models.get(i));
                continue;
            }
            String model = models.get(i);
            if (fallbackAttempt) {
                // 只有真的发出了「第二次请求」才记一次回退，跳过的冷却模型不算
                metrics.llmFallback();
            }
            fallbackAttempt = false;
            try {
                return doChatWithTools(model, messages, tools, remainingTimeout(start, budgetMs));
            } catch (RetryableModelException e) {
                markFailure(i);
                log.warn("模型 {} tools 调用失败，尝试下一个: {}", model, e.getMessage());
                lastError = degraded();
                fallbackAttempt = true;
            } catch (BizException e) {
                // 鉴权/参数错误：换模型不会变好，直接失败
                throw e;
            }
        }
        throw lastError != null ? lastError : degraded();
    }

    @Override
    public void streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta) {
        streamChat(messages, onDelta, totalBudget);
    }

    @Override
    public void streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta, Duration budget) {
        requireConfigured();
        long start = System.nanoTime();
        long budgetMs = Math.max(1L, budget.toMillis());
        BizException lastError = null;
        boolean fallbackAttempt = false;

        for (int i = 0; i < models.size(); i++) {
            if (remainingMs(start, budgetMs) <= 0) {
                break;
            }
            if (cooling(i) && i != probeIndex()) {
                continue;
            }
            String model = models.get(i);
            if (fallbackAttempt) {
                metrics.llmFallback();
            }
            fallbackAttempt = false;
            // 记录本模型是否已经吐出过内容：吐过就不能换模型重来
            boolean[] emitted = {false};
            try {
                doStreamChat(model, messages, text -> {
                    emitted[0] = true;
                    onDelta.accept(text);
                }, remainingTimeout(start, budgetMs));
                return;
            } catch (RetryableModelException e) {
                markFailure(i);
                if (emitted[0]) {
                    log.warn("模型 {} 流式中断且已输出部分内容，不再回退: {}", model, e.getMessage());
                    throw degraded();
                }
                log.warn("模型 {} 流式调用失败，尝试下一个: {}", model, e.getMessage());
                lastError = degraded();
                fallbackAttempt = true;
            } catch (BizException e) {
                throw e;
            }
        }
        throw lastError != null ? lastError : degraded();
    }

    // ---------------- 单模型调用 ----------------

    private LlmTurn doChatWithTools(String model, List<Map<String, Object>> messages,
                                    List<ToolSpec> tools, Duration timeout) {
        try {
            ObjectNode body = requestBody(model, messages, tools, false);
            HttpResponse<String> resp = http.send(request(body, timeout),
                    HttpResponse.BodyHandlers.ofString());
            ensureSuccess(resp.statusCode(), resp.body());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            List<Map<String, Object>> raw = new ArrayList<>();
            for (JsonNode tc : message.path("tool_calls")) {
                String id = tc.path("id").asText("");
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                String args = fn.path("arguments").isTextual()
                        ? fn.path("arguments").asText()
                        : fn.path("arguments").toString();
                if (id.isBlank() || name.isBlank()) {
                    continue;
                }
                calls.add(new ToolCall(id, name, args));
                Map<String, Object> rawCall = new LinkedHashMap<>();
                rawCall.put("id", id);
                rawCall.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", name);
                function.put("arguments", args);
                rawCall.put("function", function);
                raw.add(rawCall);
            }
            return new LlmTurn(content, calls, raw);
        } catch (RetryableModelException | BizException e) {
            throw e;
        } catch (Exception e) {
            // 超时、连接失败、响应不是合法 JSON —— 都可能是这个模型/节点的问题
            throw new RetryableModelException("tools 调用异常: " + e.getMessage());
        }
    }

    private void doStreamChat(String model, List<Map<String, Object>> messages,
                              Consumer<String> onDelta, Duration timeout) {
        try {
            ObjectNode body = requestBody(model, messages, List.of(), true);
            HttpResponse<java.io.InputStream> resp = http.send(request(body, timeout),
                    HttpResponse.BodyHandlers.ofInputStream());
            ensureSuccess(resp.statusCode(), null);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta");
                    String text = delta.path("content").asText("");
                    if (!text.isEmpty()) {
                        onDelta.accept(text);
                    }
                }
            }
        } catch (RetryableModelException | BizException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableModelException("流式调用异常: " + e.getMessage());
        }
    }

    // ---------------- 失败分类与预算 ----------------

    /**
     * 非 2xx 的统一判定。
     *
     * <p>429 与 5xx 属于「这个模型/节点此刻不行」，可以换下一个；
     * 400/401/403 属于「请求或凭据本身有问题」，换模型只会把同一个错误再犯一遍。
     *
     * <p>但有两个实测例外：
     * <ol>
     *   <li>DashScope 对「模型不存在/无权限」返回 **404 + model_not_found**
     *       （2026-10-29 用不存在的模型实测确认）。这属于「这个模型不可用」，
     *       必须回退，否则配置里写错一个模型名会让整条对话直接 503。</li>
     *   <li>**403 + AllocationQuota.FreeTierOnly**（2026-11-08 云端实测）：百炼的免费额度
     *       **按模型独立计费**——qwen3.8-27b 耗尽时 max/kimi 仍可用。若不回退，
     *       主模型配额一耗尽整条 AI 导购就 503，客户看到的是「AI 不可用」而不是慢一点的备选模型。
     *       凭据级 403（账号被封禁）不在此列——那种情况下每个模型都会同样失败，
     *       走到这里也只是多一次请求，可接受。</li>
     * </ol>
     */
    private void ensureSuccess(int status, String body) {
        if (status / 100 == 2) {
            return;
        }
        log.warn("LLM 调用失败 status={} body={}", status, head(body));
        if (status == 429 || status >= 500) {
            throw new RetryableModelException("HTTP " + status);
        }
        if ((status == 400 || status == 404) && isModelMissing(body)) {
            throw new RetryableModelException("HTTP " + status + " model_not_found");
        }
        if (status == 403 && isPerModelQuotaExhausted(body)) {
            throw new RetryableModelException("HTTP 403 quota_exhausted（该模型配额耗尽，换下一个）");
        }
        throw degraded();
    }

    /**
     * 是否属于「这个模型的配额耗尽」——百炼免费额度按模型独立发放，换模型可能恢复。
     * 只认明确的 AllocationQuota 标识，避免把账号级封禁/合规下架也当成可回退。
     */
    private static boolean isPerModelQuotaExhausted(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.toLowerCase(java.util.Locale.ROOT).contains("allocationquota");
    }

    /** 是否属于「模型不存在 / 无权限」这一类可回退错误。 */
    private static boolean isModelMissing(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("model_not_found")) {
            return true;
        }
        return lower.contains("model")
                && (lower.contains("does not exist")
                || lower.contains("not found")
                || lower.contains("do not have access"));
    }

    private static BizException degraded() {
        return new BizException(ErrorCode.DEGRADED, "AI 服务暂时不可用，请稍后再试");
    }

    private long remainingMs(long startNanos, long budgetMs) {
        return budgetMs - (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private Duration remainingTimeout(long startNanos, long budgetMs) {
        long remaining = remainingMs(startNanos, budgetMs);
        if (remaining <= 0) {
            return Duration.ofMillis(1);
        }
        return Duration.ofMillis(Math.min(remaining, attemptTimeout.toMillis()));
    }

    private boolean cooling(int index) {
        return System.currentTimeMillis() < cooldownUntil.get(index);
    }

    /**
     * 全部模型都在冷却时，允许把列表首个模型当作「探针」放行一次（CR-09）。
     *
     * <p>原行为是三个模型都冷却后，所有请求直接抛 DEGRADED ——
     * 一次慢请求（把整轮预算耗光、每个模型各记一次超时）会让整个 AI 能力在
     * 60 秒冷却窗口内对**所有用户**返回 503。探针把「整站不可用」降级成
     * 「一次真实的上游尝试」：模型恢复了立刻自愈，真坏了也只多一次失败调用。
     *
     * @return 可以无视冷却的模型下标；-1 表示不处于「全冷却」状态
     */
    private int probeIndex() {
        for (int i = 0; i < models.size(); i++) {
            if (!cooling(i)) {
                return -1;
            }
        }
        return models.isEmpty() ? -1 : 0;
    }

    private void markFailure(int index) {
        cooldownUntil.set(index, System.currentTimeMillis() + COOLDOWN_MS);
    }

    // ---------------- 请求构造 ----------------

    private ObjectNode requestBody(String model, List<Map<String, Object>> messages,
                                   List<ToolSpec> tools, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        ArrayNode msgs = body.putArray("messages");
        for (Map<String, Object> m : messages) {
            msgs.add(mapper.valueToTree(m));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (ToolSpec tool : tools) {
                ObjectNode t = arr.addObject();
                t.put("type", "function");
                ObjectNode f = t.putObject("function");
                f.put("name", tool.name());
                f.put("description", tool.description());
                f.set("parameters", tool.parameters());
            }
            body.put("tool_choice", "auto");
        }
        body.put("max_tokens", stream ? 800 : 600);
        return body;
    }

    private HttpRequest request(ObjectNode body, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BizException(ErrorCode.DEGRADED,
                    "AI 服务未配置（DASHSCOPE_API_KEY 为空），请联系管理员");
        }
    }

    /**
     * 解析模型列表：逗号分隔、去空白、去空项；全部非法时退回 qwen-plus ——
     * 配置写错不该让服务起不来（与「漏标方向安全」同一取舍）。
     */
    private static List<String> parseModels(String raw) {
        List<String> list = new ArrayList<>();
        if (raw != null) {
            for (String m : raw.split(",")) {
                String trimmed = m.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
        return list.isEmpty() ? List.of("qwen-plus") : List.copyOf(list);
    }

    private static String head(String body) {
        return body == null ? "" : body.substring(0, Math.min(300, body.length()));
    }

    /** 可重试失败的内部信号：不暴露给上层，只用于同一次调用内的模型切换。 */
    private static final class RetryableModelException extends RuntimeException {
        private RetryableModelException(String message) {
            super(message);
        }
    }
}
