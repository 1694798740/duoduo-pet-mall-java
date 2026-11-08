package com.duoduo.product.client.dto;

/**
 * C2C 下单结果（交易域 → 商品域 · B-11）：订单号与应付金额（分）。
 *
 * <p>演示链路里买家拿它去走既有支付端点（沙箱 mock 通道），
 * 商品域不再自建任何支付入口。
 */
public record ListingOrderCreated(
        String orderNo,
        Long payAmount
) {
}
