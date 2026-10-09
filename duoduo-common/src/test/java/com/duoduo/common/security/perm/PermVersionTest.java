package com.duoduo.common.security.perm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E17 · 权限版本共享口径的单测。
 *
 * <h2>为什么这几个看起来很傻的断言值得写</h2>
 * {@code PermVersion} 是"写入方在用户服务、读取方在网关"的共同契约，而这个契约
 * <b>错了不会报错</b>：键名对不上，两边都各自正常工作，只是"角色变更即时生效"这件事
 * 永远不发生 —— 与引入这个机制要解决的问题一模一样，却更难排查（因为代码看起来是对的）。
 * 所以这里刻意把键名、TTL、解析规则钉成<b>字面量</b>：改动它必须显式改测试，
 * 而"显式改测试"这一步就是让人停下来想一次"线上那些键还要不要认"。
 */
class PermVersionTest {

    @Test
    void 键名拼法固定_改动即红() {
        // 前缀一旦改名，Redis 里所有存量键立刻读不到 → 版本校验静默失效（放行）
        assertThat(PermVersion.KEY_PREFIX).isEqualTo("duoduo:perm:ver:");
        assertThat(PermVersion.key(710000000000000001L)).isEqualTo("duoduo:perm:ver:710000000000000001");
    }

    @Test
    void 键名按用户隔离_不会互相覆盖() {
        assertThat(PermVersion.key(1L)).isNotEqualTo(PermVersion.key(2L));
        // 与 AI 限流的键前缀不能撞车：撞了会变成"谁的版本被别人覆盖"
        assertThat(PermVersion.KEY_PREFIX).doesNotStartWith("duoduo:ai:");
    }

    @Test
    void 空用户id不拼键() {
        // 拼出 "duoduo:perm:ver:null" 这种键比返回 null 危险：它会成为一个所有人共用的假键
        assertThat(PermVersion.key(null)).isNull();
    }

    @Test
    void 解析容忍脏值_一律判为未知而不是抛异常() {
        // 这个值来自缓存：被人手工改过、残留了别的格式，都不该让鉴权链路崩掉
        assertThat(PermVersion.parse("3")).isEqualTo(3L);
        assertThat(PermVersion.parse(" 7 ")).isEqualTo(7L);
        assertThat(PermVersion.parse("0")).isEqualTo(0L);
        assertThat(PermVersion.parse(null)).isNull();
        assertThat(PermVersion.parse("")).isNull();
        assertThat(PermVersion.parse("   ")).isNull();
        assertThat(PermVersion.parse("abc")).isNull();
        assertThat(PermVersion.parse("1.5")).isNull();
    }

    @Test
    void 初始版本与DDL默认值一致() {
        // DDL 里是 DEFAULT 0。两边不一致会出现"库里是 0、代码以为从 1 开始"，
        // 表现是新账号的第一个请求就被判版本过期
        assertThat(PermVersion.INITIAL).isZero();
        assertThat(PermVersion.orInitial(null)).isEqualTo(PermVersion.INITIAL);
        assertThat(PermVersion.orInitial(5L)).isEqualTo(5L);
    }

    @Test
    void 格式化与解析可往返() {
        assertThat(PermVersion.parse(PermVersion.format(42L))).isEqualTo(42L);
        assertThat(PermVersion.format(null)).isNull();
    }

    @Test
    void 存活时间与刷新令牌同量级_不能是分钟级() {
        // 副本过期得太早，就会出现"令牌带版本、Redis 没有记录 → 放行"的窗口，
        // 而那个窗口正好落在"刚被降权、最需要拦住"的时刻
        assertThat(PermVersion.TTL).isGreaterThanOrEqualTo(java.time.Duration.ofDays(1));
    }
}
