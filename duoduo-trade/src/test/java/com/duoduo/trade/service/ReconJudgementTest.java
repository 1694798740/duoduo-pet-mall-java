package com.duoduo.trade.service;

import com.duoduo.trade.service.ReconJudgement.DebtFact;
import com.duoduo.trade.service.ReconJudgement.Diff;
import com.duoduo.trade.service.ReconJudgement.FreezeFact;
import com.duoduo.trade.service.ReconJudgement.OrderFact;
import com.duoduo.trade.service.ReconJudgement.SettlementFact;
import com.duoduo.trade.service.ReconJudgement.SkuStockFact;
import com.duoduo.trade.support.ReconRule;
import com.duoduo.trade.support.ReconScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账判据单测（S3）。
 *
 * <h2>为什么判据要独立测，而且必须"正反两向"</h2>
 * 对账规则有两种失效方式，都很安静：
 * <ul>
 *   <li><b>该报不报</b>：判据写漏了一个方向 —— 报表永远全绿，没人会去查一个全绿的东西；</li>
 *   <li><b>不该报却报</b>：判据把正常数据当异常 —— 报表天天有差异，
 *       查两次发现是判据自己的问题之后，真差异也没人信了。</li>
 * </ul>
 * 所以每条规则都配一对用例：<b>命中</b>（必须报，且报出的规则就是它自己）
 * 与<b>不命中</b>（正常数据必须一声不吭）。
 *
 * <h2>声明-执行守卫</h2>
 * {@link #每条规则都有命中用例与不命中用例()} 把 {@link ReconRule#ALL} 与用例表逐条对齐：
 * 新加一条规则却忘了写用例、或写用例时把规则写错，都会红。
 * 少了这道守卫，"规则越来越多、覆盖越来越少"是必然的 —— 而且不会有任何报错。
 */
class ReconJudgementTest {

    /** 固定"现在"，否则"超期"这类断言会随运行时刻漂移，测试会变成偶发红 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 12, 0);
    private static final int GRACE = 24;
    private static final int STALE = 24;

    // ==================== 声明 vs 用例：一条都不能少 ====================

    @Test
    @DisplayName("每条规则都有命中用例与不命中用例（声明-执行守卫）")
    void 每条规则都有命中用例与不命中用例() {
        List<Case> cases = cases();
        Set<ReconRule> covered = new LinkedHashSet<>();
        cases.forEach(c -> covered.add(c.rule()));

        assertThat(covered)
                .as("声明了 %d 条规则，用例只覆盖 %d 条；新加规则必须同时加正反用例",
                        ReconRule.ALL.size(), covered.size())
                .containsExactlyInAnyOrderElementsOf(ReconRule.ALL);

        for (Case c : cases) {
            Optional<Diff> hit = c.hit().get();
            assertThat(hit).as("%s：命中用例必须报出差异", c.rule()).isPresent();
            assertThat(hit.get().rule())
                    .as("%s：命中用例报出的规则必须是它自己（写错规则 = 覆盖是假的）", c.rule())
                    .isEqualTo(c.rule());
            assertThat(hit.get().refType())
                    .as("%s：ref_type 必须取自规则定义", c.rule())
                    .isEqualTo(c.rule().refType());
            assertThat(c.miss().get())
                    .as("%s：不命中用例不许报差异（假差异会让报表失去信任）", c.rule())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("规则清单：8 条钱 + 5 条库存，且按范围切分不重不漏")
    void 规则清单() {
        assertThat(ReconRule.ALL).hasSize(13);
        assertThat(ReconRule.ofScope(ReconScope.MONEY)).hasSize(8);
        assertThat(ReconRule.ofScope(ReconScope.STOCK)).hasSize(5);
        // 每条都必须有严重度且不能为空 —— severity 会被写进库、被前端用来排序
        ReconRule.ALL.forEach(r -> assertThat(r.severity()).isNotNull());
    }

    // ==================== 钱 · 相容表逐格 ====================

    @Test
    @DisplayName("钱 · 订单状态与托管状态的相容表：合法组合一律不报，非法组合一律报")
    void 钱_状态相容表() {
        // 合法：待付款/已关闭 不该有托管（该场景由 missingSettlement 的另一侧覆盖，这里不判）
        assertThat(ReconJudgement.statusMismatch(order("PAID", 1000), settle("CUSTODY", 1000))).isEmpty();
        assertThat(ReconJudgement.statusMismatch(order("SHIPPED", 1000), settle("CUSTODY", 1000))).isEmpty();
        assertThat(ReconJudgement.statusMismatch(order("COMPLETED", 1000), settle("RELEASED", 1000))).isEmpty();
        assertThat(ReconJudgement.statusMismatch(order("REFUNDED", 1000), settle("REFUNDED", 1000))).isEmpty();
        assertThat(ReconJudgement.statusMismatch(order("REFUNDED", 1000), settle("ADVANCED", 1000))).isEmpty();

        // 非法：还没确认收货就放款
        assertThat(ReconJudgement.statusMismatch(order("PAID", 1000), settle("RELEASED", 1000))).isPresent();
        // 非法：钱还托管着，订单却已关闭
        assertThat(ReconJudgement.statusMismatch(order("CLOSED", 1000), settle("CUSTODY", 1000))).isPresent();
        // 非法：订单已退款，托管却是"已放款"（那笔退款只可能是垫付，应落 ADVANCED）
        assertThat(ReconJudgement.statusMismatch(order("REFUNDED", 1000), settle("RELEASED", 1000))).isPresent();
        // 非法：钱都没收过，哪来的托管
        assertThat(ReconJudgement.statusMismatch(order("PENDING_PAYMENT", 1000), settle("CUSTODY", 1000))).isPresent();
    }

    @Test
    @DisplayName("钱 · 未知订单状态一律不判（宁可漏报，也不产假差异）")
    void 钱_未知状态不判() {
        assertThat(ReconJudgement.statusMismatch(order("SOMETHING_NEW", 1000), settle("CUSTODY", 1000))).isEmpty();
        assertThat(ReconJudgement.statusMismatch(order("PAID", 1000), settle("WEIRD", 1000)))
                .as("托管状态未知但订单状态已知 → 它不在相容集合里，应当报")
                .isPresent();
    }

    @Test
    @DisplayName("钱 · 「已付款但无托管」不对 REFUNDED 判：单看状态分不出有没有付过款")
    void 钱_缺失托管不判REFUNDED() {
        assertThat(ReconJudgement.requiresCustody("PAID")).isTrue();
        assertThat(ReconJudgement.requiresCustody("SHIPPED")).isTrue();
        assertThat(ReconJudgement.requiresCustody("COMPLETED")).isTrue();
        assertThat(ReconJudgement.requiresCustody("REFUNDED"))
                .as("已退款可能来自「待付款→关闭」，硬判会制造假差异")
                .isFalse();
        assertThat(ReconJudgement.requiresCustody("CLOSED")).isFalse();
        assertThat(ReconJudgement.missingSettlement(order("REFUNDED", 1000), false)).isEmpty();
    }

    @Test
    @DisplayName("钱 · 放款时间：两个方向都判（该有没有、不该有却有）")
    void 钱_放款时间双向() {
        assertThat(ReconJudgement.releaseTimeMissing(settle("RELEASED", 1000, 0, false))).isPresent();
        assertThat(ReconJudgement.releaseTimeMissing(settle("ADVANCED", 1000, 0, false))).isPresent();
        assertThat(ReconJudgement.releaseTimeMissing(settle("CUSTODY", 1000, 0, true)))
                .as("托管中却已经有 released_at —— 有一列被写错了")
                .isPresent();
        assertThat(ReconJudgement.releaseTimeMissing(settle("RELEASED", 1000, 0, true))).isEmpty();
        assertThat(ReconJudgement.releaseTimeMissing(settle("CUSTODY", 1000, 0, false))).isEmpty();
    }

    @Test
    @DisplayName("钱 · 已完成超期未放款：宽限期内不报（确认收货与放款之间本来就有延迟）")
    void 钱_超期未放款留宽限() {
        OrderFact justDone = new OrderFact(1L, "COMPLETED", 0L, 1000, NOW.minusHours(1));
        assertThat(ReconJudgement.completedNotReleased(justDone, settle("CUSTODY", 1000), NOW, GRACE))
                .as("刚完成 1 小时，宽限 24 小时，不报")
                .isEmpty();

        OrderFact longDone = new OrderFact(1L, "COMPLETED", 0L, 1000, NOW.minusHours(48));
        assertThat(ReconJudgement.completedNotReleased(longDone, settle("CUSTODY", 1000), NOW, GRACE))
                .isPresent();
        assertThat(ReconJudgement.completedNotReleased(longDone, settle("RELEASED", 1000), NOW, GRACE))
                .as("已放款当然不算卡住")
                .isEmpty();
    }

    // ==================== 钱 · 佣金：与政策无关的不变量 ====================

    @Test
    @DisplayName("★ 钱 · 费率变过 ≠ 有差异：1% 时代的旧单不该被判成错")
    void 钱_费率历史不产生假差异() {
        // amount=12900、fee=129 = 1%，而今天的费率是 6%（应为 774）。
        // 这条单结在费率还是 1% 的年代 —— 它是**历史事实**，不是差异。
        // 第一版判据拿"今天的费率"去比，在本机库上一跑就是 92 + 375 条假差异。
        SettlementFact historical = settle("RELEASED", 12_900, 129, true);
        assertThat(ReconJudgement.feeIllegal(historical))
                .as("佣金是政策的时间函数；与政策挂钩的比对属于回归测试，不属于每日对账")
                .isEmpty();

        // 自营在 E22（自营免佣）之前也是收佣的，同样是历史事实
        SettlementFact oldSelfFee = settle("RELEASED", 12_900, 129, true, 0L);
        assertThat(ReconJudgement.feeIllegal(oldSelfFee)).isEmpty();
    }

    @Test
    @DisplayName("钱 · 佣金只判三条与政策无关的不变量：负佣 / 超本金 / 负本金")
    void 钱_佣金结构性非法() {
        assertThat(ReconJudgement.feeIllegal(settle("CUSTODY", 1000, -1, false))).isPresent();
        assertThat(ReconJudgement.feeIllegal(settle("CUSTODY", 1000, 1001, false)))
                .as("佣金大于本金：放款后卖家倒欠平台，而账上一切正常")
                .isPresent();
        assertThat(ReconJudgement.feeIllegal(settle("CUSTODY", -1, 0, false))).isPresent();
        assertThat(ReconJudgement.feeIllegal(settle("CUSTODY", 1000, 60, false))).isEmpty();
        assertThat(ReconJudgement.feeIllegal(settle("CUSTODY", 60, 0, false)))
                .as("金额小到佣金取整为 0 是正常的")
                .isEmpty();
    }

    @Test
    @DisplayName("钱 · 托管金额必须等于订单实付（跨表）")
    void 钱_金额跨表一致() {
        assertThat(ReconJudgement.amountMismatch(order("PAID", 12900), settle("CUSTODY", 12900))).isEmpty();
        Optional<Diff> diff = ReconJudgement.amountMismatch(order("PAID", 12900), settle("CUSTODY", 12000));
        assertThat(diff).isPresent();
        assertThat(diff.get().expected()).isEqualTo("12900");
        assertThat(diff.get().actual()).isEqualTo("12000");
    }

    @Test
    @DisplayName("钱 · 欠款：非正金额与未知状态都判")
    void 钱_欠款非法() {
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, 0, "OUTSTANDING"))).isPresent();
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, -100, "OUTSTANDING"))).isPresent();
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, 100, "WHATEVER"))).isPresent();
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, 100, "OUTSTANDING"))).isEmpty();
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, 100, "SETTLED"))).isEmpty();
        assertThat(ReconJudgement.debtInvalid(new DebtFact(1L, 100, "WRITTEN_OFF"))).isEmpty();
    }

    // ==================== 库存 ====================

    @Test
    @DisplayName("库存 · 冻结与订单状态不相容：两个方向都判")
    void 库存_冻结与订单不相容双向() {
        // 正常：待付款 ↔ 冻结中
        assertThat(ReconJudgement.freezeLeak(freeze("FROZEN", 2), "PENDING_PAYMENT")).isEmpty();
        // 正常：已付款之后冻结必须已扣减 / 已释放
        assertThat(ReconJudgement.freezeLeak(freeze("DEDUCTED", 2), "PAID")).isEmpty();
        assertThat(ReconJudgement.freezeLeak(freeze("RELEASED", 2), "CLOSED")).isEmpty();
        // 异常：订单已终结，冻结还挂着 → 库存被永久占住
        assertThat(ReconJudgement.freezeLeak(freeze("FROZEN", 2), "COMPLETED")).isPresent();
        assertThat(ReconJudgement.freezeLeak(freeze("FROZEN", 2), "CLOSED")).isPresent();
        // 异常：订单还没付款，冻冷却已扣减 → 货出去了、钱没收到
        assertThat(ReconJudgement.freezeLeak(freeze("DEDUCTED", 2), "PENDING_PAYMENT")).isPresent();
        // 异常：订单不存在 —— 那笔冻结永远释放不掉，不能当成"未知"放过
        Optional<Diff> orphan = ReconJudgement.freezeLeak(freeze("FROZEN", 2), null);
        assertThat(orphan).isPresent();
        assertThat(orphan.get().expected()).isEqualTo("存在对应订单");
        // 未知订单状态：不判
        assertThat(ReconJudgement.freezeLeak(freeze("FROZEN", 2), "NEW_STATUS")).isEmpty();
    }

    @Test
    @DisplayName("库存 · 过期冻结：刚过期不报（补偿下一轮会处理），超一整轮才报")
    void 库存_过期冻结留余量() {
        assertThat(ReconJudgement.freezeStale(freeze("FROZEN", 2, NOW.minusHours(1)), NOW, STALE)).isEmpty();
        assertThat(ReconJudgement.freezeStale(freeze("FROZEN", 2, NOW.minusHours(48)), NOW, STALE)).isPresent();
        assertThat(ReconJudgement.freezeStale(freeze("DEDUCTED", 2, NOW.minusHours(48)), NOW, STALE))
                .as("已扣减的不是僵尸冻结")
                .isEmpty();
    }

    @Test
    @DisplayName("库存 · 冻结数量与明细数量不符（含「明细里根本没这件商品」）")
    void 库存_冻结数量对不上() {
        assertThat(ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), 3)).isEmpty();
        assertThat(ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), 1)).isPresent();
        assertThat(ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), 5)).isPresent();
        Optional<Diff> noItem = ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), null);
        assertThat(noItem).isPresent();
        assertThat(noItem.get().expected()).isEqualTo("订单明细里有该 SKU");
    }

    @Test
    @DisplayName("★ 库存 · 跨域：商品域查不到该 SKU 必须报，绝不当成 0 来比")
    void 库存_跨域商品域缺该SKU() {
        assertThat(ReconJudgement.crossDomainDrift(1L, 5L, new SkuStockFact(1L, 100, 5))).isEmpty();
        assertThat(ReconJudgement.crossDomainDrift(1L, 5L, new SkuStockFact(1L, 100, 0))).isPresent();
        assertThat(ReconJudgement.crossDomainDrift(1L, 0L, new SkuStockFact(1L, 100, 7)))
                .as("反方向：商品域记着有冻结、交易域一条都没有")
                .isPresent();
        Optional<Diff> missing = ReconJudgement.crossDomainDrift(1L, 0L, null);
        assertThat(missing)
                .as("当成 0 比会在「两边都是 0」时静默通过，而事实是冻结挂在了不存在的商品上")
                .isPresent();
        assertThat(missing.get().actual()).isEqualTo("商品域无此 SKU");
    }

    @Test
    @DisplayName("库存 · 原始两列异常：负库存 / 冻结为负 / 冻结大于总库存")
    void 库存_原始库存异常() {
        assertThat(ReconJudgement.stockNegative(new SkuStockFact(1L, -1, 0))).isPresent();
        assertThat(ReconJudgement.stockNegative(new SkuStockFact(1L, 10, -1))).isPresent();
        assertThat(ReconJudgement.stockNegative(new SkuStockFact(1L, 1, 2)))
                .as("对外 availableStock = max(0, stock-frozen) 会把它夹成 0，前台只看到「已售罄」")
                .isPresent();
        assertThat(ReconJudgement.stockNegative(new SkuStockFact(1L, 10, 10))).isEmpty();
        assertThat(ReconJudgement.stockNegative(new SkuStockFact(1L, 0, 0))).isEmpty();
    }

    // ==================== 夹具与用例表 ====================

    private record Case(ReconRule rule,
                        Supplier<Optional<Diff>> hit,
                        Supplier<Optional<Diff>> miss) {
    }

    private List<Case> cases() {
        List<Case> list = new ArrayList<>();

        list.add(new Case(ReconRule.MONEY_MISSING_SETTLEMENT,
                () -> ReconJudgement.missingSettlement(order("PAID", 1000), false),
                () -> ReconJudgement.missingSettlement(order("PAID", 1000), true)));

        list.add(new Case(ReconRule.MONEY_ORPHAN_SETTLEMENT,
                () -> ReconJudgement.orphanSettlement(settle("CUSTODY", 1000), false),
                () -> ReconJudgement.orphanSettlement(settle("CUSTODY", 1000), true)));

        list.add(new Case(ReconRule.MONEY_STATUS_MISMATCH,
                () -> ReconJudgement.statusMismatch(order("CLOSED", 1000), settle("CUSTODY", 1000)),
                () -> ReconJudgement.statusMismatch(order("PAID", 1000), settle("CUSTODY", 1000))));

        list.add(new Case(ReconRule.MONEY_COMPLETED_NOT_RELEASED,
                () -> ReconJudgement.completedNotReleased(
                        new OrderFact(1L, "COMPLETED", 0L, 1000, NOW.minusHours(48)),
                        settle("CUSTODY", 1000), NOW, GRACE),
                () -> ReconJudgement.completedNotReleased(
                        new OrderFact(1L, "COMPLETED", 0L, 1000, NOW.minusHours(48)),
                        settle("RELEASED", 1000), NOW, GRACE)));

        list.add(new Case(ReconRule.MONEY_RELEASE_TIME_MISSING,
                () -> ReconJudgement.releaseTimeMissing(settle("RELEASED", 1000, 0, false)),
                () -> ReconJudgement.releaseTimeMissing(settle("RELEASED", 1000, 0, true))));

        list.add(new Case(ReconRule.MONEY_AMOUNT_MISMATCH,
                () -> ReconJudgement.amountMismatch(order("PAID", 12900), settle("CUSTODY", 12000)),
                () -> ReconJudgement.amountMismatch(order("PAID", 12900), settle("CUSTODY", 12900))));

        list.add(new Case(ReconRule.MONEY_FEE_ILLEGAL,
                () -> ReconJudgement.feeIllegal(settle("CUSTODY", 1000, 1200, false)),
                () -> ReconJudgement.feeIllegal(settle("CUSTODY", 1000, 60, false))));

        list.add(new Case(ReconRule.MONEY_DEBT_INVALID,
                () -> ReconJudgement.debtInvalid(new DebtFact(1L, 0, "OUTSTANDING")),
                () -> ReconJudgement.debtInvalid(new DebtFact(1L, 100, "OUTSTANDING"))));

        list.add(new Case(ReconRule.STOCK_FREEZE_LEAK,
                () -> ReconJudgement.freezeLeak(freeze("FROZEN", 2), "COMPLETED"),
                () -> ReconJudgement.freezeLeak(freeze("FROZEN", 2), "PENDING_PAYMENT")));

        list.add(new Case(ReconRule.STOCK_FREEZE_STALE,
                () -> ReconJudgement.freezeStale(freeze("FROZEN", 2, NOW.minusHours(48)), NOW, STALE),
                () -> ReconJudgement.freezeStale(freeze("FROZEN", 2, NOW.minusHours(1)), NOW, STALE)));

        list.add(new Case(ReconRule.STOCK_FREEZE_QTY_MISMATCH,
                () -> ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), 1),
                () -> ReconJudgement.freezeQtyMismatch(freeze("FROZEN", 3), 3)));

        list.add(new Case(ReconRule.STOCK_CROSS_DOMAIN_DRIFT,
                () -> ReconJudgement.crossDomainDrift(1L, 5L, new SkuStockFact(1L, 100, 0)),
                () -> ReconJudgement.crossDomainDrift(1L, 5L, new SkuStockFact(1L, 100, 5))));

        list.add(new Case(ReconRule.STOCK_NEGATIVE,
                () -> ReconJudgement.stockNegative(new SkuStockFact(1L, 1, 2)),
                () -> ReconJudgement.stockNegative(new SkuStockFact(1L, 10, 2))));

        return list;
    }

    private static OrderFact order(String status, long payAmount) {
        return new OrderFact(1L, status, 0L, payAmount, null);
    }

    private static SettlementFact settle(String status, long amount) {
        return settle(status, amount, 0, false);
    }

    private static SettlementFact settle(String status, long amount, long fee, boolean released) {
        return settle(status, amount, fee, released, 0L);
    }

    private static SettlementFact settle(String status, long amount, long fee, boolean released, long sellerId) {
        return new SettlementFact(1L, sellerId, status, amount, fee, released);
    }

    private static FreezeFact freeze(String status, int quantity) {
        return freeze(status, quantity, NOW.minusHours(1));
    }

    private static FreezeFact freeze(String status, int quantity, LocalDateTime expireAt) {
        return new FreezeFact(2001L, 1L, quantity, status, expireAt);
    }
}
