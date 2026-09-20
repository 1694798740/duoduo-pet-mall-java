package com.duoduo.fulfilment.support;

import java.util.List;
import java.util.Locale;

/**
 * 信用分模型（单一事实来源）。
 *
 * <p><b>为什么把这套算法单独放一个类</b>：信用分的口径会被反复讨论、反复调整，
 * 而它同时出现在「公开查询接口」「定时回写任务」「冒烟断言」三处。
 * 算法散进 service 之后，改一处忘一处，就会出现「页面显示 82、库里存 79」这种
 * 谁也说不清谁对的分裂 —— 这类不一致在用户投诉时最难查。
 * 这里只做<b>纯计算</b>：输入事实、权重，输出分数与构成明细，不碰数据库、不碰 HTTP。
 *
 * <h2>权重表（默认，全部可由环境变量覆盖）</h2>
 * <pre>
 *   因子            满分    计算口径
 *   基础分 base      60     固定底分（注册即享有）
 *   履约完成率       18     round(18 × 已完成 / 已了结)   已了结 = 完成 + 关闭 + 退款
 *   评价质量         16     round(16 × 平均分 / 5)
 *   售后诚信          6     round(6 × (1 − 被裁定退款数 / 总订单数))
 *   ─────────────────────────
 *   合计            100
 * </pre>
 *
 * <h2>三条硬性约定</h2>
 * <ol>
 *   <li><b>因子得分之和 == 总分</b>。四个因子都是整数分，总分就是它们的和 ——
 *       不做四舍五入后再对齐，因此「明细加起来对不上总分」在结构上就不可能发生。
 *       这也是权重之和必须等于 100 的原因（启动时校验，配置错直接不让服务起来）。</li>
 *   <li><b>无数据 = 基础分</b>。三项加权因子在「分母为 0」时一律记 0 分，
 *       于是没有任何交易记录的用户得到的就是基础分，而不是 null 或 0。</li>
 *   <li><b>单调可解释</b>。每个因子都是其输入的单调不减函数：完成得越多、评分越高、
 *       被裁定退款越少，得分只升不降。刻意不设负分项 —— v1 是鼓励型模型，
 *       要用惩罚压低分数再说，且那时应先把「什么样的行为该扣多少」讨论清楚。</li>
 * </ol>
 *
 * <p><b>有效区间</b>：因子非负且满和 100，故实际取值落在 {@code [base, 100]}（默认 60~100）；
 * 输出仍按 {@code [0, 100]} 夹取，作为配置越界时的兜底。缺口部分留给将来的惩罚项。
 */
public final class CreditModel {

    // ---------------- 因子编码 ----------------
    public static final String FACTOR_BASE = "BASE";
    public static final String FACTOR_COMPLETION = "COMPLETION";
    public static final String FACTOR_REVIEW = "REVIEW";
    public static final String FACTOR_AFTERSALE = "AFTERSALE";

    // ---------------- 等级 ----------------
    public static final String LEVEL_EXCELLENT = "EXCELLENT";
    public static final String LEVEL_GOOD = "GOOD";
    public static final String LEVEL_FAIR = "FAIR";
    public static final String LEVEL_POOR = "POOR";

    /** 满分上限：权重之和必须等于它，否则启动失败（明细与总分自洽的前提） */
    public static final int MAX_SCORE = 100;

    private CreditModel() {
    }

    /**
     * 权重表。四个数之和必须等于 {@link #MAX_SCORE}，否则 {@link #validate} 抛异常。
     */
    public record Weights(int base, int completion, int review, int aftersale) {

        /** 默认口径：基础分 60 / 履约 18 / 评价 16 / 售后 6 = 100 */
        public static Weights defaults() {
            return new Weights(60, 18, 16, 6);
        }

        public int total() {
            return base + completion + review + aftersale;
        }
    }

    /**
     * 计算输入（全部来自外部事实，本类不改写）。
     *
     * @param completedOrders   已完成订单数（卖家维度）
     * @param closedOrders      已关闭订单数（超时关单 / 用户取消）
     * @param refundedOrders    已退款订单数
     * @param totalOrders       订单总数（含待付款，用作售后败诉率的分母）
     * @param reviewCount       收到的评价条数（已审核通过的）
     * @param avgRating         平均评分（1~5），无评价时为 0
     * @param adverseAfterSales 被平台裁定支持买家的售后单数
     */
    public record Inputs(
            int completedOrders,
            int closedOrders,
            int refundedOrders,
            int totalOrders,
            int reviewCount,
            double avgRating,
            int adverseAfterSales
    ) {
        /** 空输入：任何数据都没有的用户 */
        public static Inputs empty() {
            return new Inputs(0, 0, 0, 0, 0, 0.0, 0);
        }
    }

    /** 单个因子明细。{@code points} 为整数得分，{@code rawValue} 是人类可读的原始值。 */
    public record Factor(String code, String label, int weight, String rawValue, int points) {
    }

