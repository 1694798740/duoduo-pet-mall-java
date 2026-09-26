package com.duoduo.fulfilment.client.dto;

/**
 * 商品快照（内部，来自商品域 {@code /api/products/internal/cards}，第四十三轮 · 分享圈）。
 *
 * <p>只含分享展示必需的四个字段 —— 与用户域 {@link UserBrief} 同一条裁剪口径：
 * 跨域取数按「调用方真正需要什么」裁，不是「对方能给什么」。
 */
public record ProductCardBrief(
        Long id,
        String title,
        String mainImage,
        Long minPrice
) {
}
