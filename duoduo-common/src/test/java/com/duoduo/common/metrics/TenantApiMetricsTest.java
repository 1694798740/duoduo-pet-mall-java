package com.duoduo.common.metrics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E24 · 租户接口指标键口径的单测。
 *
 * <h2>为什么这些"看起来很傻"的断言值得写</h2>
 * 本类是"写入方在网关、读取方在交易域"的共同契约，而这个契约**错了不会报错**：
 * 键名或字段名对不上时，两边都各自正常工作，只是面板上的接口错误率永远读到 0 ——
 * 与"这个租户确实没出错"得到完全相同的空结果。因此这里把键名、字段名、TTL 下限
 * 钉成字面量：改动它必须显式改测试，而"显式改测试"就是让人停下来想一次
 * "Redis 里那些存量键还要不要认"。
 */
class TenantApiMetricsTest {

    @Test
    void 键名拼法固定_改动即红() {
        // 前缀改名 → 网关写新键、交易域读旧键 → 接口错误率静默归零
        assertThat(TenantApiMetrics.PREFIX).isEqualTo("duoduo:api:stat:");
        assertThat(TenantApiMetrics.dayKey(LocalDate.of(2026, 10, 12)))
                .isEqualTo("duoduo:api:stat:2026-10-12");
    }

    @Test
    void 键值租户隔离_同一租户不同档位不互相覆盖() {
        assertThat(TenantApiMetrics.field(1L, TenantApiMetrics.FIELD_TOTAL)).isEqualTo("1:total");
        assertThat(TenantApiMetrics.field(1L, TenantApiMetrics.FIELD_SERVER_ERROR)).isEqualTo("1:server_error");
        assertThat(TenantApiMetrics.field(1L, TenantApiMetrics.FIELD_CLIENT_ERROR)).isEqualTo("1:client_error");
        // 同一天不同租户必须落在不同字段上，否则 A 店的错误会算到 B 店头上
        assertThat(TenantApiMetrics.field(1L, TenantApiMetrics.FIELD_TOTAL))
                .isNotEqualTo(TenantApiMetrics.field(2L, TenantApiMetrics.FIELD_TOTAL));
    }

    @Test
    void 自营租户0是有效值_必须能拼出字段() {
        // 0 = 平台自营（E20 口径），拿它当"没租户"会让自营流量无处统计
        assertThat(TenantApiMetrics.field(0L, TenantApiMetrics.FIELD_TOTAL)).isEqualTo("0:total");
        assertThat(TenantApiMetrics.tenantOf("0:total")).isZero();
    }

    @Test
    void 缺租户id宁可炸_不拼出跨租户共用的假字段() {
        assertThatThrownBy(() -> TenantApiMetrics.field(null, TenantApiMetrics.FIELD_TOTAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantApiMetrics.field(-1L, TenantApiMetrics.FIELD_TOTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 未知档位宁可炸_不写出永远读不到的新字段() {
        // 拼错的档位（servers_error）写进去不报错、读侧也看不到 —— 表现就是"错误率恒为 0"
        assertThatThrownBy(() -> TenantApiMetrics.field(1L, "servers_error"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantApiMetrics.field(1L, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 档位大小写与空白容错_读侧不能因写入期的大小写而读不到() {
        assertThat(TenantApiMetrics.normalizeFacet(" Total ")).isEqualTo(TenantApiMetrics.FIELD_TOTAL);
        assertThat(TenantApiMetrics.normalizeFacet("SERVER_ERROR"))
                .isEqualTo(TenantApiMetrics.FIELD_SERVER_ERROR);
    }

    @Test
    void 解析回读_键与字段都能还原() {
        LocalDate day = LocalDate.of(2026, 10, 12);
        assertThat(TenantApiMetrics.parseDay(TenantApiMetrics.dayKey(day))).isEqualTo(day);
        assertThat(TenantApiMetrics.isDayKey(TenantApiMetrics.dayKey(day))).isTrue();

        String field = TenantApiMetrics.field(710000000000000001L, TenantApiMetrics.FIELD_SERVER_ERROR);
        assertThat(TenantApiMetrics.tenantOf(field)).isEqualTo(710000000000000001L);
        assertThat(TenantApiMetrics.facetOf(field)).isEqualTo(TenantApiMetrics.FIELD_SERVER_ERROR);
    }

    @Test
    void 脏键与脏字段一律判为不可用_而不是抛异常或算到自营头上() {
        // 手工建的键 / 别人的键：不能因为匹配了前缀就当成合法日期
        assertThat(TenantApiMetrics.parseDay("duoduo:api:stat:test")).isNull();
        assertThat(TenantApiMetrics.parseDay("duoduo:perm:ver:1")).isNull();
        assertThat(TenantApiMetrics.isDayKey("duoduo:api:stat:2026-13-45")).isFalse();
        assertThat(TenantApiMetrics.dayKey(null)).isNull();

        // ★ 解析失败必须返回 -1，**不能返回 0** —— 0 是"平台自营"这个真实租户，
        // 把它当失败会让一条脏字段被算进自营的账上
        assertThat(TenantApiMetrics.tenantOf("abc:total")).isEqualTo(-1L);
        assertThat(TenantApiMetrics.tenantOf(":total")).isEqualTo(-1L);
        assertThat(TenantApiMetrics.tenantOf(null)).isEqualTo(-1L);
        assertThat(TenantApiMetrics.facetOf("1:unknown")).isNull();
        assertThat(TenantApiMetrics.facetOf("noseparator")).isNull();
    }

    @Test
    void TTL必须覆盖面板允许的最大窗口() {
        // 键过期后读到的空结果与"那天一次没调用"完全一样 → 接口错误率会安静地变成 0。
        // 面板窗口上限见 TenantHealthService.MAX_DAYS（30），这里钉住"至少留出余量"。
        assertThat(TenantApiMetrics.KEEP_DAYS).isGreaterThanOrEqualTo(31);
    }
}
