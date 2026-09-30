package com.duoduo.marketing.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 券状态机单测（第五十八轮 · 营销域单元测试补齐）。
 *
 * <p>两条硬规则必须钉死（类注释里写明的）：
 * ① {@code USED} 是终态 —— 核销过的券不能再用、不能解锁，否则「退款要不要退券」
 *    会变成一个没有答案的问题（v1 口径：券不退回）；
 * ② {@code LOCKED} 必须能回到 {@code UNUSED} —— 锁券在下单、解锁在关单/超时，
 *    中间隔着「用户关掉浏览器」，若不能解锁，券会被永久占用。
 */
class CouponStatusTest {

    @Test
    void 只有未使用的券可锁定() {
        assertThat(CouponStatus.UNUSED.lockable()).isTrue();
        assertThat(CouponStatus.LOCKED.lockable()).isFalse();
        assertThat(CouponStatus.USED.lockable()).isFalse();
        assertThat(CouponStatus.EXPIRED.lockable()).isFalse();
    }

    @Test
    void 只有已锁定的券可解锁() {
        assertThat(CouponStatus.LOCKED.releasable()).isTrue();
        assertThat(CouponStatus.UNUSED.releasable()).isFalse();
        assertThat(CouponStatus.USED.releasable()).isFalse();
    }

    /** ★ 核销是终态：这条边画错，就等于允许一张券被用两次 */
    @Test
    void 已使用与已过期都是终态() {
        assertThat(CouponStatus.USED.terminal()).isTrue();
        assertThat(CouponStatus.EXPIRED.terminal()).isTrue();
        assertThat(CouponStatus.UNUSED.terminal()).isFalse();
        assertThat(CouponStatus.LOCKED.terminal()).isFalse();
    }

    @Test
    void 解析大小写不敏感_未知返回空() {
        assertThat(CouponStatus.of("unused")).isEqualTo(CouponStatus.UNUSED);
        assertThat(CouponStatus.of(" LOCKED ")).isEqualTo(CouponStatus.LOCKED);
        assertThat(CouponStatus.of("NOT_A_STATUS")).isNull();
        assertThat(CouponStatus.of(null)).isNull();
    }

    @Test
    void 未知状态文案原样返回_展示层不静默吞掉() {
        assertThat(CouponStatus.labelOf("USED")).isEqualTo("已使用");
        assertThat(CouponStatus.labelOf("WEIRD")).isEqualTo("WEIRD");
    }
}
