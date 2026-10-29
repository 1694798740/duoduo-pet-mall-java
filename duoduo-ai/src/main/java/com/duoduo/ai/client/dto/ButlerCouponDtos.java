package com.duoduo.ai.client.dto;

/**
 * 营销域（duoduo-marketing）券视图（AI 侧只读）。
 *
 * <p>金额一律整数分：{@code thresholdAmount} 是门槛、{@code discountAmount} 是满减固定额、
 * {@code discountPercent} 是折扣券折率（85 = 8.5 折）、{@code maxDiscountAmount} 是折扣券封顶。
 * 这四个值**必须原样透传**，不允许 AI 侧做二次换算 —— 否则卡片上的金额与结算页对不上。
 */
public final class ButlerCouponDtos {

    private ButlerCouponDtos() {
    }

    /** 我的券（已领取），字段与营销域 CouponView 对齐并按 AI 需要裁剪（去掉订单号等明细）。 */
    public record CouponView(
            String code,
            Long templateId,
            String name,
            String type,
            String typeLabel,
            long thresholdAmount,
            long discountAmount,
            int discountPercent,
            long maxDiscountAmount,
            String status,
            String statusLabel,
            String claimedAt,
            String expireAt
    ) {
    }
}
