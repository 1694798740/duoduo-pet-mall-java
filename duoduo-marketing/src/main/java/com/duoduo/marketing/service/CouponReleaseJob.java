package com.duoduo.marketing.service;

import com.duoduo.marketing.dto.CouponDtos.SweepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 券的补偿调度：解锁「锁定超过 TTL 仍未支付」的券，并把过期未用的券标记为 EXPIRED。
 *
 * <p>为什么要有它，而不只靠交易域在关单时主动调 {@code release}：
 * 下单成功但用户直接关掉浏览器、交易域进程重启、对端调用失败、网络分区……
 * 这些情况下「主动调用」根本不会发生。没有兜底，券会被永久锁死 ——
 * 用户看着卡包里一张「已锁定」的券用不了，运营在后台也查不出任何异常，
 * 只能等用户来投诉。兜底扫描是「券不会凭空消失」这件事的最后一道保障。
 *
 * <p>可重入（解锁只认 LOCKED、标记过期只认 UNUSED），因此不需要分布式锁，
 * 也不用担心多实例重复触发造成错误 —— 代价只是多一次扫描。
 * 生产多实例应交给 XXL-Job 之类的分布式调度统一触发（见项目其它调度任务同样的约定）。
 */
@Component
public class CouponReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(CouponReleaseJob.class);

    private final CouponService couponService;

    @Value("${duoduo.coupon.lock-ttl-minutes:35}")
    private int lockTtlMinutes;

    @Value("${duoduo.coupon.release-batch-size:200}")
    private int batchSize;

    public CouponReleaseJob(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 调度入口。
     *
     * <p>异常必须吞掉：调度线程里抛出未捕获异常会让<b>后续所有轮次静默停摆</b>，
     * 表面看「任务还在」、实际一次都没再跑 —— 这类故障最难发现。
     */
    @Scheduled(fixedDelayString = "${duoduo.coupon.release-sweep-interval-ms:60000}",
            initialDelayString = "${duoduo.coupon.release-sweep-initial-delay-ms:30000}")
    public void scheduledSweep() {
        try {
            SweepResult result = sweepNow();
            if (result.scanned() > 0 || result.released() > 0 || result.expired() > 0) {
                log.info("券补偿调度：扫描 {} 张超时锁定券，解锁 {} 张，标记过期 {} 张",
                        result.scanned(), result.released(), result.expired());
            }
        } catch (RuntimeException e) {
            log.error("券补偿调度执行异常", e);
        }
    }

    /** 立即执行一轮（运维排障与自动化测试用；与调度入口共用同一段逻辑） */
    public SweepResult sweepNow() {
        return couponService.sweep(lockTtlMinutes, batchSize);
    }
}
