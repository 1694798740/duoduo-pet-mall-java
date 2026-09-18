package com.duoduo.trade.dto;

import java.util.List;

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

    /**
     * 托管单回补计划/结果中的一条：某订单将被（已）补成什么资金状态。
     *
     * <p>回补的请求参数刻意<b>不走请求体</b>，用查询串 {@code ?dryRun=true}（见
     * {@code AdminSettlementController#backfill}）：一是与本项目既有的布尔开关写法一致，
     * 二是绕开「客户端默认 Content-Type 不匹配 → 415」这一类只在真实客户端上才暴露的坑。
     */
    public record BackfillItem(
            String orderNo,
            String orderStatus,
            String settlementStatus,
            long amount,
            long platformFee
    ) {
    }

    /**
     * 托管单回补结果。
     *
     * <p>把三组金额分开返回而不是只给一个总额：回补是**写账**操作，
     * 运营需要能立刻核对「补出来的托管中 / 已放款 / 已退回各是多少」，
     * 只给一个合计等于让人再去翻明细。
     *
     * <p>{@code commissionBps} 与 {@code commissionNote} 是审计字段：
     * 历史订单没有留档「结算当刻的费率」，本次回补只能用当前配置重算，
     * 这个口径必须随结果一起返回并落库说明，否则半年后没人说得清这些佣金是怎么来的。
     */
    public record BackfillResult(
            boolean dryRun,
            int scanned,
            int created,
            int skipped,
            int custodyCount,
            int releasedCount,
            int refundedCount,
            long custodyAmount,
            long releasedAmount,
            long refundedAmount,
            int commissionBps,
            String commissionNote,
            List<BackfillItem> items
    ) {
    }
}
