package com.duoduo.trade.support;

import java.util.Arrays;

/**
 * 资金托管状态。
 *
 * <p>与 {@link OrderStatus} 一样把状态写成显式枚举：状态名的字符串散落在各处时，
 * 迟早会出现「一处写 CUSTODY、另一处写 CUSTODIED」这种谁也发现不了的错配。
 *
 * <p>文案由服务端给出，前端不必再维护一份映射表。
 */
public enum SettlementStatus {

    /** 托管中：买家已付款，钱在平台账上 */
    CUSTODY("托管中"),
    /** 已放款：确认收货后已打给卖家 */
    RELEASED("已放款"),
    /** 已退回买家 */
    REFUNDED("已退回买家"),
    /**
     * 平台垫付（第五十八轮 · B-01）：钱已放款给卖家后，平台先垫付给买家。
     *
     * <p>它是 {@code RELEASED} 之后的一条**新边**，不是回退 —— 放款不可逆，
     * 垫付动的是平台自己的钱，并在 {@code t_seller_debt} 形成一笔卖家欠款。
     */
    ADVANCED("平台垫付");

    private final String label;

    SettlementStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static SettlementStatus of(String code) {
        return Arrays.stream(values())
                .filter(s -> s.name().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 未知状态返回原字符串，避免脏数据把接口打挂 */
    public static String labelOf(String code) {
        SettlementStatus status = of(code);
        return status == null ? code : status.label();
    }
}
