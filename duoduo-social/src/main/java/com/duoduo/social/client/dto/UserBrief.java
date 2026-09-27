package com.duoduo.social.client.dto;

/**
 * 用户公开资料跨域契约副本（与用户域 {@code UserDtos.UserBrief} 对齐）：
 * 只取展示所需的三个字段，手机号 / 实名 / 角色一律不拉 —— 跨域按需裁剪，
 * 是减少隐私扩散面最省事的一步。
 */
public record UserBrief(
        Long id,
        String nickname,
        String avatar
) {
}
