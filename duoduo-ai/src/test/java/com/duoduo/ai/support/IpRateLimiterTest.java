package com.duoduo.ai.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流计数单测（第五十八轮 · AI 加强项第 20 条：多实例不再各算各的）。
 *
 * <h2>为什么这条值得测</h2>
 * 「3 个实例 → 配额被用掉 3 倍」不会报错、不会告警，只在账单上出现；
 * 而修它引入的 Redis 一旦不可用，又不能把对话拖垮。
 * 所以真正要守住的是两个方向：<b>有 Redis 时确实共用窗口</b>，
 * <b>Redis 出问题时确实自动退回内存且不再反复重试</b>。
 */
class IpRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    private static StringRedisTemplate redisReturning(long... values) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        if (values.length == 1) {
            when(ops.increment(anyString())).thenReturn(values[0]);
        } else {
            when(ops.increment(anyString())).thenReturn(values[0], java.util.Arrays.stream(values)
                    .skip(1).boxed().toArray(Long[]::new));
        }
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    @Test
    void 没有Redis时走内存_窗口满则拒绝() {
        IpRateLimiter limiter = new IpRateLimiter(3, 100, null);

        assertThat(limiter.mode()).isEqualTo("memory");
        assertThat(limiter.allow("u:1")).isTrue();
        assertThat(limiter.allow("u:1")).isTrue();
        assertThat(limiter.allow("u:1")).isTrue();
        assertThat(limiter.allow("u:1")).isFalse();
        // 换一个键不受上一个键影响
        assertThat(limiter.allow("u:2")).isTrue();
    }

    @Test
    void 有Redis时计数走Redis且给键设过期() {
        StringRedisTemplate redis = redisReturning(1L, 2L);
        IpRateLimiter limiter = new IpRateLimiter(5, 100, providerOf(redis));

        assertThat(limiter.mode()).isEqualTo("redis");
        assertThat(limiter.allow("u:1")).isTrue();

        verify(redis.opsForValue(), times(2)).increment(anyString());
        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    void 多实例共享窗口_第二台的计数接着第一台走() {
        // 模拟两个实例共用一个 Redis：本实例第一次请求就拿到计数 5
        // （说明另一个实例已经用掉前 4 次）—— 内存计数在这里会误以为只用了 1 次
        StringRedisTemplate redis = redisReturning(5L, 6L);
        IpRateLimiter limiter = new IpRateLimiter(5, 100, providerOf(redis));

        assertThat(limiter.allow("u:1")).isTrue();  // 计数 5 → 刚好到上限，放行
        assertThat(limiter.allow("u:1")).isFalse(); // 计数 6 → 超限
    }

    @Test
    void Redis报错时退回内存并且不再反复重试() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("连接失败"));
        IpRateLimiter limiter = new IpRateLimiter(5, 100, providerOf(redis));

        assertThat(limiter.allow("u:1")).isTrue();  // 降级后仍然放行
        assertThat(limiter.mode()).isEqualTo("memory");

        int before = org.mockito.Mockito.mockingDetails(ops).getInvocations().size();
        limiter.allow("u:1");
        limiter.allow("u:1");
        int after = org.mockito.Mockito.mockingDetails(ops).getInvocations().size();
        // 冷却期内不再打 Redis（否则每个对话都要多等一次超时）
        assertThat(after).isEqualTo(before);
    }

    @Test
    void 全局配额独立于单键生效() {
        IpRateLimiter limiter = new IpRateLimiter(100, 3, null);

        assertThat(limiter.allow("u:1")).isTrue();
        assertThat(limiter.allow("u:2")).isTrue();
        assertThat(limiter.allow("u:3")).isTrue();
        // 单键都没超限，但总量到了上限 —— 全局是最后一道闸
        assertThat(limiter.allow("u:4")).isFalse();
    }
}
