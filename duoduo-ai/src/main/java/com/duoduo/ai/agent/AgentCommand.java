package com.duoduo.ai.agent;

/**
 * 一轮对话命令。身份由控制器从登录态解析，模型永远拿不到 userId。
 *
 * @param sessionId 前端会话 ID（空则由服务端生成）
 * @param userId    登录用户 ID；null = 游客
 * @param role      角色（USER / SELLER / ADMIN…）；游客为 null
 * @param phone     手机号；游客为 null
 * @param message   用户本轮原话（已由 Bean Validation 限制长度）
 * @param deviceId  游客设备标识（HttpOnly Cookie + Redis 轮次计数用）；登录用户也带，但不计配额
 * @param pageUrl   用户发起对话时所在页面（已过白名单，只保留路径、不含查询串）；可为 null
 * @param clarified 本次之前是否已经澄清过一轮（由前端带上来）；用服务端一次对话最多反问一次
 */
public record AgentCommand(
        String sessionId,
        Long userId,
        String role,
        String phone,
        String message,
        String deviceId,
        String pageUrl,
        boolean clarified
) {
}
