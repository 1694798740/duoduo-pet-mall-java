package com.duoduo.ai.support;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 管家关键指标（2026-10-29 · B5）。
 *
 * <h2>为什么用 AtomicLong + 注册，而不是直接持有 Counter</h2>
 * Micrometer 的 Counter 必须挂在 MeterRegistry 上；而单元测试与本地裸跑时
 * 注册表可能不存在。这里把「计数」与「上报」解耦：计数永远在内存里累加，
 * 有注册表时再把它们注册成计数器/仪表盘指标 —— 业务代码不需要判空。
 *
 * <h2>四个必需指标</h2>
 * <ul>
 *   <li>{@code ai_tool_failure_rate}：工具失败率（失败/总调用，Gauge）；</li>
 *   <li>{@code ai_llm_timeout_count}：模型超时次数；</li>
 *   <li>{@code ai_guest_quota_exceeded_count}：游客轮次用尽拦截次数；</li>
 *   <li>{@code ai_empty_card_count}：本应出卡却装配为空的次数。</li>
 * </ul>
 * 另附 {@code ai_tool_calls_total} / {@code ai_tool_failures_total}（比率的分子分母，
 * 便于按时间窗口算真实速率）与 {@code ai_llm_duration_seconds}（排障时先看它）。
 *
 * <h2>为什么失败率用 Gauge 而不是 Counter</h2>
 * 比率不是单调量，Counter 语义只适合累计次数。Gauge 直接暴露当前比值，
 * 看板与告警都能一次读到位；分母单独暴露，避免「比率高但其实是总量太小」的误判。
 */
@Component
public class AiMetrics {

    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolFailures = new AtomicLong();
    private final AtomicLong llmTimeouts = new AtomicLong();
    private final AtomicLong llmFallbacks = new AtomicLong();
    private final AtomicLong quotaExceeded = new AtomicLong();
    private final AtomicLong emptyCards = new AtomicLong();

    /** 模型调用耗时；没有注册表时为 null（测试环境不记录） */
    private final Timer llmTimer;

    public AiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            this.llmTimer = null;
            return;
        }
        FunctionCounter.builder("ai_tool_calls_total", toolCalls, AtomicLong::doubleValue)
                .description("AI 工具调用总次数")
                .register(registry);
        FunctionCounter.builder("ai_tool_failures_total", toolFailures, AtomicLong::doubleValue)
                .description("AI 工具失败总次数")
                .register(registry);
        Gauge.builder("ai_tool_failure_rate", this, AiMetrics::failureRate)
                .description("AI 工具失败率（失败/总调用）")
                .register(registry);
        FunctionCounter.builder("ai_llm_timeout_count", llmTimeouts, AtomicLong::doubleValue)
                .description("模型调用超时次数")
                .register(registry);
        FunctionCounter.builder("ai_llm_fallback_count", llmFallbacks, AtomicLong::doubleValue)
                .description("主模型失败后切换到备选模型的次数")
                .register(registry);
        FunctionCounter.builder("ai_guest_quota_exceeded_count", quotaExceeded, AtomicLong::doubleValue)
                .description("游客轮次用尽被拦截次数")
                .register(registry);
        FunctionCounter.builder("ai_empty_card_count", emptyCards, AtomicLong::doubleValue)
                .description("应出卡却装配为空的次数")
                .register(registry);
        this.llmTimer = Timer.builder("ai_llm_duration")
                .description("模型调用耗时")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    /** 每次工具执行结束调用（成功/失败都计数）。 */
    public void toolResult(boolean success) {
        toolCalls.incrementAndGet();
        if (!success) {
            toolFailures.incrementAndGet();
        }
    }

    /**
     * 每次模型调用结束调用。
     *
     * @param nanos      耗时（纳秒）
     * @param timedOut   是否超时（由调用方按异常类型/消息判定）
     */
    public void llmCall(long nanos, boolean timedOut) {
        if (llmTimer != null && nanos > 0) {
            llmTimer.record(nanos, TimeUnit.NANOSECONDS);
        }
        if (timedOut) {
            llmTimeouts.incrementAndGet();
        }
    }

    /**
     * 每发生一次「主模型失败 → 切备选模型」调用一次。
     *
     * <p>为什么单独计数：回退成功时上层看不到异常，`ai_llm_timeout_count` 也不会涨。
     * 没有这个指标，「主模型已经挂了、全靠备选在撑」这件事在看板上完全不可见。
     */
    public void llmFallback() {
        llmFallbacks.incrementAndGet();
    }

    public void guestQuotaExceeded() {
        quotaExceeded.incrementAndGet();
    }

    public void emptyCard() {
        emptyCards.incrementAndGet();
    }

    /** 当前失败率（0~1）；总调用为 0 时返回 0，避免看板出现 NaN。 */
    public double failureRate() {
        long total = toolCalls.get();
        return total == 0 ? 0.0 : (double) toolFailures.get() / total;
    }

    public long toolCallCount() {
        return toolCalls.get();
    }

    public long toolFailureCount() {
        return toolFailures.get();
    }

    public long llmTimeoutCount() {
        return llmTimeouts.get();
    }

    public long llmFallbackCount() {
        return llmFallbacks.get();
    }

    public long quotaExceededCount() {
        return quotaExceeded.get();
    }

    public long emptyCardCount() {
        return emptyCards.get();
    }

    /**
     * 粗判是否超时：HTTP 客户端抛出的异常通常只在 message 里体现超时，
     * 没有稳定的类型可判。宁可漏判一次（只影响一个计数）也不能把业务失败算成超时。
     */
    public static boolean looksLikeTimeout(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 5) {
            String name = cursor.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("timeout")) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("read timed out")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }
}
