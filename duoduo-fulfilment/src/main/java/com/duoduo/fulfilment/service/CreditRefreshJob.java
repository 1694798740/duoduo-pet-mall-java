package com.duoduo.fulfilment.service;

import com.duoduo.common.web.sched.SchedLeaderLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 信用分回写定时任务（A4）。
 *
 * <p>存在的意义：{@code t_user.credit_score} 是一份**副本**，供「拿不到履约域的场景」使用；
 * 实时计算的权威值在 {@link CreditService#creditOf}。这个任务负责让副本别落后太久。
 *
 * <h2>为什么默认关闭</h2>
 * 它是一个「会写别的库」的后台任务。开发与自测期间后台写数据是最讨嫌的一类干扰：
 * 断言结果时好时坏，而现象又不在报错里。因此默认 {@code duoduo.credit.refresh-enabled=false}，
 * 想开时设环境变量 {@code CREDIT_REFRESH_ENABLED=true} 即可。
 *
 * <h2>三重保护（即便开了也不闯祸）</h2>
 * <ol>
 *   <li><b>幂等</b>：写的是「设置分数」而不是「累加」，分数没变就不写库（用户域侧判等）；</li>
 *   <li><b>逐条隔离</b>：单个用户失败只记日志、计入 skipped，不中断整批；</li>
 *   <li><b>整体兜底</b>：任务级 try/catch —— 调度线程里抛出的异常会静默吞掉后续触发，
 *       必须自己接住，否则「任务停了」这件事没有任何迹象。</li>
 * </ol>
 *
 * <p>单实例用 {@code @Scheduled} 足够；生产多实例需要 XXL-Job 之类保证只跑一份（同 {@code PLAN.md} B-05）。
 */
@Component
public class CreditRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(CreditRefreshJob.class);

    /** 每次最多处理多少用户，避免一轮把对端打满 */
    private static final int BATCH_LIMIT = 200;

    /** 选主用的任务名（一个任务一个名字，跨实例必须一致） */
    private static final String TASK_NAME = "credit-refresh";

    /** 持锁时长：本任务间隔 15 分钟、单轮最多 200 个用户，留足余量 */
    private static final long LEADER_TTL_MS = 600_000L;

    private final CreditService creditService;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;

    public CreditRefreshJob(CreditService creditService,
                            SchedLeaderLock leaderLock,
                            @Value("${duoduo.credit.refresh-enabled:false}") boolean enabled) {
        this.creditService = creditService;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        if (enabled) {
            log.info("信用分定时回写已启用");
        }
    }

    /**
     * 定期重算并回写信用分。
     *
     * <p>即便关闭也会被调度器触发（只是立刻返回）—— 这样开启 / 关闭只是配置差异，
     * 不需要重启换注解，也不会因为 {@code fixedDelayString=0} 之类写法变成「疯狂空转」。
     */
    @Scheduled(fixedDelayString = "${duoduo.credit.refresh-interval-ms:900000}",
            initialDelayString = "${duoduo.credit.refresh-initial-delay-ms:120000}")
    public void refresh() {
        if (!enabled) {
            return;
        }
        // 选主（第五十八轮 · P0-2）：多实例部署时只有抢到锁的实例执行本轮
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            creditService.refresh(null, BATCH_LIMIT, "SCHEDULED_REFRESH");
        } catch (Exception e) {
            log.error("信用分定时回写失败", e);
        }
    }
}
