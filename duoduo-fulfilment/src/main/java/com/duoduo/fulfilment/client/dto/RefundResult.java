package com.duoduo.fulfilment.client.dto;

/**
 * 交易域退款结果（与 {@code com.duoduo.trade.dto.OrderDtos.RefundResult} 对齐）。
 *
 * <p>{@code refundAmount} 是**钱的权威口径**：售后单里的退款金额直接取它回填，
 * 本域不自己算。理由见 {@code AfterSaleEntity} 的注释。
 *
 * <p>{@code settlementStatus} 用来确认「托管单确实转成了 REFUNDED」——
 * 售后单显示「已退款」而托管单还挂在 CUSTODY，是最典型的账目对不上，
 * 值得在断言里盯住。
 */
public record RefundResult(
        String orderNo,
        String orderStatus,
        String orderStatusLabel,
        long refundAmount,
        boolean fundsRefunded,
        String settlementStatus,
        String settlementStatusLabel,
        boolean restockPending,
        String refundedAt
) {
}
