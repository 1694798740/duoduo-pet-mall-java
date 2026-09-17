package com.duoduo.trade.client.dto;

/**
 * 商品服务的 SKU 快照（跨服务传输对象）。
 *
 * <p>刻意**不复用**商品服务的 DTO：微服务之间不共享实体 / DTO 类是刻意的边界选择 ——
 * 共享类会让两个服务的发布节奏强行绑定（改一个字段就要同时发布两个服务）。
 * 这里只声明交易服务真正需要的字段，字段名与商品服务返回的 JSON 对齐，
 * 商品服务新增字段不会影响本服务的反序列化。
 *
 * @param skuId          SKU 主键
 * @param spuId          SPU 主键
 * @param spuTitle       商品标题（购物车列表要展示）
 * @param specText       已拍平的规格串，例如「规格:2kg / 口味:冻干双拼」
 * @param image          展示图（SKU 图优先，缺失时回退 SPU 主图）
 * @param price          单价，单位「分」
 * @param availableStock 可售库存 = 商品服务的 stock - frozenStock
 * @param sellable       服务端算好的「是否可下单」（含上下架与库存两类判定）
 * @param spuStatus      SPU 状态（ON_SALE / OFF_SHELF）
 * @param skuStatus      SKU 状态（ACTIVE / OFF_SHELF）
 */
public record SkuSnapshot(
        Long skuId,
        Long spuId,
        String spuTitle,
        String specText,
        String image,
        long price,
        int availableStock,
        boolean sellable,
        String spuStatus,
        String skuStatus
) {
}
