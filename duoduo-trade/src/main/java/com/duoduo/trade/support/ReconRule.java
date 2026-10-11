package com.duoduo.trade.support;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对账规则清单（S3）。
 *
 * <p><b>规则代号是稳定标识，不是文案。</b>差异明细里存的是代号；文案改一个字
 * 不该让「连续 3 天都有同类差异」这条统计断掉 —— 而按文案分组时它一定会断。
 *
 * <p><b>每条规则都必须回答一个问题：「哪两个来源对不上？」</b>
 * 只是把同一个函数调两遍、把同一个公式算两遍，不算对账 ——
 * 那样的两条路径会一起错、一起对，什么也发现不了。因此这里全部是
 * <b>跨来源</b>的比对：订单状态 ↔ 托管状态、订单明细数量 ↔ 冻结台账数量、
 * 交易域冻结量 ↔ 商品域库存款。
 *
 * <p><b>唯一需要解释的是 {@link #MONEY_FEE_ILLEGAL}</b>：它<b>刻意不比对费率</b>。
 * 这一条最初写成"佣金是否等于按费率算出来的数"，在本机库上一跑就是
 * <b>92 条 + 375 条差异</b> —— 全部是假的：那些单是在费率还是 1% 的年代、
 * 或自营免佣生效之前结的。项目里其实早就为这个坑写过一句话
 * （{@code SettlementEntity}：「费率会随运营策略调整，而历史单据必须保留结算当刻的口径，
 * 否则对账时会出现『按今天的费率算昨天的单，怎么都对不平』」），
 * 只是写在了另一处、没有拦住这条新规则。
 * <b>结论：佣金是"政策的时间函数"，与政策挂钩的比对属于回归测试（settlement-smoke 已覆盖），
 * 不属于每日对账。每日对账只判与政策无关的结构不变量。</b>
 *
 * <p>关于 {@link #ALL}：它是**声明清单**。判据单测里有一条守卫断言
 * 「声明了 N 条 → 恰好有 N 条被覆盖」，防止新加规则却忘了写用例
 * （新规则没用例 = 它永远不会被发现是错的，而且不会有任何报错）。
 */
public enum ReconRule {

    // ---------------- 钱 ----------------

    /**
     * 订单已付款，但资金托管单不存在。
     *
     * <p>这是钱侧最危险的一种：买家付了钱，而平台账上没有这笔托管 ——
     * 既放不了款也退不了款，只能靠人工对账发现。**只在"已付款"及之后的状态判定**：
     * {@code REFUNDED} 不在此列，因为从 {@code CLOSED}（从未付款）也能到 {@code REFUNDED}
     * 之外 —— 单看状态分不出"有没有付过款"，硬判会制造假差异。
     */
    MONEY_MISSING_SETTLEMENT(ReconScope.MONEY, Severity.HIGH, "ORDER", "订单已付款但托管单缺失"),

    /** 托管单挂了，但它指向的订单不存在。孤儿台账会让钱包汇总凭空多出一笔。 */
    MONEY_ORPHAN_SETTLEMENT(ReconScope.MONEY, Severity.HIGH, "ORDER", "托管单找不到对应订单"),

    /**
     * 订单状态与托管状态矛盾。
     *
     * <p>例：订单已关闭，钱却还在托管中；订单已退款，托管却仍是"已放款"（那笔退款只可能是垫付，
     * 应当落成 {@code ADVANCED} 并形成欠款）。
     */
    MONEY_STATUS_MISMATCH(ReconScope.MONEY, Severity.HIGH, "ORDER", "订单状态与托管状态矛盾"),

    /**
     * 订单已完成，超过放款持有期仍是"托管中"。
     *
     * <p>这是「钱卡住了」——补偿调度该放款却没放。买家早已确认收货，
     * 卖家却拿不到钱，而系统里没有任何地方报错。
     */
    MONEY_COMPLETED_NOT_RELEASED(ReconScope.MONEY, Severity.HIGH, "ORDER", "订单已完成但超期未放款"),

    /** 已放款（或已垫付）却没有放款时间 —— 出问题时无法回答"这笔钱是哪天出去的"。 */
    MONEY_RELEASE_TIME_MISSING(ReconScope.MONEY, Severity.MEDIUM, "ORDER", "放款状态缺放款时间"),

    /**
     * 托管金额与订单实付金额不一致。
     *
     * <p><b>跨表</b>：托管单的金额来自下单时的快照（{@code openCustody} 传 {@code order.payAmount}），
     * 订单的实付是另一列。两者本该恒等，一旦分叉说明有人改了其中一侧 ——
     * 后果是"退给买家的钱"与"本该退的钱"不一样，而两边都自认为对。
     */
    MONEY_AMOUNT_MISMATCH(ReconScope.MONEY, Severity.HIGH, "ORDER", "托管金额与订单实付不一致"),

    /**
     * 佣金结构不合法：为负、或大于托管本金、或托管金额为负。
     *
     * <p><b>刻意不比费率</b>（理由见类注释）：费率是政策的时间函数，
     * 拿今天的费率判历史行会稳定产出一批假差异 —— 本机实测 92 + 375 条。
     * 这里只留与政策无关的三条不变量：任何一条被违反都不可能是"那会儿政策不一样"，
     * 只可能是算错了。
     */
    MONEY_FEE_ILLEGAL(ReconScope.MONEY, Severity.HIGH, "ORDER", "佣金结构不合法（负佣 / 超本金）"),

    /** 卖家欠款金额非正、或状态取值非法。欠款是非负账，负数等于"平台倒欠卖家"。 */
    MONEY_DEBT_INVALID(ReconScope.MONEY, Severity.MEDIUM, "ORDER", "卖家欠款金额或状态非法"),

    // ---------------- 库存 ----------------

    /**
     * 冻结台账与订单状态不相容。
     *
     * <p>冻结只在「待付款」期间成立：一旦订单离开待付款，冻结必须已经走到
     * {@code RELEASED}（取消/超时）或 {@code DEDUCTED}（已扣减）。
     * 反方向（订单还在待付款、冻结却已扣减）同样是错的 —— 等于货已经出去了、用户却没付钱。
     * 冻结单找不到订单也归到这条：那笔冻结永远释放不掉。
     */
    STOCK_FREEZE_LEAK(ReconScope.STOCK, Severity.HIGH, "ORDER", "库存冻结与订单状态不相容"),

    /**
     * 冻结早已过期仍未释放。
     *
     * <p>逾期本身是正常的（补偿调度下一轮就会处理）；**过期超过一整轮补偿周期**才是异常，
     * 它说明补偿调度没在跑。这条规则的价值正在于它是「调度死了」的探针 ——
     * 调度停摆不会有任何报错，只会表现为库存被一批僵尸冻结慢慢占满。
     */
    STOCK_FREEZE_STALE(ReconScope.STOCK, Severity.HIGH, "ORDER", "库存冻结超期未释放（补偿疑似停摆）"),

    /** 同一订单同一 SKU 的冻结数量与商品明细数量不符 —— 有人买 3 件却只冻了 1 件。 */
    STOCK_FREEZE_QTY_MISMATCH(ReconScope.STOCK, Severity.HIGH, "ORDER", "冻结数量与订单明细数量不符"),

    /**
     * 交易域的冻结之和与商品域记录的冻结库存不一致。
     *
     * <p><b>跨域</b>：这是本套规则里唯一需要同时读两个库的比对，也是"冻结/释放"
     * 这类跨服务调用最容易漂移的地方 —— 调用超时、响应丢失、单边成功，
     * 都会在这里留下痕迹，而两个服务各自看自己的数据都是自洽的。
     */
    STOCK_CROSS_DOMAIN_DRIFT(ReconScope.STOCK, Severity.HIGH, "SKU", "冻结库存与商品域台账不一致"),

    /**
     * 商品域库存出现负数，或冻结量大于总库存。
     *
     * <p><b>为什么必须看原始两列、不能看"可售库存"</b>：对外下发的
     * {@code availableStock = max(0, stock - frozen)} 把 {@code frozen > stock}
     * 这个异常**夹成了 0** —— 前台只会显示"已售罄"，看不出库存在算错。
     * 异常被一个看似合理的展示层兜底吞掉了，这正是需要独立对账的原因。
     */
    STOCK_NEGATIVE(ReconScope.STOCK, Severity.HIGH, "SKU", "库存为负或冻结大于总库存");

    /** 差异严重度。{@code HIGH} 意味着有资金或库存事实已经错位，需要人工介入。 */
    public enum Severity {
        HIGH("高"),
        MEDIUM("中");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final ReconScope scope;
    private final Severity severity;
    private final String refType;
    private final String label;

    ReconRule(ReconScope scope, Severity severity, String refType, String label) {
        this.scope = scope;
        this.severity = severity;
        this.refType = refType;
        this.label = label;
    }

    public ReconScope scope() {
        return scope;
    }

    public Severity severity() {
        return severity;
    }

    /** 差异主体类型（ORDER / SKU / SELLER），与 t_recon_diff.ref_type 一致 */
    public String refType() {
        return refType;
    }

    public String label() {
        return label;
    }

    /** 该范围下的全部规则 */
    public static List<ReconRule> ofScope(ReconScope scope) {
        return Arrays.stream(values()).filter(r -> r.scope == scope).toList();
    }

    /** 声明清单（判据单测用它做「声明了就要有用例」的守卫） */
    public static final Set<ReconRule> ALL =
            java.util.Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values())));

    public static ReconRule of(String code) {
        return Arrays.stream(values())
                .filter(r -> r.name().equals(code))
                .findFirst()
                .orElse(null);
    }
}
