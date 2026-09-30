package com.duoduo.trade.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单状态机单测（第五十八轮 · 交易域单元测试补齐）。
 *
 * <p>为什么先测这里：状态机是「钱与货归属」的**唯一裁判**。它的边画错一条，
 * 后果不是报错而是**资金说不清**（历史缺陷 I-11：当年 {@code PAID → CLOSED}
 * 被当成退款通路，等于给买家开了「已付款还能自助取消」的口子）。
 * 这类错误不会有任何日志，只能靠断言把每一条边钉死。
 */
class OrderStatusTest {

    // ---------- 合法流转 ----------

    @Test
    void 主链路_待付款到已完成() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    void 取消与超时关单() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CLOSED)).isTrue();
    }

    @Test
    void 退款两条入口_未发货与已发货() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.REFUNDED)).isTrue();
    }

    // ---------- 非法流转（每一条都对应一类真实事故） ----------

    @Test
    void 已关闭不能再发货() {
        assertThat(OrderStatus.CLOSED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.CLOSED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    /**
     * ★ 这是 B-01 的由来：已完成的订单**不能**通过订单状态机退款。
     *
     * <p>钱已经放款给卖家，此时退款动的是平台的钱，**必须走独立的垫付通道**
     * （记录卖家欠款），而不是在这里开一条边 —— 否则系统里会出现
     * 「钱退了、但没有欠款记录」的悬空账。
     */
    @Test
    void 已完成不能直接退款_必须走平台垫付通道() {
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.REFUNDED)).isFalse();
    }

    @Test
    void 退款是终态_不能再流转() {
        assertThat(OrderStatus.REFUNDED.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.REFUNDED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.REFUNDED.canTransitionTo(OrderStatus.CLOSED)).isFalse();
    }

    @Test
    void 已完成不能再回到已发货() {
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    // ---------- 解析与文案 ----------

    @Test
    void 按码解析与中文文案() {
        assertThat(OrderStatus.of("PAID")).isEqualTo(OrderStatus.PAID);
        assertThat(OrderStatus.of("paid")).isNull(); // 大小写敏感，脏数据不该被猜对
        assertThat(OrderStatus.of(null)).isNull();
        assertThat(OrderStatus.PENDING_PAYMENT.label()).isEqualTo("待付款");
    }

    @Test
    void 未知状态取文案_返回原字符串_不打挂接口() {
        assertThat(OrderStatus.labelOf("NOT_A_STATUS")).isEqualTo("NOT_A_STATUS");
        assertThat(OrderStatus.labelOf(null)).isNull();
    }
}
