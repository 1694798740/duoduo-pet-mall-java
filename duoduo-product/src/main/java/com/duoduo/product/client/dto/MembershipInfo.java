package com.duoduo.product.client.dto;

/**
 * 店铺成员身份（E14）：用户服务 {@code /api/users/internal/merchant-membership} 的响应体副本。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与交易域 {@code trade.client.dto.MembershipInfo}
 * 同一纪律）：商品域需要的是「这个人以哪家店的身份行动」这四个字段，
 * 共享整份 DTO 会把用户域的视图结构耦合进商品域的编译单元 ——
 * 用户域加一个字段，商品域跟着重新编译，而它根本用不上。
 */
public record MembershipInfo(
        String merchantId,
        String ownerUserId,
        String memberRole,
        String status
) {
    /** 是否现役店员（角色为 STAFF 且状态 ACTIVE）。停用后 status 变 DISABLED → false */
    public boolean activeStaff() {
        return "STAFF".equals(memberRole) && "ACTIVE".equals(status);
    }
}
