package com.duoduo.ai.agent;

import com.duoduo.ai.dto.ai.AiCard;
import com.duoduo.ai.dto.ai.BadgePatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件（type + data）。
 *
 * <p>事件顺序固定为：meta → (tool | confirm)* → recommendations → cards → done。
 * 前端可以在 {@code cards} 事件里拿到结构化卡片与红点补丁，
 * 在 {@code done} 里拿到最终文本与会话 ID；登录拦截则用 {@code login_required}。
 */
public record AgentEvent(String type, Object data) {

    public static AgentEvent meta(String sessionId, String requestId) {
        return new AgentEvent("meta", Map.of("sessionId", sessionId, "requestId", requestId));
    }

    public static AgentEvent token(String delta) {
        return new AgentEvent("token", Map.of("text", delta));
    }

    public static AgentEvent tool(String name, boolean success) {
        return new AgentEvent("tool", Map.of("name", name, "success", success));
    }

    public static AgentEvent confirm(String token, Object preview) {
        return new AgentEvent("confirm", Map.of("token", token, "preview", preview));
    }

    public static AgentEvent recommendations(Object data) {
        return new AgentEvent("recommendations", data);
    }

    /**
     * 结构化卡片 + 红点补丁。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：badgePatch 允许为 null
     * （「本轮没有角标变化」是正常状态），而 Map.of 不接受 null 值。
     */
    public static AgentEvent cards(List<AiCard> cards, BadgePatch badgePatch) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cards", cards == null ? List.of() : cards);
        data.put("badgePatch", badgePatch);
        return new AgentEvent("cards", data);
    }

    /** 游客轮次用尽 / 目标功能需要登录：前端据此弹登录（loginUrl 已带站内 next）。 */
    public static AgentEvent loginRequired(String reply, String loginUrl) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", reply);
        data.put("loginUrl", loginUrl);
        return new AgentEvent("login_required", data);
    }

    public static AgentEvent handoff(String reply, String summary) {
        return new AgentEvent("handoff", Map.of("reply", reply, "summary", summary));
    }

    public static AgentEvent done(String reply, String sessionId) {
        return new AgentEvent("done", Map.of("reply", reply, "sessionId", sessionId));
    }

    public static AgentEvent error(String message) {
        return new AgentEvent("error", Map.of("message", message));
    }
}
