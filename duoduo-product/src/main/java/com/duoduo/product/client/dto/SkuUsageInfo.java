package com.duoduo.product.client.dto;

/**
 * 单个 SKU 的订单占用结果（A-14）。
 *
 * <p>{@code orderCount} 是订单明细条数：同一订单里同一 SKU 会合并成一条明细，
 * 因此它不是「卖了多少件」，而是「被多少条订单明细引用过」。
 * 删除门禁只关心「有没有被引用」，不需要数量口径。
 */
public record SkuUsageInfo(Long skuId, long orderCount) {
}
