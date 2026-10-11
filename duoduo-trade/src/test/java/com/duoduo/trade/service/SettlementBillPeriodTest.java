package com.duoduo.trade.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.dto.SettlementDtos.SettlementBill;
import com.duoduo.trade.mapper.SellerDebtMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E22 · 结算账单（租户 × 周期）单测。
 *
 * <h2>为什么这段必须单独测</h2>
 * 账单的费用段由 SQL 聚合，冒烟能覆盖"这个周期里确实有数"；
 * 但**周期边界**与**格式非法时的行为**冒烟覆盖不到 ——
 * 而恰恰是这两处最容易出静默错误：
 * <ul>
 *   <li>边界写错（比如右端也取"含"），会让"10 月 31 日 23:59:59.999"这条订单
 *       同时落进 10 月和 11 月，或者两边都不落。前者表现为金额虚高、后者为虚低，
 *       而两种都不报错；</li>
 *   <li>格式非法时若回落到"当前月"，用户查 9 月会看到 10 月的数，
 *       页面一切正常，只是答案是错的 —— 这是最难被发现的一类 bug。</li>
 * </ul>
 */
class SettlementBillPeriodTest {

    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final SellerDebtMapper sellerDebtMapper = mock(SellerDebtMapper.class);
    private final SettlementService service = new SettlementService(
            settlementMapper, sellerDebtMapper, mock(SnowflakeIdGenerator.class));

    /** 让 flow 聚合返回一组可控的数 */
    private void stubFlow(long orderAmount, long fee, long orderCount) {
        Map<String, Object> flow = new HashMap<>();
        flow.put("orderAmount", orderAmount);
        flow.put("platformFeeAmount", fee);
        flow.put("sellerEarningAmount", orderAmount - fee);
        flow.put("orderCount", orderCount);
        when(settlementMapper.billFlowAggregate(any(), any(), any())).thenReturn(flow);
    }

    /** 记录并返回 service 实际传给 Mapper 的周期窗口 */
    private LocalDateTime[] captureWindow() {
        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(settlementMapper).billFlowAggregate(any(), start.capture(), end.capture());
        return new LocalDateTime[]{start.getValue(), end.getValue()};
    }

