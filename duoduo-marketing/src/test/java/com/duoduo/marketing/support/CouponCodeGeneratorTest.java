package com.duoduo.marketing.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 券码生成单测（第五十八轮 · 营销域单元测试补齐）。
 *
 * <p>券码为什么不能用雪花 id、为什么字符表里要去掉 {@code 0/O/1/I}，
 * 在 {@link CouponCodeGenerator} 的类注释里已写明：券码最常见的输入场景是
 * **客服在电话里报给你听**，易混字符会直接变成输错，而输错的报错是「券不存在」——
 * 用户会以为券有问题，排查方向被彻底带偏。这里用断言把这条约定固定住。
 */
class CouponCodeGeneratorTest {

    @Test
    void 长度与前缀固定() {
        String code = CouponCodeGenerator.next();
        assertThat(code).startsWith("CP");
        assertThat(code).hasSize(16); // CP + 14 位
    }

    /** ★ 易混字符表：0 O 1 I 一律不出现 */
    @Test
    void 不含易混字符() {
        for (int i = 0; i < 200; i++) {
            String code = CouponCodeGenerator.next();
            assertThat(code).doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("I");
        }
    }

    @Test
    void 券码不是数字_规避JS大整数精度问题() {
        // 券码一旦是纯数字，前端 Number 处理长数字就会丢精度（INV-16）
        for (int i = 0; i < 50; i++) {
            String code = CouponCodeGenerator.next();
            assertThat(code).isNotEqualTo("0");
            assertThat(code.substring(2)).doesNotMatch("\\d+");
        }
    }

    @Test
    void 批量生成不重复() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            codes.add(CouponCodeGenerator.next());
        }
        assertThat(codes).hasSize(500);
    }
}
