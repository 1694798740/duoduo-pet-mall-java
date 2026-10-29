package com.duoduo.trade.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导出任务清理调度（矩阵第 9 项 · 2026-10-28）。
 *
 * <p>三件事，缺一件都会留下垃圾：
 * <ol>
 *   <li>服务重启后没人推进的 PENDING / RUNNING → FAILED（否则用户永远等一个不会完成的任务）；</li>
 *   <li>过期文件删除（文件是敏感数据，留在盘上越久风险越大）；</li>
 *   <li>30 天前的任务行清理（「我的任务」不能被历史淹没）。</li>
 * </ol>
 *
 * <p><b>选主 + 吞异常</b>：与其它调度同一口径 —— 多实例只有 Leader 执行；
 * 调度线程抛异常会让后续轮次全部静默停摆，因此这里必须 catch。
 */
@Component
public class ExportJobMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(ExportJobMaintenanceJob.class);

    private static final String TASK_NAME = "export-job-sweep";
    private static final long LEADER_TTL_MS = 120_000L;

    private final ExportJobService exportJobService;
    private final SchedLeaderLock leaderLock;

    public ExportJobMaintenanceJob(ExportJobService exportJobService, SchedLeaderLock leaderLock) {
        this.exportJobService = exportJobService;
        this.leaderLock = leaderLock;
    }

    /** 默认每 10 分钟一轮：过期文件不敏感于实时性，但也不能拖太久 */
    @Scheduled(fixedDelayString = "${duoduo.export.sweep-interval-ms:600000}",
            initialDelayString = "${duoduo.export.sweep-initial-delay-ms:60000}")
    public void sweep() {
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            exportJobService.sweep();
        } catch (RuntimeException e) {
            log.error("导出任务清理调度执行异常", e);
        }
    }
}
