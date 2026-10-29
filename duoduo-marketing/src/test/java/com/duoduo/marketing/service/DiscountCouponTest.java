package com.duoduo.marketing.service;

import com.duoduo.marketing.entity.CouponTemplateEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 折扣券金额计算单测（E-05 · 2026-10-26）。
 *
 * <p>为什么这些断言必须存在：折扣券的抵扣额是「比例 × 金额」算出来的，
 * 而不是像满减券那样直接读一个字段 —— 取整方向、封顶、不得超过订单金额
 * 这三件事任何一条写错，损失的都是真金白银，且不会有任何异常抛出来。
 *
 * <p>口径（用户已拍板）：
 * <ul>
 *   <li>85 = 8.5 折，即减 15%；</li>
 *   <li>结果<b>向下取整</b>（少减 1 分给平台，绝不多减）；</li>
 *   <li>{@code max_discount_amount > 0} 时封顶；</li>
 *   <li>抵扣额永远不超过订单商品金额。</li>
 * </ul>
 */
class DiscountCouponTest {

    private static CouponTemplateEntity discount(int percent, long capFen) {
        CouponTemplateEntity t = new CouponTemplateEntity();
        t.setType(CouponTemplateEntity.TYPE_DISCOUNT);
        t.setDiscountPercent(percent);
        t.setMaxDiscountAmount(capFen);
        t.setDiscountAmount(0L);
        t.setThresholdAmount(0L);
        return t;
    }

    private static CouponTemplateEntity fullReduce(long discountFen) {
        CouponTemplateEntity t = new CouponTemplateEntity();
        t.setType(CouponTemplateEntity.TYPE_FULL_REDUCE);
        t.setDiscountAmount(discountFen);
        t.setDiscountPercent(100);
        t.setMaxDiscountAmount(0L);
        t.setThresholdAmount(0L);
        return t;
    }

    @Test
    void 八五折_一百元减十五元() {
        assertThat(CouponService.calculateDiscount(discount(85, 0L), 10000L)).isEqualTo(1500L);
    }

    @Test
    void 折扣额向下取整_不四舍五入() {
        // 999 分 × 15% = 149.85 分：四舍五入会变成 150（平台多减 1 分），必须取 149
        assertThat(CouponService.calculateDiscount(discount(85, 0L), 999L)).isEqualTo(149L);
    }

    @Test
    void 封顶金额生效_超过封顶只减封顶值() {
        // 100 元 8 折本应减 20 元，封顶 9 元则只减 900 分
        assertThat(CouponService.calculateDiscount(discount(80, 900L), 10000L)).isEqualTo(900L);
    }

    @Test
    void 封顶为0表示不封顶() {
        assertThat(CouponService.calculateDiscount(discount(80, 0L), 10000L)).isEqualTo(2000L);
    }

    @Test
    void 抵扣额不超过订单金额() {
        // 满减券固定减 5 元，但订单只有 1 元：不能减出负数应付
        assertThat(CouponService.calculateDiscount(fullReduce(500L), 100L)).isEqualTo(100L);
    }

    @Test
    void 折扣比例缺失或越界时按不打折处理() {
        CouponTemplateEntity missing = discount(85, 0L);
        missing.setDiscountPercent(null);
        assertThat(CouponService.calculateDiscount(missing, 10000L)).isZero();

        assertThat(CouponService.calculateDiscount(discount(0, 0L), 10000L)).isZero();
        assertThat(CouponService.calculateDiscount(discount(100, 0L), 10000L)).isZero();
        assertThat(CouponService.calculateDiscount(discount(150, 0L), 10000L)).isZero();
    }

    @Test
    void 折后比例100即满减券的默认值_不产生折扣() {
        assertThat(CouponService.calculateDiscount(fullReduce(0L), 10000L)).isZero();
    }

    @Test
    void 折扣文案带封顶说明() {
        assertThat(CouponService.discountLabel(discount(85, 0L))).isEqualTo("8.5 折");
        assertThat(CouponService.discountLabel(discount(85, 2000L))).isEqualTo("8.5 折（最高减 20 元）");
        assertThat(CouponService.discountLabel(fullReduce(500L))).isNull();
    }
}
