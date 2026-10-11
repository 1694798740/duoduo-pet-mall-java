package com.duoduo.trade.service;

import com.duoduo.trade.support.ReconRule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 对账判据（S3）—— <b>纯函数</b>，不碰数据库、不碰 Spring、不看时钟。
 *
 * <h2>为什么把判据与取数分开</h2>
 * 对账代码最难测的部分不是 SQL，而是「什么算对不上」。把判据收进一个纯函数类之后：
 * <ul>
 *   <li>12 条规则每条都能喂手写数据做「命中 / 不命中」正反两向断言，不需要起库；</li>
 *   <li>取数层怎么写（换表、加分区、改索引）都不会改变判据，回归面收敛；</li>
 *   <li>时间作为入参传入（{@code now}）而不是 {@code LocalDateTime.now()} ——
 *       否则「超期」这类断言会随运行时刻漂移，测试会变成偶发红。</li>
 * </ul>
 *
 * <h2>判据的一贯取向：不确定时**不报**</h2>
 * 遇到无法判断的情形（订单状态是未知取值、时间戳缺失）一律返回空，
 * 而不是猜一个结论报出来。<b>假差异比漏报更贵</b>：一次假差异要人工查一轮，
 * 查完发现是判据自己的问题，下次真差异就没人信了。
 * 因此这里每条规则的「不报」分支都是显式写的，不是"忘了判"。
 */
public final class ReconJudgement {

    private ReconJudgement() {
    }

    // ==================== 事实（fact）：判据唯一认识的数据形态 ====================

    /** 订单事实。{@code completedAt} 取「确认收货」那条状态流转的时间，缺失表示还没完成。 */
    public record OrderFact(long orderId,
                            String status,
                            long sellerId,
                            long payAmount,
                            LocalDateTime completedAt) {
    }

    /**
     * 资金托管单事实。
     *
     * <p>{@code sellerId} 不参与任何比对，但它是托管单身份的一部分 ——
     * 差异落到明细表后，排查的第一步就是「这是谁的账」，
     * 而那时手上只有 {@code orderId}，再去 join 一次订单只是为了拿到卖家。
     */
    public record SettlementFact(long orderId,
                                 long sellerId,
                                 String status,
                                 long amount,
                                 long platformFee,
                                 boolean releasedAtPresent) {
    }

    /** 卖家欠款事实。 */
    public record DebtFact(long orderId, long amount, String status) {
    }

    /** 库存冻结台账事实（一个 (skuId, orderId) 一条）。 */
    public record FreezeFact(long skuId, long orderId, int quantity, String status, LocalDateTime expireAt) {
    }

    /** 商品域某个 SKU 的原始库存两列。 */
    public record SkuStockFact(long skuId, long stock, long frozenStock) {
    }

    /** 一条差异。{@code rule} 决定严重度与主体类型，调用方不再各自传一遍（传两遍就会不一致）。 */
    public record Diff(ReconRule rule, long refId, String expected, String actual, String detail) {
        public String refType() {
            return rule.refType();
        }

        public String severity() {
            return rule.severity().name();
        }
    }

    // ==================== 常量：从既有枚举抄过来的字面量，不 import 是为了不被重构带走 ====================

    private static final String ORDER_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String ORDER_PAID = "PAID";
    private static final String ORDER_SHIPPED = "SHIPPED";
    private static final String ORDER_COMPLETED = "COMPLETED";
    private static final String ORDER_REFUNDED = "REFUNDED";
    private static final String ORDER_CLOSED = "CLOSED";

    private static final String SETTLE_CUSTODY = "CUSTODY";
    private static final String SETTLE_RELEASED = "RELEASED";
    private static final String SETTLE_REFUNDED = "REFUNDED";
    private static final String SETTLE_ADVANCED = "ADVANCED";

    private static final String FREEZE_FROZEN = "FROZEN";
    private static final String FREEZE_RELEASED = "RELEASED";
    private static final String FREEZE_DEDUCTED = "DEDUCTED";
    private static final String FREEZE_RESTOCK_PENDING = "RESTOCK_PENDING";
    private static final String FREEZE_RESTOCKED = "RESTOCKED";

    private static final String DEBT_OUTSTANDING = "OUTSTANDING";
    private static final String DEBT_SETTLED = "SETTLED";
    private static final String DEBT_WRITTEN_OFF = "WRITTEN_OFF";

