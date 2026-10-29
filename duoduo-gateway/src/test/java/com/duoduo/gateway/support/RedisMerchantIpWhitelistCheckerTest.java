package com.duoduo.gateway.support;

import com.duoduo.common.security.ip.MerchantIpWhitelist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家 IP 白名单判定单测（E-06）。
 *
 * <p>直接测 {@code decide}：它把「Redis 里读到的值」映射成「放行 / 拒绝」，
 * 是本功能唯一的判定点。三种边界必须钉死 ——
 * 键不存在（未启用）、键为空（启用但没规则）、对端地址缺失。
 */
class RedisMerchantIpWhitelistCheckerTest {

    @Test
    void 键不存在表示未启用_放行() {
        assertThat(RedisMerchantIpWhitelistChecker.decide(null, "1.2.3.4", 5001L)).isTrue();
    }

    @Test
    void 键存在但为空_失败关闭() {
        assertThat(RedisMerchantIpWhitelistChecker.decide("", "1.2.3.4", 5001L)).isFalse();
        assertThat(RedisMerchantIpWhitelistChecker.decide(",", "1.2.3.4", 5001L)).isFalse();
    }

    @Test
    void 命中规则放行_未命中拒绝() {
        String raw = MerchantIpWhitelist.format(List.of("10.0.0.0/8", "203.0.113.7"));
        assertThat(RedisMerchantIpWhitelistChecker.decide(raw, "10.9.9.9", 5001L)).isTrue();
        assertThat(RedisMerchantIpWhitelistChecker.decide(raw, "203.0.113.7", 5001L)).isTrue();
        assertThat(RedisMerchantIpWhitelistChecker.decide(raw, "8.8.8.8", 5001L)).isFalse();
    }

    @Test
    void 取不到对端地址时拒绝() {
        String raw = MerchantIpWhitelist.format(List.of("10.0.0.0/8"));
        assertThat(RedisMerchantIpWhitelistChecker.decide(raw, null, 5001L)).isFalse();
        assertThat(RedisMerchantIpWhitelistChecker.decide(raw, " ", 5001L)).isFalse();
    }

    @Test
    void 键名与格式只允许一处定义() {
        assertThat(MerchantIpWhitelist.key(501L)).isEqualTo("duoduo:merchant:ipwl:501");
        assertThat(MerchantIpWhitelist.key(null)).isNull();
        assertThat(MerchantIpWhitelist.format(List.of(" 1.2.3.4 ", "", "10.0.0.0/8"))).isEqualTo("1.2.3.4,10.0.0.0/8");
        assertThat(MerchantIpWhitelist.parse("1.2.3.4, 10.0.0.0/8")).containsExactly("1.2.3.4", "10.0.0.0/8");
        assertThat(MerchantIpWhitelist.parse(null)).isEmpty();
    }
}
