package com.duoduo.social.client.dto;

/**
 * 闲置卡跨域契约副本（与商品域 {@code ProductDtos.InternalListingView} 对齐，第五十三轮）。
 *
 * <p>{@code sellerId}：闲置咨询（LISTING）的收件人在服务端按闲置归属卖家定位 ——
 * 与商城商品咨询（ASK）同一纪律，不信前端传的 userId。
 */
public record ListingCardBrief(
        Long id,
        String title,
        String mainImage,
        Long price,
        Long sellerId
) {
}
