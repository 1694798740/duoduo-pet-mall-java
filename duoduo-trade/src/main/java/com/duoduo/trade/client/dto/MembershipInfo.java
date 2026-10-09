package com.duoduo.trade.client.dto;

/**
 * 店铺成员身份（E13）：user 服务 `/api/users/internal/merchant-membership` 的响应体副本。
 *
 * <p>跨服务 DTO 刻意**复制而不是共享**：交易域需要的是"这个人以哪家店的身份行动"
 * 这四个字段，共享整份 DTO 会把 user 域的视图结构耦合进交易域的编译单元。
 */
public record MembershipInfo(
        String merchantId,
        String ownerUserId,
        String memberRole,
        String status
) {
    /** 是否现役店员（店主要求且状态 ACTIVE） */
    public boolean activeStaff() {
        return "STAFF".equals(memberRole) && "ACTIVE".equals(status);
    }
}
