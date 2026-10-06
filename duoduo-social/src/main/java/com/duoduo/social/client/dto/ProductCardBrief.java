package com.duoduo.social.client.dto;

/**
 * 商品卡跨域契约副本（与商品域 {@code ProductDtos.InternalCardView} 对齐）。
 *
 * <p>{@code sellerId}（第四十七轮）：「问一问」的收件人在服务端按 SPU 归属卖家定位 ——
 * 不信前端传的 userId，否则「咨询」会被当成向任意用户发消息的通道。
 *
 * <p>{@code supplyType}（第七十三轮）：自营/入驻判定随卡下发。自营商品（self）的
 * sellerId 一律为 0（平台自营），咨询收件人改按本字段定到官方客服账号 ——
 * 「卖家归属」（交易拆单）与「咨询归属」（问一问）自此不再共用一个字段。
 */
public record ProductCardBrief(
        Long id,
        String title,
        String mainImage,
        Long minPrice,
        Long sellerId,
        String supplyType
) {
}
