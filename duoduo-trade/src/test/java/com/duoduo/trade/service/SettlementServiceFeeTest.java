package com.duoduo.trade.service;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.mapper.SellerDebtMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * E22 · 佣金口径单测。
 *
 * <h2>为什么必须单独测这一段</h2>
 * 冒烟脚本（{@code settlement-smoke.ps1}）用的是**自营夹具商品**，
 * 而 E22 之后自营是**免佣**的 —— 也就是说，冒烟能证明"自营不抽佣"，
 * 却**证明不了**"第三方按 6% 抽佣"这条真正的收入规则。
 * 那一条如果写错了（比如取整方向反了、或者漏乘 10000），
 * 在冒烟里会表现为"佣金恰好是 0"而被当成正常 —— 一个纯粹的静默错误。
 *
 * <p>所以这里把两种口径与取整规则一起钉住，作为冒烟之外的独立证据。
 */
class SettlementServiceFeeTest {

    /** 与生产默认一致：600 bp = 6% */
    private static final int BPS_6_PERCENT = 600;

    private static SettlementService serviceWith(int bps, int holdDays) throws Exception {
        SettlementService service = new SettlementService(
                mock(SettlementMapper.class),
                mock(SellerDebtMapper.class),
                mock(SnowflakeIdGenerator.class));
        setField(service, "platformFeeBps", bps);
        setField(service, "settlementHoldDays", holdDays);
        return service;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("第三方订单：按 6% 抽佣，且取整采用四舍五入（+5000 后整除）")
    void thirdPartyPaysSixPercent() throws Exception {
        SettlementService service = serviceWith(BPS_6_PERCENT, 7);

        // 39700 分 × 6% = 2382.0 分 → 2382（与冒烟里的期望值同一个算法）
        assertThat(service.platformFeeOf(710000000000000002L, 39700L)).isEqualTo(2382L);
        // 100 元整：10000 × 6% = 600 ✓ 整数无歧义
        assertThat(service.platformFeeOf(710000000000000002L, 10000L)).isEqualTo(600L);
        // 取整方向：1 分 × 6% = 0.06 → 0（+5000 后 5000/10000 = 0）
        assertThat(service.platformFeeOf(710000000000000002L, 1L)).isZero();
        // 0.5 分的边界：83 分 × 6% = 4.98 → 5；84 分 × 6% = 5.04 → 5
        assertThat(service.platformFeeOf(710000000000000002L, 83L)).isEqualTo(5L);
        assertThat(service.platformFeeOf(710000000000000002L, 84L)).isEqualTo(5L);
    }

    @Test
    @DisplayName("★ 平台自营（seller_id = 0）免佣 —— 佣金是左手倒右手，收了只会让指标虚增")
    void platformSelfIsFeeFree() throws Exception {
        SettlementService service = serviceWith(BPS_6_PERCENT, 7);
        assertThat(service.platformFeeOf(0L, 39700L)).isZero();
        assertThat(service.platformFeeOf(0L, 100000000L)).isZero();
    }

    @Test
    @DisplayName("费率为 0 → 全平台免佣（回滚开关）；金额不合法 → 0 而不是抛异常")
    void zeroRateAndInvalidAmount() throws Exception {
        SettlementService freeOfCharge = serviceWith(0, 7);
        assertThat(freeOfCharge.platformFeeOf(710000000000000002L, 39700L)).isZero();

        SettlementService service = serviceWith(BPS_6_PERCENT, 7);
        assertThat(service.platformFeeOf(710000000000000002L, 0L)).isZero();
        assertThat(service.platformFeeOf(710000000000000002L, -100L)).isZero();
        assertThat(service.platformFeeOf(null, 39700L)).as("sellerId 为 null 时按第三方处理（失败方向指向「照收」）").isEqualTo(2382L);
    }

    @Test
    @DisplayName("大额订单不溢出：金额用 long 参与乘法（int 在 2 万元 × 100 的量级就会溢出）")
    void largeAmountDoesNotOverflow() throws Exception {
        SettlementService service = serviceWith(BPS_6_PERCENT, 7);
        long amount = 2_000_000_00L; // 2 亿元（以分为单位）
        long fee = service.platformFeeOf(710000000000000002L, amount);
        assertThat(fee).isPositive();
        assertThat(fee).isEqualTo((amount * 600L + 5000L) / 10000L);
        assertThat(fee).isLessThan(amount);
    }

    @Test
    @DisplayName("售后期天数与费率一样是「唯一口径」：service 暴露的读数就是配置值")
    void holdDaysIsExposedConsistently() throws Exception {
        assertThat(serviceWith(600, 7).settlementHoldDays()).isEqualTo(7);
        assertThat(serviceWith(600, 3).settlementHoldDays()).isEqualTo(3);
        assertThat(serviceWith(600, 0).settlementHoldDays()).isZero();
    }
}
