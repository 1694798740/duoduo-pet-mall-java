package com.duoduo.trade.service;

import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.AdminDtos.AbnormalSettlement;
import com.duoduo.trade.dto.AdminDtos.AdminReport;
import com.duoduo.trade.dto.AdminDtos.SettlementSummary;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 平台管理后台（M10）运营对账与报表。
 *
 * <p>刻意与商家端 {@link SellerService} 分开：商家端对账是「我这个卖家有多少钱」，
 * 这里对账是「整个平台的钱对不对得上」——口径不同、可见范围不同、权限也不同。
 * 全部入口由 {@link OperatorGuard#requireOperator()} 把关。
 */
@Service
public class AdminOpsService {

    private final SettlementMapper settlementMapper;
    private final OrderMapper orderMapper;

    public AdminOpsService(SettlementMapper settlementMapper, OrderMapper orderMapper) {
        this.settlementMapper = settlementMapper;
        this.orderMapper = orderMapper;
    }

    /** 全平台托管资金汇总（托管中 / 已放款 / 已退回） */
    public SettlementSummary settlementSummary() {
        OperatorGuard.requireOperator();
        List<Map<String, Object>> rows = settlementMapper.aggregateAll();
        long custodyAmount = 0, custodyFee = 0, releasedAmount = 0, releasedFee = 0, refundedAmount = 0;
        int custodyCount = 0, releasedCount = 0, refundedCount = 0;
        for (Map<String, Object> row : rows) {
            String status = str(row, "status");
            long amount = lng(row, "amount_sum");
            long fee = lng(row, "fee_sum");
            int cnt = (int) lng(row, "cnt");
            switch (status) {
                case "CUSTODY" -> { custodyAmount = amount; custodyFee = fee; custodyCount = cnt; }
                case "RELEASED" -> { releasedAmount = amount; releasedFee = fee; releasedCount = cnt; }
                case "REFUNDED" -> { refundedAmount = amount; refundedCount = cnt; }
                default -> { /* 未知状态忽略，避免一条脏数据拖垮整个汇总 */ }
            }
        }
        return new SettlementSummary(custodyAmount, custodyFee, custodyCount,
                releasedAmount, releasedFee, releasedCount, refundedAmount, refundedCount);
    }

    /** 异常挂账：已付款却缺托管单的订单 */
    public List<AbnormalSettlement> abnormalSettlements() {
        OperatorGuard.requireOperator();
        return orderMapper.findOrdersWithoutSettlement().stream()
                .map(row -> new AbnormalSettlement(
                        str(row, "orderNo"),
                        str(row, "orderStatus"),
                        lng(row, "payAmount")))
                .toList();
    }

    /** 运营概览报表：GMV / 订单量 / 退款率 / 转化率 */
    public AdminReport report() {
        OperatorGuard.requireOperator();
        List<Map<String, Object>> rows = orderMapper.countAllByStatus();
        int total = 0, paid = 0, refunded = 0;
        for (Map<String, Object> row : rows) {
            String status = str(row, "status");
            int cnt = (int) lng(row, "cnt");
            total += cnt;
            switch (status) {
                case "PAID", "SHIPPED", "COMPLETED" -> paid += cnt;
                case "REFUNDED" -> refunded += cnt;
                default -> { /* PENDING_PAYMENT / CLOSED 只计入 total */ }
            }
        }
        long gmv = orderMapper.sumPaidAmount();
        // 退款率 = 退款单 / 已付款单；转化率 = 已付款单 / 全部单。分母为零时给 0，不抛异常。
        String refundRate = paid > 0 ? percent(refunded, paid) : "0.00%";
        String conversionRate = total > 0 ? percent(paid, total) : "0.00%";
        return new AdminReport(total, paid, gmv, refunded, refundRate, conversionRate);
    }

    private static String percent(long numerator, long denominator) {
        double value = denominator == 0 ? 0 : (double) numerator / denominator * 100;
        return String.format("%.2f%%", value);
    }

    /** 列名大小写容错：不同 JDBC 驱动可能回传 {@code CNT} / {@code amount_sum} 等，写死会全零 */
    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
