package com.duoduo.common.security.ip;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP 规则解析与匹配单测（E-06）。
 *
 * <p>为什么值得单独测：这套规则同时决定「网关放不放行」与「保存时会不会把店主锁在门外」，
 * 而它没有任何运行时异常可以发现错误 —— 匹配写错的唯一表现是「有人进不去」或「谁都能进」。
 */
class IpRulesTest {

    @Test
    void 精确地址匹配() {
        assertThat(IpRules.matches("203.0.113.7", "203.0.113.7")).isTrue();
        assertThat(IpRules.matches("203.0.113.7", "203.0.113.8")).isFalse();
    }

    @Test
    void IPv4_CIDR匹配() {
        assertThat(IpRules.matches("10.0.0.0/8", "10.9.8.7")).isTrue();
        assertThat(IpRules.matches("192.168.1.0/24", "192.168.1.44")).isTrue();
        assertThat(IpRules.matches("192.168.1.0/24", "192.168.2.44")).isFalse();
        assertThat(IpRules.matches("0.0.0.0/0", "8.8.8.8")).isTrue();
    }

    @Test
    void 前缀通配匹配() {
        assertThat(IpRules.matches("192.168.1.*", "192.168.1.99")).isTrue();
        assertThat(IpRules.matches("192.168.1.*", "192.168.10.1")).isFalse();
    }

    @Test
    void 空值与非法规则一律不匹配() {
        assertThat(IpRules.matches(null, "1.2.3.4")).isFalse();
        assertThat(IpRules.matches("", "1.2.3.4")).isFalse();
        assertThat(IpRules.matches("1.2.3.4", null)).isFalse();
        assertThat(IpRules.matches("不是IP", "1.2.3.4")).isFalse();
        assertThat(IpRules.matches("1.2.3.999", "1.2.3.999")).as("字符串精确比对仍然成立").isTrue();
        assertThat(IpRules.matches("10.0.0.0/33", "10.0.0.1")).as("越界掩码不匹配").isFalse();
    }

    @Test
    void 名单任一命中即允许() {
        List<String> rules = List.of("10.0.0.0/8", "203.0.113.7");
        assertThat(IpRules.matchesAny(rules, "10.1.2.3")).isTrue();
        assertThat(IpRules.matchesAny(rules, "203.0.113.7")).isTrue();
        assertThat(IpRules.matchesAny(rules, "8.8.8.8")).isFalse();
        assertThat(IpRules.matchesAny(List.of(), "8.8.8.8")).isFalse();
    }

    @Test
    void 保存时的形状校验() {
        assertThat(IpRules.isWellFormed("203.0.113.7")).isTrue();
        assertThat(IpRules.isWellFormed("10.0.0.0/8")).isTrue();
        assertThat(IpRules.isWellFormed("192.168.1.*")).isTrue();
        assertThat(IpRules.isWellFormed("2001:db8::1")).isTrue();

        assertThat(IpRules.isWellFormed("")).isFalse();
        assertThat(IpRules.isWellFormed(" * ")).as("裸通配等于放行全部，白名单失去意义").isFalse();
        assertThat(IpRules.isWellFormed("localhost")).isFalse();
        assertThat(IpRules.isWellFormed("https://example.com")).isFalse();
        assertThat(IpRules.isWellFormed("10.0.0.0/99")).isFalse();
        assertThat(IpRules.isWellFormed("1.2.3")).isFalse();
    }
}
