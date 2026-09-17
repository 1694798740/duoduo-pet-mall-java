package com.duoduo.trade.support;

import java.util.Arrays;

/**
 * 订单状态机（本阶段已落地的部分）。
 *
 * <p>把状态与合法流转写成显式枚举，而不是散落在 if/else 里的字符串比较 ——
 * 非法流转（如「已关闭」再发货）应当由一处统一拒绝，否则迟早会有分支漏判。
 *
 * <p>本阶段（M5-4/M5-5）只用到 {@link #PENDING_PAYMENT} 与 {@link #CLOSED}；
 * {@link #PAID} / {@link #SHIPPED} / {@link #COMPLETED} 在支付与发货阶段（M5-6）接入，
 * 这里先把口径固定下来，避免后续各处自造字符串。
 */
public enum OrderStatus {

    /** 待付款：下单成功、库存已冻结，等待支付 */
    PENDING_PAYMENT("待付款"),
    /** 已付款：支付成功、库存已扣减 */
    PAID("已付款"),
    /** 已发货 */
    SHIPPED("已发货"),
    /** 已完成：确认收货 */
    COMPLETED("已完成"),
    /** 已关闭：用户取消或超时未支付 */
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

    /**
     * 是否允许从当前状态流转到目标状态。
     *
     * <p><b>注意</b>：这是「业务上可能发生的流转」的全集，不等于「每个接口都能走」。
     * 例如 {@code PAID → CLOSED} 只有「退款关单」能走（需售后流程与资金操作），
     * 不能让用户自助「取消订单」直接命中这条边 —— 各接口必须再收窄自己的合法起点。
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAYMENT -> target == PAID || target == CLOSED;
            // PAID → CLOSED 仅限退款关单（售后流程），不对外开放给「用户取消」
            case PAID -> target == SHIPPED || target == CLOSED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CLOSED -> false;
        };
    }
}
