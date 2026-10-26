package com.duoduo.social.client.dto;

/**
 * 店铺成员身份（用户域内部端点 `/internal/merchant-membership` 的裁剪副本）。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与商品域、交易域同名 DTO 同一纪律）：
 * 社交域只需要「这个登录账号属于哪家店、店主是谁」，
 * 不需要带入驻材料、资质状态等本域不用的字段。
 *
 * <p>{@code ownerUserId} 是**店铺主体**（店主 userId），也是客服会话的归属键：
 * 店主与店员都解析到同一个值，会话才能按店铺维度被全店看到。
 */
public record MembershipBrief(
        String merchantId,
        String ownerUserId,
        String memberRole,
        String status,
        /** F-04 · 店员生效权限键（店主不走本接口） */
        java.util.List<String> permissions
) {
    /** 是否具备某项操作权限（null 清单按无权限处理）。 */
    public boolean hasPermission(com.duoduo.common.seller.StaffPermission permission) {
        return com.duoduo.common.seller.StaffPermissions.has(permissions, permission);
    }
}
