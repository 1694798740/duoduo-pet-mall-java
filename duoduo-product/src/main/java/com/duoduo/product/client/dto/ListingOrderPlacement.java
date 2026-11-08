package com.duoduo.product.client.dto;

/**
 * C2C 下单载荷（商品域 → 交易域，内部端点 · B-11）。
 *
 * <p>字段全部由商品域<b>服务端从闲置事实里取</b>（归属、标题、价格快照），
 * 连买家 id 也来自登录态透传 —— 交易域拿到后不需要再回查商品库，
 * 也不接受任何「前端说了算」的字段。
 *
 * <p>价格与标题在这里固化成快照：之后卖家改价 / 改标题都不影响已成立的订单，
 * 这与商城订单的 SKU 快照纪律一致。
 */
public record ListingOrderPlacement(
        Long buyerId,
        Long listingId,
        Long sellerId,
        String title,
        Long priceFen,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String idempotentKey
) {
}
