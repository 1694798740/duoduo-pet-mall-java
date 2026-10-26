package com.duoduo.trade.mapper;

/** SKU 维度订单占用聚合行（A-14）：商品域删除规格前用它确认「这个 SKU 是否被订单引用过」。 */
public class SkuUsageRow {

    private Long skuId;
    private Long orderCount;

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Long orderCount) {
        this.orderCount = orderCount;
    }
}
