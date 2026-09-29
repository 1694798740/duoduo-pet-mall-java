package com.duoduo.ai.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流（第五十七轮加固）：单键固定窗口 + **全站总配额**。
 *
 * <h2>为什么不用 Redis
 * 单实例内存限流保护的是**本进程的 LLM 配额**，实例重启清零的代价只是「窗口内多放几个请求」，
 * 不涉及资金与一致性；为它引入一个外部依赖是反向的性价比。多实例部署时应换成网关层限流
 * （那个负责全站），而不是每个实例自己搬一份 Redis。
 *
 * <h2>第五十七轮补了什么、为什么
 * <ol>
 *   <li><b>限流键的来源换了</b>（改动在 {@code AiController}）：此前取
 *       {@code X-Forwarded-For} 的**首段**，而那是客户端可以自己写的头 ——
 *       换句话说，换一个假 IP 就等于绕过限流，而 LLM 配额是真金白银。
 *       现在按「登录用 userId、未登录用网关写入的可信 IP」取键；</li>
 *   <li><b>加了全站总配额</b>（{@link #GLOBAL_KEY}）：单键限流防不住「分布式换键刷」——
 *       对方有 1000 个 IP 就能把总消耗放大 1000 倍。总配额是最后一道闸：
 *       无论怎么绕，**每分钟的调用总数有上界**。</li>
 * </ol>
 *
 * <p><b>固定窗口的边界抖动</b>（两窗口交界处可能放进 2 倍）对「防刷」够用；
 * 精确令牌桶留给将来真有需要时再换，接口已经收敛在 {@link #allow}。
 */
@Component
public class IpRateLimiter {

    /** 全局配额的键名（不是真实客户端键，不会被任何请求头影响） */
    public static final String GLOBAL_KEY = "__global__";

    private final int limitPerMinute;
    private final int globalLimitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public IpRateLimiter(@Value("${duoduo.ai.rate-limit-per-minute:20}") int limitPerMinute,
                         @Value("${duoduo.ai.global-rate-limit-per-minute:300}") int globalLimitPerMinute) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.globalLimitPerMinute = Math.max(1, globalLimitPerMinute);
    }

    /**
     * 该键是否放行。
     *
     * <p>两道判断都要过：先看单键，再看全局。顺序无关紧要，但**两者都要计数** ——
     * 若单键超限就提前返回而不计全局，那么「被单键挡住的那部分流量」不消耗全局额度，
     * 全局配额的实际含义就说不清了（这是限流实现里常见的口径混乱）。
     */
    public boolean allow(String clientKey) {
        long minute = System.currentTimeMillis() / 60_000L;
        String key = (clientKey == null || clientKey.isBlank()) ? "unknown" : clientKey;
        boolean keyOk = hit(key, minute) <= limitPerMinute;
        boolean globalOk = hit(GLOBAL_KEY, minute) <= globalLimitPerMinute;
        return keyOk && globalOk;
    }

    private long hit(String key, long minute) {
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
