package com.duoduo.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流（第五十七轮加固、第五十八轮补多实例）：单键固定窗口 + **全站总配额**。
 *
 * <h2>计数放哪儿：有 Redis 就用，没有就退回本机内存</h2>
 * 第五十七轮的口径是「多实例时换网关层限流，不要每个实例自己搬一份 Redis」。
 * 这条口径对「全站洪水」仍然成立（那是网关的职责），但它盖不住另一件事：
 * <b>LLM 配额是按调用总量付费的</b>，部署 3 个实例时，本机内存计数会让
 * 实际消耗变成配额的 3 倍 —— 而这个偏差不会报错，只在账单上出现。
 *
 * <p>所以这里做成<b>可选分布式计数</b>：容器里有 Redis 就用它（所有实例共用一个窗口），
 * 没有就退回内存（单实例部署时完全等价）。判据是「能不能真的写进去」，
 * 而不是「配置文件里有没有」—— 配了却连不上时**自动降级**，绝不让限流把整个对话拖垮。
 *
 * <h2>降级后为什么要「冷却」而不是每次都试</h2>
 * Redis 不可用时若每次请求都去连一次，超时会**叠加到每个对话的响应时间上** ——
 * 用户感到的是「AI 变慢了」，而这本来只是为了统计个数字。
 * 冷却期内直接走内存，60 秒后再探一次（Redis 恢复后自动切回来）。
 *
 * <h2>两道判断都要计数</h2>
 * 单键与全局两道都要过，且<b>两者都要计数</b>：若单键超限就提前返回而不计全局，
 * 「被单键挡住的那部分流量」不消耗全局额度，全局配额的实际含义就说不清了
 * （这是限流实现里常见的口径混乱）。
 */
@Component
public class IpRateLimiter {

    /** 全局配额的键名（不是真实客户端键，不会被任何请求头影响） */
    public static final String GLOBAL_KEY = "__global__";

    /** Redis 不可用后的冷却期（毫秒）：冷却内直接走内存，不再每次尝试 */
    private static final long REDIS_COOLDOWN_MILLIS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(IpRateLimiter.class);

    private final int limitPerMinute;
    private final int globalLimitPerMinute;
    private final StringRedisTemplate redis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long redisBrokenUntil = 0L;

    public IpRateLimiter(@Value("${duoduo.ai.rate-limit-per-minute:20}") int limitPerMinute,
                         @Value("${duoduo.ai.global-rate-limit-per-minute:300}") int globalLimitPerMinute,
                         ObjectProvider<StringRedisTemplate> redisProvider) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.globalLimitPerMinute = Math.max(1, globalLimitPerMinute);
        // getIfAvailable：没有 Redis 时（本地 / 单实例部署）redis 为 null，走内存
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /** 当前计数模式（排障用：一眼看出是「全站共享窗口」还是「单机窗口」） */
    public String mode() {
        return redis != null && System.currentTimeMillis() >= redisBrokenUntil ? "redis" : "memory";
    }

    /**
     * 该键是否放行。
     *
     * @see #allow(String)
     */
    public boolean allow(String clientKey) {
        long minute = System.currentTimeMillis() / 60_000L;
        String key = (clientKey == null || clientKey.isBlank()) ? "unknown" : clientKey;
        boolean keyOk = hit(key, minute) <= limitPerMinute;
        boolean globalOk = hit(GLOBAL_KEY, minute) <= globalLimitPerMinute;
        return keyOk && globalOk;
    }

    private long hit(String key, long minute) {
        if (redis != null && System.currentTimeMillis() >= redisBrokenUntil) {
            try {
                Long value = redisCount(key, minute);
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException ex) {
                redisBrokenUntil = System.currentTimeMillis() + REDIS_COOLDOWN_MILLIS;
                log.warn("限流计数走 Redis 失败，60 秒内退回本机内存: {}", ex.getMessage());
            }
        }
        return memoryHit(key, minute);
    }

    /**
     * Redis 计数（多实例共享同一个窗口）。
     *
     * <p>键里带分钟数，等价于固定窗口；过期时间给到 2 分钟（窗口结束后还留一点余量，
     * 便于排障时回看上一分钟的消耗），不是靠它做滑动窗口。
     */
    private Long redisCount(String key, long minute) {
        String redisKey = "duoduo:ai:rl:" + minute + ":" + key;
        Long value = redis.opsForValue().increment(redisKey);
        if (value != null && value == 1L) {
            redis.expire(redisKey, Duration.ofMinutes(2));
        }
        return value;
    }

    private long memoryHit(String key, long minute) {
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute != minute ? new Window(minute) : old);
        return w.counter.incrementAndGet();
    }

    public int limit() {
        return limitPerMinute;
    }

    public int globalLimit() {
        return globalLimitPerMinute;
    }

    private static final class Window {
        final long minute;
        final AtomicLong counter = new AtomicLong();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