    // ==================== 钱 ====================

    /**
     * 这个订单状态「应当」有资金托管单吗？
     *
     * <p>{@code REFUNDED} 刻意**不在**此列：从「待付款 → 已关闭」再退款不成立，
     * 但单看 {@code REFUNDED} 分不出订单到底有没有付过款（已发货退款与未付款关闭
     * 都会收在同一个终态里）。硬判会制造假差异，所以交给
     * {@link #statusMismatch} 在"托管单存在"的前提下反向判。
     */
    public static boolean requiresCustody(String orderStatus) {
        return ORDER_PAID.equals(orderStatus)
                || ORDER_SHIPPED.equals(orderStatus)
                || ORDER_COMPLETED.equals(orderStatus);
    }

    /** 订单已付款（或更靠后），必须在平台账上看到这笔托管。 */
    public static Optional<Diff> missingSettlement(OrderFact order, boolean settlementExists) {
        if (order == null || !requiresCustody(order.status()) || settlementExists) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_MISSING_SETTLEMENT, order.orderId(),
                "存在托管单（金额 " + order.payAmount() + "）", "无托管单",
                "订单状态 " + order.status() + "，买家已付款，但 t_settlement 里没有这笔托管；"
                        + "既放不了款也退不了款"));
    }

    /** 托管单指向的订单必须存在。 */
    public static Optional<Diff> orphanSettlement(SettlementFact settlement, boolean orderExists) {
        if (settlement == null || orderExists) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_ORPHAN_SETTLEMENT, settlement.orderId(),
                "存在对应订单", "无对应订单",
                "托管单挂在订单 " + settlement.orderId() + " 上，但 t_order 里查不到该订单，"
                        + "这笔钱会被算进钱包汇总"));
    }

    /**
     * 订单状态与托管状态必须相容。
     *
     * <p>相容表是**写死的一张表**而不是一串 if：
     * 订单状态每加一个取值，这里必须显式表个态，否则默认落到"未知状态不判"、
     * 静默失去覆盖 —— 而静默失去覆盖正是这套规则最容易被自己骗过去的地方。
     */
    public static Optional<Diff> statusMismatch(OrderFact order, SettlementFact settlement) {
        if (order == null || settlement == null) {
            return Optional.empty();
        }
        Set<String> allowed = switch (order.status()) {
            case ORDER_PENDING_PAYMENT, ORDER_CLOSED -> Set.of();
            case ORDER_PAID, ORDER_SHIPPED -> Set.of(SETTLE_CUSTODY);
            case ORDER_COMPLETED -> Set.of(SETTLE_RELEASED);
            case ORDER_REFUNDED -> Set.of(SETTLE_REFUNDED, SETTLE_ADVANCED);
            default -> null;
        };
        if (allowed == null) {
            // 未知订单状态：不判（判了就是假差异）
            return Optional.empty();
        }
        if (allowed.contains(settlement.status())) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_STATUS_MISMATCH, order.orderId(),
                allowed.isEmpty() ? "不应存在托管单" : "托管状态属于 " + allowed,
                "托管状态 " + settlement.status(),
                "订单状态 " + order.status() + " 与托管状态 " + settlement.status()
                        + " 不可能同时成立"));
    }

    /**
     * 订单已完成、超过宽限期仍是"托管中" → 钱卡住了。
     *
     * <p>宽限期不是装饰：确认收货与放款之间本来就有一小段延迟（事件、重试）。
     * 不留宽限，这条规则会在每次自动确认收货的当天都报一遍假差异。
     */
    public static Optional<Diff> completedNotReleased(OrderFact order, SettlementFact settlement,
                                                      LocalDateTime now, int graceHours) {
        if (order == null || settlement == null) {
            return Optional.empty();
        }
        if (!ORDER_COMPLETED.equals(order.status()) || order.completedAt() == null) {
            return Optional.empty();
        }
        if (SETTLE_RELEASED.equals(settlement.status()) || SETTLE_ADVANCED.equals(settlement.status())) {
            return Optional.empty();
        }
        LocalDateTime deadline = order.completedAt().plusHours(Math.max(1, graceHours));
        if (now.isBefore(deadline)) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_COMPLETED_NOT_RELEASED, order.orderId(),
                SETTLE_RELEASED, settlement.status(),
                "订单于 " + order.completedAt() + " 完成，宽限期 " + graceHours
                        + " 小时已过，托管仍是 " + settlement.status() + "：卖家拿不到钱"));
    }

    /** 放款状态必须有放款时间；托管中不该有放款时间（两者都是"这一列被人写错"的痕迹）。 */
    public static Optional<Diff> releaseTimeMissing(SettlementFact settlement) {
        if (settlement == null) {
            return Optional.empty();
        }
        boolean shouldHaveTime = SETTLE_RELEASED.equals(settlement.status())
                || SETTLE_ADVANCED.equals(settlement.status());
        if (shouldHaveTime == settlement.releasedAtPresent()) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_RELEASE_TIME_MISSING, settlement.orderId(),
                shouldHaveTime ? "有放款时间" : "无放款时间",
                settlement.releasedAtPresent() ? "有放款时间" : "无放款时间",
                shouldHaveTime
                        ? "托管状态 " + settlement.status() + " 却没有 released_at，"
                        + "出事时回答不了「这笔钱哪天出去的」"
                        : "托管状态 " + settlement.status() + " 却已经有 released_at，"
                        + "说明有一列被写错了"));
    }

    /**
     * 托管金额必须等于订单实付金额。
     *
     * <p><b>跨表</b>：托管金额是下单时从 {@code order.payAmount} 抄过去的快照，
     * 之后两条时间线各自往前走。分叉的后果不是"数字不好看"，而是
     * 「退给买家的钱」与「账上本该有的钱」不是同一个数 —— 而两边都能自证。
     */
    public static Optional<Diff> amountMismatch(OrderFact order, SettlementFact settlement) {
        if (order == null || settlement == null) {
            return Optional.empty();
        }
        if (settlement.amount() == order.payAmount()) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.MONEY_AMOUNT_MISMATCH, order.orderId(),
                String.valueOf(order.payAmount()), String.valueOf(settlement.amount()),
                "订单 " + order.orderId() + " 实付 " + order.payAmount()
                        + "，托管单记的是 " + settlement.amount()));
    }

    /**
     * 佣金结构不合法：为负、大于本金、或托管金额为负。
     *
     * <p><b>为什么不比费率</b>（本方法最初不是这样写的）：
     * 第一版的判据是「佣金是否等于按当前费率算出来的数」。在本机库上一跑，
     * 报出 <b>92 条</b>费率不符 + <b>375 条</b>自营不该收佣的差异 —— <b>全部是假的</b>。
     * 那些单结在费率还是 1% 的年代、或结在自营免佣（E22）生效之前。
     *
     * <p>项目里其实早就写下过这个坑（{@code SettlementEntity} 的字段注释：
     * 「费率会随运营策略调整，而历史单据必须保留**结算当刻**的口径，
     * 否则对账时会出现『按今天的费率算昨天的单，怎么都对不平』」）——
     * 只是那句话写在另一个文件里，没能拦住这条新规则。
     *
     * <p><b>由此定下的边界</b>：佣金是「政策的时间函数」。
     * 与政策挂钩的比对属于**回归测试**（改费率时按新费率断言，settlement-smoke 已覆盖），
     * 不属于每日对账 —— 每日对账要能连续跑几年而不产生一条噪声。
     * 这里只留三条与政策无关的不变量：任何一条被违反，
     * 都不可能是"那会儿政策不一样"，只可能是算错了。
     */
    public static Optional<Diff> feeIllegal(SettlementFact settlement) {
        if (settlement == null) {
            return Optional.empty();
        }
        long amount = settlement.amount();
        long fee = settlement.platformFee();
        if (amount < 0) {
            return Optional.of(new Diff(ReconRule.MONEY_FEE_ILLEGAL, settlement.orderId(),
                    ">= 0", String.valueOf(amount), "托管金额为负"));
        }
        if (fee < 0) {
            return Optional.of(new Diff(ReconRule.MONEY_FEE_ILLEGAL, settlement.orderId(),
                    ">= 0", String.valueOf(fee), "佣金为负：等于平台倒贴给卖家"));
        }
        if (fee > amount) {
            return Optional.of(new Diff(ReconRule.MONEY_FEE_ILLEGAL, settlement.orderId(),
                    "<= " + amount, String.valueOf(fee),
                    "佣金大于托管本金：放款后卖家会倒欠平台，而账上看起来一切正常"));
        }
        return Optional.empty();
    }

    /** 卖家欠款：金额必须为正、状态必须是三个已知取值之一。 */
    public static Optional<Diff> debtInvalid(DebtFact debt) {
        if (debt == null) {
            return Optional.empty();
        }
        if (debt.amount() <= 0) {
            return Optional.of(new Diff(ReconRule.MONEY_DEBT_INVALID, debt.orderId(),
                    "> 0", String.valueOf(debt.amount()),
                    "欠款金额非正：负数会被读成「平台欠卖家」"));
        }
        if (!Set.of(DEBT_OUTSTANDING, DEBT_SETTLED, DEBT_WRITTEN_OFF).contains(debt.status())) {
            return Optional.of(new Diff(ReconRule.MONEY_DEBT_INVALID, debt.orderId(),
                    "OUTSTANDING / SETTLED / WRITTEN_OFF", debt.status(),
                    "欠款状态是未知取值，它在结算与抵扣里的处理方式未定义"));
        }
        return Optional.empty();
    }

    // ==================== 库存 ====================

    /**
     * 冻结台账与订单状态必须相容。
     *
     * <p>唯一合法组合：订单待付款 ↔ 冻结中。其余所有组合都是错的，两个方向都有害：
     * <ul>
     *   <li>订单已离开待付款，冻结还挂着 → <b>库存被永久占住</b>，货卖不出去也不报错；</li>
     *   <li>订单还在待付款，冻结已扣减 → <b>货已出库、钱还没收</b>。</li>
     * </ul>
     * 订单不存在也归到这条：那笔冻结永远释放不掉。
     */
    public static Optional<Diff> freezeLeak(FreezeFact freeze, String orderStatus) {
        if (freeze == null) {
            return Optional.empty();
        }
        if (orderStatus == null) {
            return Optional.of(new Diff(ReconRule.STOCK_FREEZE_LEAK, freeze.orderId(),
                    "存在对应订单", "无对应订单",
                    "SKU " + freeze.skuId() + " 上有一条 " + freeze.status()
                            + " 冻结，但订单不存在：这 " + freeze.quantity() + " 件永远释放不掉"));
        }
        Set<String> allowed;
        if (ORDER_PENDING_PAYMENT.equals(orderStatus)) {
            allowed = Set.of(FREEZE_FROZEN);
        } else if (ORDER_PAID.equals(orderStatus) || ORDER_SHIPPED.equals(orderStatus)
                || ORDER_COMPLETED.equals(orderStatus) || ORDER_REFUNDED.equals(orderStatus)
                || ORDER_CLOSED.equals(orderStatus)) {
            allowed = Set.of(FREEZE_RELEASED, FREEZE_DEDUCTED, FREEZE_RESTOCK_PENDING, FREEZE_RESTOCKED);
        } else {
            return Optional.empty();   // 未知订单状态：不判
        }
        if (allowed.contains(freeze.status())) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.STOCK_FREEZE_LEAK, freeze.orderId(),
                "冻结状态属于 " + allowed, freeze.status(),
                "订单状态 " + orderStatus + "、SKU " + freeze.skuId()
                        + " 的冻结却是 " + freeze.status() + "（" + freeze.quantity() + " 件）"));
    }

    /**
     * 冻结已过期超过一整轮补偿周期仍未释放 → 补偿调度很可能已经停摆。
     *
     * <p>这条规则是<b>调度是否活着</b>的探针。调度停摆不发任何告警，
     * 唯一的表现就是僵尸冻结越积越多，而它对库存的侵蚀是缓慢且不与任何事件关联的。
     */
    public static Optional<Diff> freezeStale(FreezeFact freeze, LocalDateTime now, int staleHours) {
        if (freeze == null || !FREEZE_FROZEN.equals(freeze.status()) || freeze.expireAt() == null) {
            return Optional.empty();
        }
        LocalDateTime deadline = freeze.expireAt().plusHours(Math.max(1, staleHours));
        if (!now.isAfter(deadline)) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.STOCK_FREEZE_STALE, freeze.orderId(),
                "已释放或不存在此条", FREEZE_FROZEN + "（过期于 " + freeze.expireAt() + "）",
                "订单 " + freeze.orderId() + " 的 SKU " + freeze.skuId() + " 冻结已于 "
                        + freeze.expireAt() + " 过期，超过 " + staleHours
                        + " 小时仍未释放：补偿调度疑似停摆"));
    }

    /** 冻结数量必须等于订单明细里该 SKU 的数量。明细缺失同样是差异（冻结挂在了没有的商品上）。 */
    public static Optional<Diff> freezeQtyMismatch(FreezeFact freeze, Integer itemQuantity) {
        if (freeze == null) {
            return Optional.empty();
        }
        if (itemQuantity == null) {
            return Optional.of(new Diff(ReconRule.STOCK_FREEZE_QTY_MISMATCH, freeze.orderId(),
                    "订单明细里有该 SKU", "无对应明细",
                    "订单 " + freeze.orderId() + " 冻结了 SKU " + freeze.skuId()
                            + " 共 " + freeze.quantity() + " 件，但明细里没有这件商品"));
        }
        if (freeze.quantity() == itemQuantity) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.STOCK_FREEZE_QTY_MISMATCH, freeze.orderId(),
                String.valueOf(itemQuantity), String.valueOf(freeze.quantity()),
                "订单 " + freeze.orderId() + " 的 SKU " + freeze.skuId()
                        + " 明细 " + itemQuantity + " 件、冻结 " + freeze.quantity()
                        + " 件：冻少了等于货被多卖，冻多了等于库存被虚占"));
    }

    /**
     * 交易域某 SKU 的「冻结中」之和，应等于商品域该 SKU 的 {@code frozen_stock}。
     *
     * <p>{@code sku == null} 表示商品域里根本没有这个 SKU —— 同样是差异，
     * 不能当成 0 来比（当成 0 时若交易域也是 0 就会静默通过，
     * 而那意味着"冻结挂在了一个不存在的商品上"这件事被对账放过了）。
     */
    public static Optional<Diff> crossDomainDrift(long skuId, long frozenInTrade, SkuStockFact sku) {
        if (sku == null) {
            return Optional.of(new Diff(ReconRule.STOCK_CROSS_DOMAIN_DRIFT, skuId,
                    "商品域存在该 SKU", "商品域无此 SKU",
                    "交易域有 " + frozenInTrade + " 件冻结挂在 SKU " + skuId
                            + " 上，商品域查不到这个 SKU"));
        }
        if (sku.frozenStock() == frozenInTrade) {
            return Optional.empty();
        }
        return Optional.of(new Diff(ReconRule.STOCK_CROSS_DOMAIN_DRIFT, skuId,
                String.valueOf(frozenInTrade), String.valueOf(sku.frozenStock()),
                "交易域冻结台账合计 " + frozenInTrade + " 件，商品域 frozen_stock 是 "
                        + sku.frozenStock() + " 件：某次冻结/释放只在一侧生效了"));
    }

    /**
     * 商品域原始库存不允许为负，也不允许冻结量超过总库存。
     *
     * <p>对外下发的可售库存是 {@code max(0, stock - frozenStock)} —— 那个 {@code max}
     * 恰好把 {@code frozen > stock} 夹成了 0，前台只会显示"已售罄"。
     * 异常被展示层兜底吞掉，所以必须在这里看原始两列。
     */
    public static Optional<Diff> stockNegative(SkuStockFact sku) {
        if (sku == null) {
            return Optional.empty();
        }
        if (sku.stock() < 0) {
            return Optional.of(new Diff(ReconRule.STOCK_NEGATIVE, sku.skuId(),
                    ">= 0", String.valueOf(sku.stock()), "总库存为负"));
        }
        if (sku.frozenStock() < 0) {
            return Optional.of(new Diff(ReconRule.STOCK_NEGATIVE, sku.skuId(),
                    ">= 0", String.valueOf(sku.frozenStock()), "冻结库存为负"));
        }
        if (sku.frozenStock() > sku.stock()) {
            return Optional.of(new Diff(ReconRule.STOCK_NEGATIVE, sku.skuId(),
                    "<= stock(" + sku.stock() + ")", String.valueOf(sku.frozenStock()),
                    "冻结量大于总库存：对外会被 max(0, stock-frozen) 夹成 0，"
                            + "前台只看到「已售罄」，看不出库存在算错"));
        }
        return Optional.empty();
    }

    /** 把 {@code Optional} 收成列表，便于上游用 {@code addIfPresent} 的写法累积 */
    public static void addIfPresent(List<Diff> target, Optional<Diff> diff) {
        diff.ifPresent(target::add);
    }
}
