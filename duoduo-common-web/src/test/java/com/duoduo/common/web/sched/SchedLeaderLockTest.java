package com.duoduo.common.web.sched;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务选主锁单测（第五十八轮 · P0-2）。
 *
 * <h2>为什么必须钉住「顺序」</h2>
 * 抢占分两步：先 UPDATE（续约 / 抢占过期锁），再 INSERT（首次创建）。
 * <b>顺序不能反</b>：反过来就是「先 SELECT 再 INSERT」的经典并发漏洞 ——
 * 两个实例都看到「没有这行」而双双写入，唯一键成了摆设。
 * 这里显式断言「UPDATE 影响 0 行时才走 INSERT」「UPDATE 影响 1 行时绝不 INSERT」。
 *
 * <h2>「撞主键 → 放弃」必须被覆盖</h2>
 * 撞 {@code DuplicateKeyException} 是<b>正常的多实例协同</b>而不是故障，
 * 若哪天被改成「抛异常」，调度线程里就会因为一次未捕获异常而**后续轮次全部静默停摆**
 * （那是本项目反复强调过的最难发现的故障形态）。
 */
class SchedLeaderLockTest {

    private static SchedLeaderLock lock(JdbcTemplate jdbc, boolean enabled) {
        return new SchedLeaderLock(jdbc, "host-a#100", enabled);
    }

    @Test
    void 首次运行走INSERT并抢到锁() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.update(startsWith("INSERT"), any(), any(), any(), any())).thenReturn(1);

        assertThat(lock(jdbc, true).tryAcquire("order-sweep", 60_000L)).isTrue();
        verify(jdbc).update(startsWith("INSERT"), any(), any(), any(), any());
    }

    @Test
    void 续约成功时不再INSERT() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(lock(jdbc, true).tryAcquire("order-sweep", 60_000L)).isTrue();
        verify(jdbc, never()).update(startsWith("INSERT"), any(), any(), any(), any());
    }

    @Test
    void 别人持锁未过期_撞主键_本轮放弃且不抛异常() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.update(startsWith("INSERT"), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'order-sweep'"));

        // 关键：返回 false 而不是往外抛 —— 抛出去会让整个调度线程后续轮次静默停摆
        assertThat(lock(jdbc, true).tryAcquire("order-sweep", 60_000L)).isFalse();
    }

    @Test
    void 关闭选主时一律放行_交给外部调度() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThat(lock(jdbc, false).tryAcquire("order-sweep", 60_000L)).isTrue();
        verify(jdbc, never()).update(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 数据库异常时放行而不是让补偿任务停摆() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));

        // 补偿任务停摆的后果（券永久锁死 / 库存永不释放）远重于多跑一遍
        assertThat(lock(jdbc, true).tryAcquire("coupon-release-sweep", 60_000L)).isTrue();
    }

    @Test
    void UPDATE的WHERE必须带过期或本人持有两个条件() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(), any(), any(), any(), any(), any())).thenReturn(1);

        lock(jdbc, true).tryAcquire("search-sync", 60_000L);

        // 少了「或本人持有」，同一个实例第二轮就续不上约；少了「过期」判断，锁永远释放不掉
        verify(jdbc).update(eq("UPDATE t_sched_leader SET owner = ?, expire_at = ?, updated_at = ? "
                        + "WHERE name = ? AND (expire_at < ? OR owner = ?)"),
                any(), any(), any(), any(), any(), any());
    }
}
