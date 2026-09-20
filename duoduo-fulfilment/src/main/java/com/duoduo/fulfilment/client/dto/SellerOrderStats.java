package com.duoduo.fulfilment.client.dto;

/**
 * 交易域「某卖家订单状态计数」（与 {@code com.duoduo.trade.dto.OrderDtos.SellerOrderStats} 对齐）。
 *
 * <p>手写一份而不依赖交易域的 dto 包：跨服务共享 DTO 会让两个服务必须同时发布，
 * 一处加字段就可能让另一处编译不过。契约在这里只体现为「字段名与语义」，由冒烟测试守住。
 *
 * <p>刻意只有计数、没有订单明细 —— 信用分只需要几个整数，收货人信息不该因此流动（INV-9）。
 */
public record SellerOrderStats(
        Long sellerId,
        int pendingPaymentOrders,
        int paidOrders,
        int shippedOrders,
        int completedOrders,
        int closedOrders,
        int refundedOrders,
        int totalOrders
) {
}
