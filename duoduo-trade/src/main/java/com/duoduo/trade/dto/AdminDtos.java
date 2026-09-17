package com.duoduo.trade.dto;

/**
 * 平台管理后台（M10）交易域 DTO。
 *
 * <p>金额一律整数「分」，前端展示层再格式化。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 资金托管全量汇总（跨全平台，与商家端按卖家过滤的汇总口径分开） */
    public record SettlementSummary(
            long custodyAmount,
            long custodyFee,
            int custodyCount,
            long releasedAmount,
            long releasedFee,
            int releasedCount,
            long refundedAmount,
            int refundedCount
    ) {
    }

    /** 异常挂账：已付款但缺托管单的订单（放款/退款时会找不到账本） */
    public record AbnormalSettlement(
            String orderNo,
            String orderStatus,
            long payAmount
    ) {
    }

    /** 运营概览报表 */
    public record AdminReport(
            int totalOrders,
            int paidOrders,
            long gmv,
            int refundedOrders,
            String refundRate,
            String conversionRate
    ) {
    }

    /** 运营强制关单请求 */
    public record ForceCloseRequest(
            @jakarta.validation.constraints.Size(max = 100, message = "关单原因过长") String reason
    ) {
    }
}
