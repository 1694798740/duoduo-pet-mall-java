package com.duoduo.ai.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每 IP 固定窗口限流（A9 验收③的「限流」半边）。
 *
 * <p>为什么不用 Redis：单实例内存限流保护的是**本进程的 LLM 配额**，
 * 实例重启清零的代价只是「窗口内多放几个请求」，不涉及资金与一致性；
 * 为它引入一个外部依赖是反向的性价比。多实例部署时应换成网关层限流
 * （那个负责全站），而不是每个实例自己搬一份 Redis。
 *
 * <p>固定窗口的边界抖动（两窗口交界处可能放进 2 倍）对「防刷」够用；
 * 精确令牌桶留给将来真有需要时再换，接口已经收敛在 {@link #allow}。
 */
@Component
public class IpRateLimiter {

    private final int limitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public IpRateLimiter(@Value("${duoduo.ai.rate-limit-per-minute:20}") int limitPerMinute) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
    }

    /** 本分钟是否还放行该 IP；放行则计数 +1 */
    public boolean allow(String clientKey) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(clientKey, (k, old) ->
                old == null || old.minute != minute ? new Window(minute) : old);
        return w.counter.incrementAndGet() <= limitPerMinute;
    }

    public int limit() {
        return limitPerMinute;
    }

    private static final class Window {
        final long minute;
        final AtomicLong counter = new AtomicLong();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
