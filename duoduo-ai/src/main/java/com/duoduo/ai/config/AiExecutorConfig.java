package com.duoduo.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 流式对话线程池（2026-10-28）。
 *
 * <p>与 Tomcat 请求线程隔离：LLM 生成可能持续数秒，不能占满 Web 线程。
 */
@Configuration
public class AiExecutorConfig {

    @Bean(name = "aiStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService aiStreamExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(16, runnable -> {
            Thread t = new Thread(runnable, "ai-stream-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
