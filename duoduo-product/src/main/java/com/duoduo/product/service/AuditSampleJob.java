package com.duoduo.product.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.product.dto.AuditSlaDtos.AuditMetrics;
import com.duoduo.product.dto.AuditSlaDtos.SampleReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 事后抽查任务（E28）：每天生成一批复核任务，并顺带评估要不要收紧抽样比例。
 *
 * <p>调度形态与 {@code MerchantLicenseJob}（E15）**刻意保持一致**：
 * 选主锁 + 单轮上限 + 异常不外抛 + 独立的 {@code xxxNow()} 手动入口。
 * 四条都不是风格偏好，而是各自被踩过：
 * <ul>
 *   <li><b>选主</b>：多实例部署时每台都会跑 {@code @Scheduled}，不选主则同一天重复抽；</li>
 *   <li><b>单轮上限</b>：某天放行量异常大时，任务要能跑完而不是拖成慢查询；</li>
 *   <li><b>异常不外抛</b>：抛出去会让 Spring 的调度线程后续几轮静默停摆 ——
 *       最坏的形态是「任务没报错、也没再跑过」；</li>
 *   <li><b>手动入口</b>：验收与事故排查都需要「现在就跑一轮看效果」。</li>
 * </ul>
 *
 * <p><b>为什么跑在凌晨</b>：抽样对象是「当天已经自动放行的那批商品」，
 * 一天里陆续放行，只有等当天结束才能拿到完整候选集。定时器设在 00:20（UTC）
 * 抽前一天：晚一点跑还有个好处 —— 零点前后的时间抖动不会让某天的任务被跳过。
 */
@Service
public class AuditSampleJob {

    private static final Logger log = LoggerFactory.getLogger(AuditSampleJob.class);

    private static final String TASK_NAME = "audit-sample-sweep";
    private static final long LEADER_TTL_MS = 600_000L;

    private final AuditSampleService sampleService;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;

    public AuditSampleJob(AuditSampleService sampleService,
                          SchedLeaderLock leaderLock,
                          @Value("${duoduo.audit.sla.sample-enabled:true}") boolean enabled) {
        this.sampleService = sampleService;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        if (enabled) {
            log.info("审核抽查任务已启用（每日一轮，抽样比例见 t_audit_sample_rate）");
        }
    }

    /** 调度入口：每日一轮（86400000ms），初始延迟 20 分钟 */
    @Scheduled(fixedDelayString = "${duoduo.audit.sla.sample-interval-ms:86400000}",
            initialDelayString = "${duoduo.audit.sla.sample-initial-delay-ms:1200000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;         // 别的实例正在跑本轮
        }
        try {
            sweepNow();
        } catch (Exception ex) {
            log.error("审核抽查任务异常", ex);      // ★ 异常不外抛
        }
    }

    /**
     * 手动触发一轮（运营入口 {@code POST /api/admin/audit-sla/sweep}）。
     *
     * <p>与 {@link #sweep()} 两处不同：不看 {@code enabled}；不抢选主锁。
     * 抽的是**昨天**（与定时器同一口径）—— 手动触发若改成抽今天，
     * 运营点两次会看到两个不同的结果，然后开始怀疑「抽查到底准不准」。
     */
    public SampleReport sweepNow() {
        LocalDate target = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        SampleReport report = sampleService.sweepOnce(target);
        // 抽完顺带评估一次指标：收紧判定需要连续多天观察，挂在每日任务上最省事，
        // 不需要另外再开一个"只在特定时间跑"的调度。
        AuditMetrics metrics = sampleService.metricsSnapshot();
        log.info("审核抽查完成（{}）：候选 {}、生成 {}、跳过 {}；当前比例 {}%、"
                        + "违规率 {}%、平均复核 {} 小时",
                target, report.candidates(), report.created(), report.skipped(),
                metrics.currentRatePercent(), metrics.violationPercent(), metrics.avgResponseHours());
        return report;
    }
}
