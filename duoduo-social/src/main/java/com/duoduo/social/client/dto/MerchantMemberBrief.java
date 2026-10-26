package com.duoduo.social.client.dto;

/**
 * 工单转交候选人（C-04，用户域内部端点 {@code /internal/merchant-members} 的裁剪副本）。
 *
 * <p>与其它跨域 DTO 一样复制而不是共享：社交域只用来渲染下拉与校验目标归属，
 * 不携带手机号等管理字段。
 */
public record MerchantMemberBrief(
        String userId,
        String nickname,
        String avatar,
        String role
) {
}
