package com.duoduo.user.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.user.dto.MerchantAuditSlaDtos.AuditMetrics;
import com.duoduo.user.dto.MerchantAuditSlaDtos.SampleReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 入驻侧事后抽查任务（E28）。
 *
 * <p>调度形态与 {@code MerchantLicenseJob}（E15）、商品侧 {@code AuditSampleJob} 保持一致：
 * 选主锁 + 异常不外抛 + 独立手动入口。抽的是**昨天**（与定时器同一口径）——
 * 手动触发若改成抽今天，运营点两次会看到两个不同结果，然后开始怀疑抽查准不准。
 */
@Service
public class MerchantAuditSampleJob {

    private static final Logger log = LoggerFactory.getLogger(MerchantAuditSampleJob.class);

    private static final String TASK_NAME = "merchant-audit-sample-sweep";
    private static final long LEADER_TTL_MS = 600_000L;

    private final MerchantAuditSampleService sampleService;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;

    public MerchantAuditSampleJob(MerchantAuditSampleService sampleService,
                                  SchedLeaderLock leaderLock,
                                  @Value("${duoduo.audit.sla.merchant-sample-enabled:true}") boolean enabled) {
        this.sampleService = sampleService;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        if (enabled) {
            log.info("入驻抽查任务已启用（每日一轮）");
        }
    }

    @Scheduled(fixedDelayString = "${duoduo.audit.sla.merchant-sample-interval-ms:86400000}",
            initialDelayString = "${duoduo.audit.sla.merchant-sample-initial-delay-ms:1500000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            sweepNow();
        } catch (Exception ex) {
            log.error("入驻抽查任务异常", ex);
        }
    }

    /** 手动触发一轮（{@code POST /api/admin/merchants/audit-sample-sweep}） */
    public SampleReport sweepNow() {
        LocalDate target = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        SampleReport report = sampleService.sweepOnce(target);
        AuditMetrics metrics = sampleService.metricsSnapshot();
        log.info("入驻抽查完成（{}）：候选 {}、生成 {}、跳过 {}；当前比例 {}%、违规率 {}%",
                target, report.candidates(), report.created(), report.skipped(),
                metrics.currentRatePercent(), metrics.violationPercent());
        return report;
    }
}
