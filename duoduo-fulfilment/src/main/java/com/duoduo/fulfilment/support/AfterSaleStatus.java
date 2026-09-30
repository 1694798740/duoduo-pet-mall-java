package com.duoduo.fulfilment.support;

import java.util.Set;

/**
 * 售后单状态机（单一事实来源）。
 *
 * <p>状态一律存**枚举名**（VARCHAR），不用数字码：线上排查时 `SELECT status FROM t_after_sale`
 * 直接能看懂，不需要另一张映射表。
 *
 * <pre>
 *                 ┌─────────────── 卖家 AGREE（仅退款：执行退款）────────────▶ REFUNDED（终态）
 *                 │
 *                 ├─────────────── 卖家 AGREE（退货退款）──▶ WAITING_RETURN
 *   PENDING ──────┤                                            │（买家填寄回运单）
 *   （待卖家处理） │                                            ▼
 *                 │                                        RETURNING
 *                 │                                            │（卖家确认收货，此时才退款）
 *                 │                                            ▼
 *                 │                                        REFUNDED（终态）
 *                 │
 *                 ├─────────────── 卖家 REJECT（必填说明）──▶ SELLER_REJECTED ──┐
 *                 └─────────────── 买家申请仲裁 ──────────────────▶ ARBITRATING ─┤
 *                                                                                │
 *                       运营 arbitrate：APPROVE_REFUND（执行退款）────────▶ REFUNDED（终态）
 *                       运营 arbitrate：REJECT ─────────────────────────────▶ CLOSED（终态）
 * </pre>
 *
 * <p>三条边刻意说明：
 * <ul>
 *   <li><b>买家可以从 PENDING 直接申请仲裁</b>：卖家一直不处理时，若只能死等，
 *       售后就变成了「卖家单方面决定要不要理你」。给买家一条求助平台的路，是这类流程的基本要求；</li>
 *   <li><b>退款是终态、驳回也是终态</b>：不设「已驳回」再改判的边。要改判只能新立案，
 *       这样每一条售后单的结论永远只有一次，留痕不会自相矛盾；</li>
 *   <li><b>退货退款的钱在「卖家确认收货」那一刻才动</b>（第五十八轮 P1-5）：
 *       同意即退款意味着货还没回来钱先走了；反过来「等收货再退」若没有系统入口，
 *       就变成一句只能靠线下执行的空话。WAITING_RETURN / RETURNING 两态让这件事有据可查，
 *       且这两态同样可以进仲裁 —— 货寄出后卖家一直不确认收货时，买家不能没有出路。</li>
 * </ul>
 *
 * <p>所有状态迁移都通过 {@code AfterSaleMapper} 的 <b>CAS 条件更新</b>完成
 * （把「当前必须是某个状态」写进 WHERE），由数据库保证并发下只有一次生效 ——
 * 不依赖分布式锁，也不依赖「先查再改」那段必然存在的窗口。
 */
public final class AfterSaleStatus {

    /** 已立案，待卖家处理 */
    public static final String PENDING = "PENDING";
    /** 卖家已拒绝（买家可继续申请平台仲裁） */
    public static final String SELLER_REJECTED = "SELLER_REJECTED";
    /** 卖家已同意退货，等待买家寄回（第五十八轮 P1-5，仅 RETURN_REFUND 会经过） */
    public static final String WAITING_RETURN = "WAITING_RETURN";
    /** 买家已寄回，等待卖家签收（签收入的那一刻才执行退款） */
    public static final String RETURNING = "RETURNING";
    /** 买家已申请平台仲裁，待运营裁定 */
    public static final String ARBITRATING = "ARBITRATING";
    /** 已退款（终态） */
    public static final String REFUNDED = "REFUNDED";
    /** 仲裁驳回 / 关闭（终态） */
    public static final String CLOSED = "CLOSED";

    /** 终态集合：终态不允许再发生任何迁移 */
    private static final Set<String> TERMINAL = Set.of(REFUNDED, CLOSED);

    /** 售后类型：仅退款（钱退、货不用寄回） */
    public static final String TYPE_REFUND_ONLY = "REFUND_ONLY";
    /** 售后类型：退货退款（货要寄回：卖家同意 → 买家寄回 → 卖家签收后才退款） */
    public static final String TYPE_RETURN_REFUND = "RETURN_REFUND";

    /** 卖家处理动作：同意（执行退款） */
    public static final String ACTION_AGREE = "AGREE";
    /** 卖家处理动作：拒绝（必填说明） */
    public static final String ACTION_REJECT = "REJECT";

    /** 仲裁结论：支持退款 */
    public static final String RESULT_APPROVE_REFUND = "APPROVE_REFUND";
    /** 仲裁结论：驳回 */
    public static final String RESULT_REJECT = "REJECT";

    private AfterSaleStatus() {
    }

    /**
     * 状态中文文案。
     *
     * <p>文案由服务端给出，前端不再维护一份映射 ——
     * 两处映射迟早会漂移，而「状态文案不一致」在用户眼里就是系统坏了。
     * 未知状态原样返回（不抛异常），避免一条脏数据把整个列表打不开。
     */
    public static String labelOf(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "待卖家处理";
            case SELLER_REJECTED -> "卖家已拒绝";
            case WAITING_RETURN -> "待买家寄回";
            case RETURNING -> "买家已寄回，待收货";
            case ARBITRATING -> "平台仲裁中";
            case REFUNDED -> "已退款";
            case CLOSED -> "已关闭";
            default -> status;
        };
    }

    /** 售后类型文案 */
    public static String typeLabelOf(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_REFUND_ONLY -> "仅退款";
            case TYPE_RETURN_REFUND -> "退货退款";
            default -> type;
        };
    }

    /** 是否终态 */
    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }

    /** 是否合法的售后类型 */
    public static boolean isKnownType(String type) {
        return TYPE_REFUND_ONLY.equals(type) || TYPE_RETURN_REFUND.equals(type);
    }
}
