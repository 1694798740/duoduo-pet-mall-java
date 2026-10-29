package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.user.entity.CoinFreezeEntity;
import com.duoduo.user.mapper.CoinFreezeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * R-P1 · 多多币冻结 TTL 兜底：把「下单冻结了币、订单却没建成或关单时释放失败」的
 * 冻结单按时间兜底释放，余额归还账户。
 *
 * <p><b>为什么需要它</b>：币的冻结发生在下单事务内（真实扣减余额），
 * 但释放有两个不被本服务事务保护的窗口：
 * <ul>
 *   <li>交易域下单事务中途失败，补偿释放调用恰好失败（网络 / 本服务抖动）——
 *       订单不存在，永远不会再有人来释放这笔冻结；</li>
 *   <li>取消 / 超时关单时交易域的释放调用失败（best-effort），
 *       若交易域调度持续不可用，冻结会一直挂着。</li>
 * </ul>
 * 券的体系里有 TTL 兜底解锁（35 分钟），币这里同理 —— TTL 刻意配得比订单过期时间
 * （默认 30 分钟）宽裕得多（默认 2 小时）：正常路径下取消 / 关单早就把币还了，
 * 能活到 TTL 的冻结几乎必然是孤儿；即使交易域调度大面积停摆导致「订单还挂着、币先被还了」，
 * 支付确认扣减会被用户域 CAS 拒绝（FROZEN 才能扣），支付失败可重试，不会扣飞。
 *
 * <p><b>幂等</b>：释放走 {@code CoinService#release} 的 CAS（FROZEN → RELEASED），
 * 与交易域的主动释放并发也只会有一个成功；已释放的重入返回「已处理」。
 * <b>异常必须吞掉</b>：调度线程抛异常会让后续轮次静默停摆。
 */
@Component
public class CoinFreezeTtlJob {

    private static final Logger log = LoggerFactory.getLogger(CoinFreezeTtlJob.class);

    /** 选主用的任务名（一个任务一个名字，跨实例必须一致） */
    private static final String TASK_NAME = "coin-freeze-ttl-release";

    /** 持锁时长：必须大于单轮执行耗时 */
    private static final long LEADER_TTL_MS = 120_000L;

    private final CoinFreezeMapper freezeMapper;
    private final CoinService coinService;
    private final SchedLeaderLock leaderLock;

    /** 冻结多久仍未释放就视为孤儿（分钟）；必须显著大于交易域订单过期时间（默认 30 分钟） */
    @Value("${duoduo.coin.freeze-ttl-minutes:120}")
    private int freezeTtlMinutes;

    /** 单轮最多处理多少条，避免一次把库压住 */
    @Value("${duoduo.coin.freeze-ttl-batch-size:200}")
    private int batchSize;

    public CoinFreezeTtlJob(CoinFreezeMapper freezeMapper, CoinService coinService, SchedLeaderLock leaderLock) {
        this.freezeMapper = freezeMapper;
        this.coinService = coinService;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedDelayString = "${duoduo.coin.freeze-ttl.interval-ms:300000}",
            initialDelayString = "${duoduo.coin.freeze-ttl.initial-delay-ms:60000}")
    public void scheduledRelease() {
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            int released = releaseStale();
            if (released > 0) {
                log.info("多多币冻结 TTL 兜底：释放 {} 笔超时冻结，余额已归还", released);
            }
        } catch (RuntimeException e) {
            log.error("多多币冻结 TTL 兜底执行异常", e);
        }
    }

    /** 扫描超时冻结并逐笔释放；返回本轮成功释放的笔数 */
    public int releaseStale() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, freezeTtlMinutes));
        List<CoinFreezeEntity> stale = freezeMapper.selectList(new LambdaQueryWrapper<CoinFreezeEntity>()
                .eq(CoinFreezeEntity::getStatus, CoinFreezeEntity.STATUS_FROZEN)
                .le(CoinFreezeEntity::getCreatedAt, threshold)
                .orderByAsc(CoinFreezeEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, batchSize)));
        int released = 0;
        for (CoinFreezeEntity freeze : stale) {
            try {
                if (coinService.release(freeze.getOrderNo()).applied()) {
                    released++;
                }
            } catch (RuntimeException e) {
                // 单笔失败不连累整轮；它保持 FROZEN，下轮重试
                log.error("多多币冻结 {}（订单 {}）TTL 释放失败，下轮重试", freeze.getId(), freeze.getOrderNo(), e);
            }
        }
        return released;
    }
}
