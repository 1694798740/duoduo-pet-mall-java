package com.duoduo.social.client.dto;

/**
 * 商品卡跨域契约副本（与商品域 {@code ProductDtos.InternalCardView} 对齐）。
 *
 * <p>{@code sellerId}（第四十七轮）：「问一问」的收件人在服务端按 SPU 归属卖家定位 ——
 * 不信前端传的 userId，否则「咨询」会被当成向任意用户发消息的通道。
 */
public record ProductCardBrief(
        Long id,
        String title,
        String mainImage,
        Long minPrice,
        Long sellerId
) {
}
