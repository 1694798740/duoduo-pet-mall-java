package com.duoduo.user.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * C7 · 账号注销执行调度：把冷静期到点的申请真正执行掉。
 *
 * <p><b>为什么需要兜底扫描</b>：注销申请是用户主动行为，到期执行却没有任何
 * "用户再来一次"的触发点 —— 用户提交完就走了，七天里不会再来。没有这个调度，
 * 所有注销申请都会永远停在 {@code CANCELLING}：账号登录被停用（用户以为已经注销了），
 * 个人数据却一个字节都没清。这是「承诺了注销、实际什么都没发生」的静默失败形态。
 *
 * <p><b>选主</b>：多实例部署时只有抢到锁的实例执行本轮，避免重复执行。
 * 任务本身幂等（{@code AccountDeletionService#executeOne} 可重入），
 * 重复跑不会算错，但会让「这轮注销了几个账号」说不清。
 *
 * <p><b>异常必须吞掉</b>：调度线程里抛出未捕获异常会让后续所有轮次静默停摆 ——
 * 表面看任务还在、实际一次都没再跑。单个用户执行失败由服务层记录并下轮重试，
 * 不在这里扩散。
 */
@Component
public class AccountDeletionJob {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionJob.class);

    /** 选主用的任务名（一个任务一个名字，跨实例必须一致） */
    private static final String TASK_NAME = "account-deletion-execute";

    /** 持锁时长：必须大于单轮执行耗时 */
    private static final long LEADER_TTL_MS = 120_000L;

    private final AccountDeletionService deletionService;
    private final SchedLeaderLock leaderLock;

    public AccountDeletionJob(AccountDeletionService deletionService, SchedLeaderLock leaderLock) {
        this.deletionService = deletionService;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedDelayString = "${duoduo.account-deletion.sweep-interval-ms:60000}",
            initialDelayString = "${duoduo.account-deletion.sweep-initial-delay-ms:45000}")
    public void scheduledExecute() {
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            int executed = deletionService.executeDue();
            if (executed > 0) {
                log.info("账号注销调度：执行 {} 个到期申请", executed);
            }
        } catch (RuntimeException e) {
            log.error("账号注销调度执行异常", e);
        }
    }
}
