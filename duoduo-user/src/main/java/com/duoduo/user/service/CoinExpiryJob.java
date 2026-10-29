package com.duoduo.user.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 多多币过期批次清理调度（R-P1）：到期批次的剩余从余额里清掉并记 EXPIRE 流水。
 *
 * <p><b>为什么必须有这个调度</b>：12 个月有效期意味着余额是一个**随时间衰减**的数 ——
 * 「先到期先扣」的前提是过期批次先被清掉，否则用户会花到已经「名义上过期」的币，
 * 平台负债也永远不减。没有它，有效期就是一句写在注释里的话。
 *
 * <p><b>选主 + 吞异常</b>：与 {@link AccountDeletionJob} 同一口径 ——
 * 多实例只有Leader执行；调度线程绝不抛异常（一次异常 = 后续轮次全部静默停摆）。
 */
@Component
public class CoinExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CoinExpiryJob.class);

    /** 选主用的任务名（一个任务一个名字，跨实例必须一致） */
    private static final String TASK_NAME = "coin-expiry-sweep";

    /** 持锁时长：必须大于单轮执行耗时 */
    private static final long LEADER_TTL_MS = 120_000L;

    private final CoinService coinService;
    private final SchedLeaderLock leaderLock;

    public CoinExpiryJob(CoinService coinService, SchedLeaderLock leaderLock) {
        this.coinService = coinService;
        this.leaderLock = leaderLock;
    }

    /** 默认每 10 分钟扫一轮（过期清理对实时性不敏感，但频繁的轻量扫描让负债曲线更准） */
    @Scheduled(fixedDelayString = "${duoduo.coin.expiry.sweep-interval-ms:600000}",
            initialDelayString = "${duoduo.coin.expiry.sweep-initial-delay-ms:30000}")
    public void scheduledSweep() {
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            coinService.expireLots();
        } catch (RuntimeException e) {
            log.error("多多币过期清理调度执行异常", e);
        }
    }

    /** 每日对账：余额 = Σ 未过期批次剩余 + 流水快照一致性；发现问题只报警不修数（修数要人工看清楚再动） */
    @Scheduled(fixedDelayString = "${duoduo.coin.recon.interval-ms:3600000}",
            initialDelayString = "${duoduo.coin.recon.initial-delay-ms:90000}")
    public void scheduledRecon() {
        if (!leaderLock.tryAcquire(TASK_NAME + "-recon", LEADER_TTL_MS)) {
            return;
        }
        try {
            var result = coinService.reconcile();
            if (!result.issues().isEmpty()) {
                log.error("多多币对账发现 {} 个问题：{}", result.issues().size(), result.issues());
            }
        } catch (RuntimeException e) {
            log.error("多多币对账调度执行异常", e);
        }
    }
}
