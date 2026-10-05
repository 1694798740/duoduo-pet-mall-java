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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DashScope（阿里云百炼）OpenAI 兼容客户端（A9）。
 *
 * <p>用 JDK HttpClient 直连 REST 而不是引 SDK：本服务只调「对话补全」一个接口，
 * 一个 POST 换一个 SDK 依赖是反向性价比（与 WechatGateway 同一取舍）。
 *
 * <p>Key 只走环境变量；日志只记耗时与状态码 + **上游错误体**，**绝不落请求体与回复内容**
 * （用户消息可能带个人偏好，模型回复可能被缓存复用，都不是日志该承载的）。
 *
 * <h2>多模型自动切换（2026-10-06）</h2>
 * {@code duoduo.ai.model} 支持逗号分隔的**优先级列表**（如 {@code qwen3.8-flash,qwen3.7-flash-2026-07-15,kimi-k3}）：
 * <ul>
 *   <li><b>优先顺序 = 书写顺序</b>，第一个可用的是主力模型；</li>
 *   <li><b>触发条件</b>：当前模型返回 403（额度用尽/无权限）、404（模型不存在）、
 *       429（限流）、5xx（上游故障）、超时、或回复为空 —— 这些换了模型就可能好转；</li>
 *   <li><b>不切换的情况</b>：401（Key 本身无效/被删）—— 换模型救不了凭据，直接按凭据问题降级；</li>
 *   <li><b>粘性记忆</b>：成功过的模型会被记住，下一次请求**直接从它开始**，
 *       避免每次都为先撞一遍坏模型而多花一次失败调用；主力恢复后自然回切（下一次失败时再让位）；</li>
 *   <li>全部模型不可用 → 仍抛 DEGRADED，由 {@code ChatService} 降级为召回拼装回复。</li>
 * </ul>
 *
 * <p>背景（2026-10-05/06 两次排障）：qwen-max 的免费额度用尽导致 AI 整段 503。
 * 单模型配置下「额度用尽」只能靠人改配置重启；列表化之后，主力额度耗尽时
 * 自动落到备选上，且平台的免费额度**按模型独立计算**（每模型 1M tokens），
 * 备选各自有独立额度 —— 切换是真实有效的续命手段。
 */
@Component
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    /** 模型优先级列表（书写顺序 = 优先顺序）；至少一项，配置写错时回落到 qwen-plus */
    private final List<String> models;
    private final Duration timeout;

    /** 粘性记忆：上次成功的模型下标。下一次请求从它开始（成功后回写） */
    private volatile int preferred = 0;

    public DashScopeClient(@Value("${duoduo.ai.api-key:}") String apiKey,
                           @Value("${duoduo.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                           @Value("${duoduo.ai.model:qwen-plus}") String model,
                           @Value("${duoduo.ai.timeout-seconds:15}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.models = parseModels(model);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * 解析模型列表：逗号分隔、去空白、去空项；全部非法时退回 qwen-plus ——
     * 配置写错不该让服务起不来（与「漏标方向安全」同一取舍）。
     */
    private static List<String> parseModels(String raw) {
        List<String> list = new ArrayList<>();
        if (raw != null) {
            for (String m : raw.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
        }
        return list.isEmpty() ? List.of("qwen-plus") : List.copyOf(list);
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** 主力模型（列表第一个），供日志与能力描述使用 */
    public String primaryModel() {
        return models.get(0);
    }

    /**
     * 对话补全。所有模型都试败后统一抛 503 DEGRADED ——
     * 对调用方而言「模型这次没帮上忙」，不该区分成 N 种内部错误。
     */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return chat(systemPrompt, history, userMessage, 0);
    }

    /**
     * 对话补全（可指定 max_tokens，带多模型故障切换）。
     *
     * <p>{@code maxTokens <= 0} 表示不约束；导购的结构化选择场景给一个小上限，
     * 既省配额又天然限制「模型自由发挥」的空间。
     */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens) {
        if (!isConfigured()) {
            throw new BizException(ErrorCode.DEGRADED, "AI 服务未配置，请联系管理员完成百炼 API Key 配置");
        }

        // 从粘性记忆的下标出发，按 (preferred..n) ++ (0..preferred) 的顺序把每个模型试一遍
        int n = models.size();
        BizException last = null;
        for (int i = 0; i < n; i++) {
            int idx = (preferred + i) % n;
            String model = models.get(idx);
            try {
                String content = callOnce(model, systemPrompt, history, userMessage, maxTokens);
                if (idx != preferred) {
                    log.warn("LLM 已切换：{} → {}（主力不可用，按配置顺序落到备选）",
                            models.get(preferred), model);
                }
                preferred = idx;
                return content;
            } catch (ModelUnusableException e) {
                // 换模型可能好转（额度/限流/模型不存在/上游故障/空回复）→ 试下一个
                log.warn("模型 {} 不可用（{}），{}", model, e.reason,
                        idx == n - 1 ? "已无备选" : "尝试下一个备选模型");
                last = e.toDegraded();
            } catch (BizException e) {
                // 401（Key 无效）等「换模型救不了」的情况：直接按凭据问题降级，不浪费时间逐个试
                log.error("LLM 调用被拒绝且切换模型无济于事（{}），跳过其余备选", e.getMessage());
                throw e;
            }
        }
        throw last != null ? last
                : new BizException(ErrorCode.DEGRADED, "AI 服务暂时不可用，请稍后再试");
    }

    /**
     * 对**单个模型**的一次完整尝试。
     *
     * @throws ModelUnusableException 换模型可能好转的失败（触发故障切换）
     * @throws BizException           换模型救不了的失败（如 401 Key 无效），原样上抛
     */
    private String callOnce(String model, String systemPrompt, List<Map<String, String>> history,
                            String userMessage, int maxTokens) {
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
                log.warn("LLM 调用失败 model={} status={} cost={}ms body={}", model, resp.statusCode(), cost, head);
                // 401 = Key 本身无效/被删：换模型救不了凭据，单独识别并立即放弃其余备选
                if (resp.statusCode() == 401) {
                    throw new BizException(ErrorCode.DEGRADED, "AI 服务凭据无效，请联系管理员检查百炼 API Key");
                }
                throw new ModelUnusableException("status=" + resp.statusCode() + " " + head);
            }
            String content = extractContent(resp.body());
            if (content == null || content.isBlank()) {
                log.warn("LLM 回复为空 model={} cost={}ms", model, cost);
                throw new ModelUnusableException("回复为空");
            }
            log.info("LLM 调用成功 cost={}ms model={}", cost, model);
            return content;
        } catch (ModelUnusableException | BizException ex) {
            throw ex;
        } catch (Exception ex) {
            // 超时 / 连接失败：换模型可能好转（上游单点故障），纳入切换范围
            log.warn("LLM 调用异常 model={} cost={}ms: {}", model, System.currentTimeMillis() - start, ex.getMessage());
            throw new ModelUnusableException("超时或连接失败: " + ex.getMessage());
        }
    }

    /** 「换模型可能好转」的失败：携带原因（只进日志，不出用户界面） */
    private static final class ModelUnusableException extends RuntimeException {
        final String reason;

        ModelUnusableException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        BizException toDegraded() {
            return new BizException(ErrorCode.DEGRADED, "AI 服务暂时不可用，请稍后再试");
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
