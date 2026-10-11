package com.duoduo.trade.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.trade.dto.AdminDtos.AbnormalSettlement;
import com.duoduo.trade.dto.AdminDtos.AdminReport;
import com.duoduo.trade.dto.AdminDtos.SettlementSummary;
import com.duoduo.trade.dto.AnalyticsDtos;
import com.duoduo.trade.dto.AnalyticsDtos.AdminCharts;
import com.duoduo.trade.dto.AnalyticsDtos.Slice;
import com.duoduo.trade.dto.AnalyticsDtos.TrendPoint;
import com.duoduo.trade.dto.SettlementDtos.SellerWalletView;
import com.duoduo.trade.dto.SettlementDtos.SettlementBill;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import com.duoduo.trade.support.OrderStatus;
import com.duoduo.trade.support.TrendSeries;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
    /** E22 · 钱包口径的唯一来源：平台侧与商家侧必须调用同一个方法，不允许各算一遍 */
    private final SettlementService settlementService;

    public AdminOpsService(SettlementMapper settlementMapper,
                           OrderMapper orderMapper,
                           SettlementService settlementService) {
        this.settlementMapper = settlementMapper;
        this.orderMapper = orderMapper;
        this.settlementService = settlementService;
    }

    /**
     * E22 · 指定租户的钱包（待结算 / 冻结中 / 可提现 / 欠款）。
     *
     * <p><b>与商家端同源</b>：直接调 {@code SettlementService.walletOf}。
     * 平台侧与商家侧看到的可提现金额必须逐分一致 ——
     * 两边各算一遍是本项目明确禁止的模式（同 E20「判定点只有一个」的纪律）：
     * 一旦分叉，出现的是"平台说能提 500、商家看到 480"这种谁也说不清谁对的分歧。
     *
     * <p>权限用 {@link Permission#FINANCE_SETTLEMENT}（矩阵里**仅 ADMIN**）：
     * 资金视图比"看订单"更敏感，它暴露的是平台抽佣总额与卖家欠款规模。
     * 与 {@link #settlementSummary()} 的差别正在于此 —— 那个是"全平台对不对得上"的运维视图（运营也要看），
     * 这个是"某个租户能拿走多少钱"的财务视图。
     */
    public SellerWalletView tenantWallet(Long tenantId) {
        OperatorGuard.requireOperator();
        LoginUser actor = UserContext.require();
        if (!actor.hasPerm(Permission.FINANCE_SETTLEMENT)) {
            throw BizException.forbidden("查看租户资金钱包需要财务权限（仅管理员）");
        }
        if (tenantId == null) {
            throw BizException.validation("请指定租户（店铺主体 userId）");
        }
        return settlementService.walletOf(tenantId);
    }

    /**
     * E22 · 指定租户的结算账单（租户 × 周期）。
     *
     * <p><b>与商家端同源</b>：直接调 {@code SettlementService.billOf}。
     * 平台看到的账单必须与商家看到的一模一样（逐分一致），否则对账会变成
     * 一场"谁的数据才对"的争论 —— 而这种争论在技术上无解，因为两边都自认为对。
     *
     * <p>权限同 {@link #tenantWallet}：{@link Permission#FINANCE_SETTLEMENT}（仅 ADMIN）。
     * 账单比钱包更敏感的地方在于它同时暴露**平台抽佣总额**，
     * 那是平台的商业条款，不能给到运营角色。
     */
    public SettlementBill tenantBill(Long tenantId, String period) {
        OperatorGuard.requireOperator();
        LoginUser actor = UserContext.require();
        if (!actor.hasPerm(Permission.FINANCE_SETTLEMENT)) {
            throw BizException.forbidden("查看租户结算账单需要财务权限（仅管理员）");
        }
        if (tenantId == null) {
            throw BizException.validation("请指定租户（店铺主体 userId）");
        }
        return settlementService.billOf(tenantId, period);
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

    /**
     * 运营概览的图表数据（第六十六轮）。
     *
     * <p>与 {@link #report()} 分成两个接口，理由与商家端一致：报表卡是「一眼要看的数」，
     * 图表是「细看的形状」；图表取数出问题时，报表卡与资金对账仍然应该正常显示。
     *
     * <p>四个维度覆盖用户要求的四类图形：分布图（状态分布）、折线图（趋势）、
     * 柱状图（商品销量 Top）、饼图（资金构成）。全部走 SQL 聚合 —— 演示库就已经有
     * 五千多笔订单，「捞明细回内存聚合」是一次性便宜、之后越来越贵的写法。
     */
    public AdminCharts charts() {
        OperatorGuard.requireOperator();

        // 分布：全平台订单状态（中文标签由 OrderStatus 统一给出，前端不再维护一份映射）
        Map<String, Long> statusCount = new HashMap<>();
        for (Map<String, Object> row : orderMapper.countAllByStatus()) {
            statusCount.merge(str(row, "status"), lng(row, "cnt"), Long::sum);
        }
        List<Slice> statusDistribution = new ArrayList<>();
        for (OrderStatus s : OrderStatus.values()) {
            statusDistribution.add(new Slice(s.label(), statusCount.getOrDefault(s.name(), 0L)));
        }

        // 折线：近 14 天趋势（补零口径与商家端共用 TrendSeries，否则两个页面的横轴会对不上）
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDay = today.minusDays(AnalyticsDtos.TREND_DAYS - 1L);
        List<TrendPoint> trend = TrendSeries.fill(
                orderMapper.dailyTrend(null, fromDay.atStartOfDay()),
                fromDay, AnalyticsDtos.TREND_DAYS);

        // 柱状：全平台商品销量 Top N
        List<Slice> topProducts = orderMapper.topProductsBySales(null, AnalyticsDtos.TOP_PRODUCT_LIMIT).stream()
                .map(row -> new Slice(str(row, "name"), lng(row, "qty")))
                .toList();

        // 饼图：全平台资金构成
        Map<String, Long> fund = new HashMap<>();
        for (Map<String, Object> row : settlementMapper.aggregateAll()) {
            fund.merge(str(row, "status"), lng(row, "amount_sum"), Long::sum);
        }
        List<Slice> fundComposition = List.of(
                new Slice("托管中", fund.getOrDefault(SettlementEntity.STATUS_CUSTODY, 0L)),
                new Slice("已放款", fund.getOrDefault(SettlementEntity.STATUS_RELEASED, 0L)),
                new Slice("已退回", fund.getOrDefault(SettlementEntity.STATUS_REFUNDED, 0L)));

        return new AdminCharts(statusDistribution, trend, topProducts, fundComposition,
                LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
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
