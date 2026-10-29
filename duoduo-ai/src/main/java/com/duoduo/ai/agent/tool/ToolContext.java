package com.duoduo.ai.agent.tool;

/**
 * 工具执行上下文。
 *
 * <p>身份只来自登录态（控制器从令牌解析后传入），**绝不从模型参数取 userId**。
 */
public record ToolContext(
        Long userId,
        String role,
        String phone,
        String sessionId,
        String requestId,
        boolean loggedIn
) {
    public ToolContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
    }
}