    @Test
    @DisplayName("月周期：2026-10 → [10-01T00:00Z, 11-01T00:00Z)，左闭右开")
    void monthPeriodIsHalfOpen() {
        stubFlow(0, 0, 0);
        SettlementBill bill = service.billOf(1001L, "2026-10");

        assertThat(bill.period()).isEqualTo("2026-10");
        LocalDateTime[] w = captureWindow();
        assertThat(w[0]).isEqualTo(LocalDateTime.parse("2026-10-01T00:00:00"));
        assertThat(w[1]).isEqualTo(LocalDateTime.parse("2026-11-01T00:00:00"));
        // 右端不含：11-01 零点整的那笔订单属于 11 月，不属于 10 月
        assertThat(w[1]).isAfter(w[0]);
        assertThat(bill.periodStart()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(bill.periodEnd()).isEqualTo("2026-11-01T00:00:00Z");
    }

    @Test
    @DisplayName("12 月跨年：2026-12 → 2027-01-01，不能算成 2026-13")
    void decemberCrossesYear() {
        stubFlow(0, 0, 0);
        SettlementBill bill = service.billOf(1001L, "2026-12");
        LocalDateTime[] w = captureWindow();
        assertThat(w[1]).isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00"));
        assertThat(bill.period()).isEqualTo("2026-12");
    }

    @Test
    @DisplayName("日周期：2026-10-05 → [10-05, 10-06)，且周期串原样回述")
    void dayPeriod() {
        stubFlow(0, 0, 0);
        SettlementBill bill = service.billOf(1001L, "2026-10-05");
        assertThat(bill.period()).isEqualTo("2026-10-05");
        LocalDateTime[] w = captureWindow();
        assertThat(w[0]).isEqualTo(LocalDateTime.parse("2026-10-05T00:00:00"));
        assertThat(w[1]).isEqualTo(LocalDateTime.parse("2026-10-06T00:00:00"));
    }

    @Test
    @DisplayName("不传周期 = 当前自然月；起点必为月初、终点必为下月初")
    void blankPeriodFallsBackToCurrentMonth() {
        stubFlow(0, 0, 0);
        SettlementBill bill = service.billOf(1001L, "   ");

        LocalDateTime[] w = captureWindow();
        assertThat(w[0].getDayOfMonth()).isEqualTo(1);
        assertThat(w[0].toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(w[1]).isEqualTo(w[0].plusMonths(1));
        assertThat(bill.period()).isEqualTo(w[0].toLocalDate().toString().substring(0, 7));
    }

    @Test
    @DisplayName("★ 周期格式非法一律 400：绝不静默回落到当前月（回落=不报错的错答案）")
    void invalidPeriodIsRejected() {
        for (String bad : new String[]{"2026-13", "2026/10", "上个月", "2026-1", "202610", "最近30天", "2026-10-32"}) {
            assertThatThrownBy(() -> service.billOf(1001L, bad))
                    .as("周期「%s」应当被拒绝", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("周期格式不正确");
        }
    }

    @Test
    @DisplayName("费用段逐字段落到 DTO：入账 / 佣金 / 卖家应得 / 笔数 不串位")
    void feeSectionMapsFieldByField() {
        stubFlow(39700L, 2382L, 3L);
        SettlementBill bill = service.billOf(1001L, "2026-10");

        assertThat(bill.tenantId()).isEqualTo(1001L);
        assertThat(bill.orderAmount()).isEqualTo(39700L);
        assertThat(bill.platformFeeAmount()).isEqualTo(2382L);
        assertThat(bill.sellerEarningAmount()).isEqualTo(39700L - 2382L);
        assertThat(bill.orderCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("列名大小写容错：全大写别名同样认得（换驱动/换库时不至于静默全 0）")
    void columnNameCaseIsTolerated() {
        Map<String, Object> flow = new HashMap<>();
        flow.put("ORDERAMOUNT", 500L);
        flow.put("PLATFORMFEEAMOUNT", 30L);
        when(settlementMapper.billFlowAggregate(any(), any(), any())).thenReturn(flow);

        SettlementBill bill = service.billOf(1001L, "2026-10");
        assertThat(bill.orderAmount()).isEqualTo(500L);
        assertThat(bill.platformFeeAmount()).isEqualTo(30L);
    }

    @Test
    @DisplayName("快照段复用钱包口径：待结算/冻结/可提现 与 欠款 各自落位，且不互相污染")
    void snapshotSectionReusesWalletAggregate() {
        stubFlow(0, 0, 0);
        Map<String, Object> stock = new HashMap<>();
        stock.put("pendingAmount", 1100L);
        stock.put("frozenAmount", 2200L);
        stock.put("withdrawableAmount", 3300L);
        stock.put("releasedAmount", 5500L);
        when(settlementMapper.walletAggregate(eq(1001L), any())).thenReturn(stock);
        when(sellerDebtMapper.sumOutstanding(1001L)).thenReturn(700L);

        SettlementBill bill = service.billOf(1001L, "2026-10");
        assertThat(bill.pendingAmount()).isEqualTo(1100L);
        assertThat(bill.frozenAmount()).isEqualTo(2200L);
        assertThat(bill.withdrawableAmount()).isEqualTo(3300L);
        assertThat(bill.outstandingDebt()).isEqualTo(700L);
    }

    @Test
    @DisplayName("欠款为 null（无记录）时记 0，不抛 NPE —— 查账单不该因为没欠过钱而失败")
    void nullDebtBecomesZero() {
        stubFlow(0, 0, 0);
        when(sellerDebtMapper.sumOutstanding(any())).thenReturn(null);

        SettlementBill bill = service.billOf(1001L, "2026-10");
        assertThat(bill.outstandingDebt()).isZero();
    }

    @Test
    @DisplayName("聚合返回 null（无任何托管单）时账单全 0，仍然是 200 而不是 NPE")
    void emptyAggregateYieldsZeroBill() {
        when(settlementMapper.billFlowAggregate(any(), any(), any())).thenReturn(null);
        when(settlementMapper.walletAggregate(any(), any())).thenReturn(null);

        SettlementBill bill = service.billOf(1001L, "2026-10");
        assertThat(bill.orderAmount()).isZero();
        assertThat(bill.releasedAmount()).isZero();
        assertThat(bill.withdrawableAmount()).isZero();
    }

    @Test
    @DisplayName("快照时刻是「现在」而不是周期末：账单的余额段永远是实时值（口径只有一套）")
    void snapshotAtIsNowNotPeriodEnd() {
        stubFlow(0, 0, 0);
        SettlementBill bill = service.billOf(1001L, "2020-01");

        // 周期早已过去，但快照时刻仍是当前 —— 若被写成周期末，
        // 就说明它走的是一条独立的回溯口径，那正是要避免的第二套口径。
        LocalDateTime snapshot = LocalDateTime.parse(bill.snapshotAt().replace("Z", ""));
        assertThat(snapshot).isAfter(LocalDate.of(2025, 1, 1).atStartOfDay());
        assertThat(snapshot).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    }
}
