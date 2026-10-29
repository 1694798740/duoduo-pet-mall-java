package com.duoduo.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审计写入线程池（2026-10-29 · B2）。
 *
 * <h2>为什么独立于 SSE 线程池</h2>
 * 对话线程会被模型调用占住数十秒；审计如果共用同一个池，一次模型高峰就能把
 * 审计任务排到天荒地老（用户回复已经返回，审计却延迟几分钟才落库）。
 * 独立小池 + 有界队列，既能保证审计与对话互不拖累，也不会在数据库变慢时无限堆内存。
 *
 * <h2>为什么拒绝策略是「丢弃 + 记日志」</h2>
 * 审计不能反压对话：队列满时让用户等审计，等于把旁路事实变成主链路。
 * 丢弃必须留下 WARN —— 静默丢审计是比丢一条记录更严重的问题（没人知道丢了）。
 */
@Configuration
public class AiAuditConfig {

    private static final Logger log = LoggerFactory.getLogger(AiAuditConfig.class);

    @Bean(name = "aiAuditExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor aiAuditExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 4,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-audit-" + seq.incrementAndGet());
                    // 守护线程：应用退出时不因审计队列未清空而卡住 shutdown
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> {
                    log.warn("AI 审计队列已满（容量 2000），本次审计记录被丢弃");
                });
    }
}