    /** 计算结果：总分 + 等级 + 四项因子（因子之和恒等于总分）。 */
    public record Breakdown(int score, String level, String levelLabel, List<Factor> factors) {
    }

    /**
     * 启动时校验权重：非负且合计 100。
     *
     * <p>宁可服务起不来，也不要带着一个「因子加起来不等于总分」的配置对外供分 ——
     * 后者会让前端显示的构成明细与总分互相矛盾，比不可用更糟。
     */
    public static void validate(Weights w) {
        if (w.base() < 0 || w.completion() < 0 || w.review() < 0 || w.aftersale() < 0) {
            throw new IllegalStateException("信用分权重不能为负数：" + w);
        }
        if (w.total() != MAX_SCORE) {
            throw new IllegalStateException(
                    "信用分权重之和必须等于 " + MAX_SCORE + "，当前为 " + w.total() + "：" + w);
        }
    }

    /**
     * 计算信用分。纯函数：同样的输入与权重永远得到同样的结果。
     */
    public static Breakdown compute(Inputs in, Weights w) {
        int basePoints = w.base();

        // 履约完成率：分母是「已了结」的订单（完成 / 关闭 / 退款）。
        // 不含待付款与在途单 —— 把「还没走完」的单算进分母，会让一个刚下单的用户分数无理由地掉。
        int settled = in.completedOrders() + in.closedOrders() + in.refundedOrders();
        int completionPoints;
        String completionRaw;
        if (settled <= 0) {
            completionPoints = 0;
            completionRaw = "暂无可结算的交易";
        } else {
            double rate = (double) in.completedOrders() / settled;
            completionPoints = (int) Math.round(w.completion() * rate);
            completionRaw = String.format(Locale.ROOT, "%.0f%%（完成 %d / 已了结 %d）",
                    rate * 100, in.completedOrders(), settled);
        }

        // 评价质量：平均分 / 5。无评价记 0 分（而不是给满分或中性分）——
        // 「不评价」不该比「认真评价」更划算，否则会反过来鼓励沉默。
        int reviewPoints;
        String reviewRaw;
        if (in.reviewCount() <= 0) {
            reviewPoints = 0;
            reviewRaw = "暂无评价";
        } else {
            double rate = Math.max(0.0, Math.min(1.0, in.avgRating() / 5.0));
            reviewPoints = (int) Math.round(w.review() * rate);
            reviewRaw = String.format(Locale.ROOT, "%.2f/5（%d 条）", in.avgRating(), in.reviewCount());
        }

        // 售后诚信：被平台裁定支持买家的比例越低越好。
        // 分母用「订单总数」而不是「售后单数」：以订单为基准，量大者不会因偶发一笔被过度惩罚。
        int aftersalePoints;
        String aftersaleRaw;
        if (in.totalOrders() <= 0) {
            aftersalePoints = 0;
            aftersaleRaw = "暂无交易记录";
        } else {
            double adverseRate = Math.min(1.0, (double) in.adverseAfterSales() / in.totalOrders());
            aftersalePoints = (int) Math.round(w.aftersale() * (1.0 - adverseRate));
            aftersaleRaw = in.adverseAfterSales() <= 0
                    ? "无被裁定退款的记录"
                    : String.format(Locale.ROOT, "%d 笔被裁定退款 / %d 单",
                    in.adverseAfterSales(), in.totalOrders());
        }

        List<Factor> factors = List.of(
                new Factor(FACTOR_BASE, "基础分", basePoints, "注册即享有的信任底分", basePoints),
                new Factor(FACTOR_COMPLETION, "履约完成率", w.completion(), completionRaw, completionPoints),
                new Factor(FACTOR_REVIEW, "评价质量", w.review(), reviewRaw, reviewPoints),
                new Factor(FACTOR_AFTERSALE, "售后诚信", w.aftersale(), aftersaleRaw, aftersalePoints));

        // 因子之和即总分（上面已保证权重满和，正常情况下不会触发夹取）。
        int sum = factors.stream().mapToInt(Factor::points).sum();
        int score = Math.max(0, Math.min(MAX_SCORE, sum));
        return new Breakdown(score, levelOf(score), levelLabelOf(score), factors);
    }

    /** 等级编码 */
    public static String levelOf(int score) {
        if (score >= 90) {
            return LEVEL_EXCELLENT;
        }
        if (score >= 80) {
            return LEVEL_GOOD;
        }
        if (score >= 70) {
            return LEVEL_FAIR;
        }
        return LEVEL_POOR;
    }

    /**
     * 等级中文文案。
     *
     * <p>与项目其它状态文案同一原则：文案由服务端给出，前端不再维护一份映射 ——
     * 两处映射迟早漂移，而「同一个分数在两页显示成两种等级」在用户眼里就是系统坏了。
     */
    public static String levelLabelOf(int score) {
        return switch (levelOf(score)) {
            case LEVEL_EXCELLENT -> "优秀";
            case LEVEL_GOOD -> "良好";
            case LEVEL_FAIR -> "一般";
            default -> "待提升";
        };
    }
}
