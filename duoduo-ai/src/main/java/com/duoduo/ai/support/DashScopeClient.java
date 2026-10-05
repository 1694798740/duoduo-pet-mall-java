package com.duoduo.ai.support;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DashScope（阿里云百炼）OpenAI 兼容客户端（A9）。
 *
 * <p>用 JDK HttpClient 直连 REST 而不是引 SDK：本服务只调「对话补全」一个接口，
 * 一个 POST 换一个 SDK 依赖是反向性价比（与 WechatGateway 同一取舍）。
 *
 * <p>Key 只走环境变量；日志只记耗时与状态码，**绝不落请求体与回复内容**
 * （用户消息可能带个人偏好，模型回复可能被缓存复用，都不是日志该承载的）。
 */
@Component
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    public DashScopeClient(@Value("${duoduo.ai.api-key:}") String apiKey,
                           @Value("${duoduo.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                           @Value("${duoduo.ai.model:qwen-plus}") String model,
                           @Value("${duoduo.ai.timeout-seconds:15}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 对话补全。任何失败（超时 / 5xx / 内容缺失）统一抛 503 DEGRADED ——
     * 对调用方而言「模型这次没帮上忙」，不该区分成 N 种内部错误。
     */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return chat(systemPrompt, history, userMessage, 0);
    }

    /**
     * 对话补全（可指定 max_tokens）。
     *
     * <p>{@code maxTokens <= 0} 表示不约束；导购的结构化选择场景给一个小上限，
     * 既省配额又天然限制「模型自由发挥」的空间。
     */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens) {
        if (!isConfigured()) {
            throw new BizException(ErrorCode.DEGRADED, "AI 服务未配置，请联系管理员完成百炼 API Key 配置");
        }
        var messages = new java.util.ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        String body = buildBody(model, messages, maxTokens);
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/chat/completions"))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            if (resp.statusCode() / 100 != 2) {
                // ★ 把上游错误体记进日志：状态码只有 401/403/429 一个数字，
                //   「额度用尽」与「key 无效」的排障方向完全不同 —— 只记状态码等于
                //   让下一次排障还要手工复现一遍才能看到原因（2026-10-05 实测教训）。
                String errBody = resp.body();
                String head = errBody == null ? "" : errBody.substring(0, Math.min(errBody.length(), 300));
                log.warn("LLM 调用失败 status={} cost={}ms body={}", resp.statusCode(), cost, head);
                throw new BizException(ErrorCode.DEGRADED, "AI 服务暂时不可用，请稍后再试");
            }
            String content = extractContent(resp.body());
            if (content == null || content.isBlank()) {
                log.warn("LLM 回复为空 cost={}ms", cost);
                throw new BizException(ErrorCode.DEGRADED, "AI 服务暂时不可用，请稍后再试");
            }
            log.info("LLM 调用成功 cost={}ms model={}", cost, model);
            return content;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("LLM 调用异常 cost={}ms: {}", System.currentTimeMillis() - start, ex.getMessage());
            throw new BizException(ErrorCode.DEGRADED, "AI 服务超时或不可用，请稍后再试", ex);
        }
    }

    /** OpenAI 兼容请求体（受控结构，手写拼接避免引入额外序列化依赖） */
    private static String buildBody(String model, List<Map<String, String>> messages, int maxTokens) {
        StringBuilder sb = new StringBuilder("{\"model\":\"").append(model).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            var m = messages.get(i);
            String role = m.get("role") == null ? "user" : m.get("role");
            String content = m.get("content") == null ? "" : m.get("content")
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            sb.append("{\"role\":\"").append(role).append("\",\"content\":\"").append(content).append("\"}");
        }
        sb.append(']');
        if (maxTokens > 0) {
            sb.append(",\"max_tokens\":").append(maxTokens);
        }
        return sb.append('}').toString();
    }

    /** 从 OpenAI 兼容响应里取 choices[0].message.content；解析失败按依赖故障处理 */
    private static String extractContent(String body) {
        int idx = body.indexOf("\"content\"");
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('"', colon);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
