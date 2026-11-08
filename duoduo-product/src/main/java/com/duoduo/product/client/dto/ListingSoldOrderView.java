package com.duoduo.product.client.dto;

import java.time.LocalDateTime;

/**
 * 卖出订单视图（交易域 → 商品域 → 卖家的「我的闲置 · 卖出订单」页 · B-11）。
 *
 * <p>订单与资金的事实表（t_order / t_settlement）都在交易域，
 * 商品域只透传；这里的字段是卖家视角的最小集合：
 * 卖家最关心「谁买了（什么时候）、发没发货、钱到没到账」。
 */
public record ListingSoldOrderView(
        String orderNo,
        /** 订单状态机：PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CANCELLED / REFUNDED */
        String status,
        Long listingId,
        String listingTitle,
        Long payAmount,
        String logisticsCompany,
        String logisticsNo,
        LocalDateTime createdAt
) {
}
