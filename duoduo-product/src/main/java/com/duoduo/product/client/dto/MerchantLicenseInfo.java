package com.duoduo.product.client.dto;

import java.time.LocalDateTime;

/**
 * 店铺资质与营业状态（E15）：用户服务 {@code /api/users/internal/merchant-license} 的响应体副本。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与本域 {@link MembershipInfo}、交易域
 * {@code MembershipInfo} 同一纪律）：商品域做上架门禁只需要「这家店现在能不能卖」，
 * 用户域往后给这份视图加字段（比如下一次续期时间），商品域不该跟着重新编译。
 *
 * <p>与 {@link MembershipInfo} 是两个问题：那个查「这个人是不是店员」，
 * 这个查「这家店能不能卖」—— 店主自己走商家端时不经过成员查询，
 * 所以只补成员查询拦不住「已停业的店主继续上架」。
 */
public record MerchantLicenseInfo(
        String merchantId,
        /** APPROVED / SUBMITTED / REVIEWING / SUSPENDED / … */
        String status,
        /** NONE / T30 / T7 / EXPIRED */
        String licenseStage,
        LocalDateTime licenseExpireAt,
        String suspendedReason
) {
    /** 是否可以对外销售（判据是状态，不是「没过期」—— 续期审核中同样不能卖） */
    public boolean canSell() {
        return "APPROVED".equals(status);
    }
}
