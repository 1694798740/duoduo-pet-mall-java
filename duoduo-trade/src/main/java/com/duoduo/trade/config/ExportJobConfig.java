package com.duoduo.trade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步导出线程池（矩阵第 9 项 · 2026-10-28）。
 *
 * <p><b>为什么必须独立线程池</b>：导出是「CPU + 磁盘 + 数据库顺序扫」的长任务，
 * 放进公共线程池会与关单扫描、对账调度抢线程；一次 50 万行导出能把定时任务饿死。
 *
 * <p><b>为什么上限这么小（2 并行 + 20 排队）</b>：导出会全表按筛选扫，并发越高越容易
 * 把数据库的 IO 吃满，反而让在线接口变慢。排队满了直接拒绝并提示稍后重试 ——
 * 静默堆积会让用户以为「点了没反应」。
 */
@Configuration
public class ExportJobConfig {

    @Bean(name = "exportJobExecutor")
    public Executor exportJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("export-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
