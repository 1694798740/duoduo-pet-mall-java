package com.duoduo.social.client.dto;

/**
 * 用户角色跨域契约副本（第四十七轮新增端点 {@code /api/users/internal/roles}）。
 *
 * <p>私信要按发送方角色分流通知（普通用户 → 好友消息，卖家 → 商家消息），
 * 所以发消息时点取一次角色并落列（快照），不随展示反查。
 */
public record UserRoleBrief(
        Long id,
        String role
) {
}
