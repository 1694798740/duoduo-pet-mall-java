package com.duoduo.trade.support;

import java.util.Arrays;

/**
 * 订单状态机。
 *
 * <p>把状态与合法流转写成显式枚举，而不是散落在 if/else 里的字符串比较 ——
 * 非法流转（如「已关闭」再发货）应当由一处统一拒绝，否则迟早会有分支漏判。
 *
 * <p>合法流转全景：
 * <pre>
 *   PENDING_PAYMENT ──→ PAID ──→ SHIPPED ──→ COMPLETED
 *          │              │           │
 *          └──→ CLOSED    │           │
 *             （取消/超时）  └───────────┴──→ REFUNDED（已退款）
 * </pre>
 *
 * <p><b>退款为什么单独一个状态，而不是复用 CLOSED</b>：
 * 早期实现把「退款关单」写成 {@code PAID → CLOSED}，结果埋了一个坑 ——
 * 那条边既是「售后退款」的通路，也就等于把「用户自助取消」的口子一起打开了，
 * 已付款订单能被买家一键取消，钱与货的归属立刻说不清（见看板 I-11）。
 * 现在退款有独立终态 {@link #REFUNDED}，语义是「钱已原路退回」，
 * 与「取消 / 超时」（{@link #CLOSED}，钱从未收过或已释放冻结）彻底分开，
 * 资金动作与状态名一一对应，不再需要靠调用方「记得别走那条边」。
 *
 * <p><b>注意</b>：这是「业务上可能发生的流转」的全集，不等于「每个接口都能走」。
 * 例如 {@code PAID → SHIPPED} 只有卖家侧发货接口能走、{@code PAID → REFUNDED}
 * 买家只能自助（未发货），已发货的要走运营侧售后入口。
 * 各接口必须再收窄自己的合法起点。
 */
public enum OrderStatus {

    /** 待付款：下单成功、库存已冻结，等待支付 */
    PENDING_PAYMENT("待付款"),
    /** 已付款：支付成功、库存已扣减，资金进入平台托管 */
    PAID("已付款"),
    /** 已发货 */
    SHIPPED("已发货"),
    /** 已完成：确认收货（或发货满 N 天自动确认），资金已放款给卖家 */
    COMPLETED("已完成"),
    /** 已退款：钱已退回买家，订单终结 */
    REFUNDED("已退款"),
    /** 已关闭：用户取消或超时未支付（钱从未收过，或已释放冻结） */
    CLOSED("已关闭");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    /** 中文展示文案（对前端友好，不用前端再维护一份映射表） */
    public String label() {
        return label;
    }

    public static OrderStatus of(String code) {
        return Arrays.stream(values())
                .filter(s -> s.name().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 未知状态返回原字符串，避免因为脏数据把接口打挂 */
    public static String labelOf(String code) {
        OrderStatus status = of(code);
        return status == null ? code : status.label();
    }

    /** 是否是终结状态（不再有任何后续流转） */
    public boolean isFinal() {
        return this == COMPLETED || this == REFUNDED || this == CLOSED;
    }

    /**
     * 是否允许从当前状态流转到目标状态（业务可能性全集，见类注释）。
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAYMENT -> target == PAID || target == CLOSED;
            case PAID -> target == SHIPPED || target == REFUNDED;
            case SHIPPED -> target == COMPLETED || target == REFUNDED;
            // 已完成之后钱已放款给卖家，退款需要走追偿/平台垫付，不属于本状态机的能力
            case COMPLETED, REFUNDED, CLOSED -> false;
        };
    }
}
