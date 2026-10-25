package com.duoduo.product.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 回收站清理任务（A-09）：每日把软删满 30 天的商品物理删掉。
 *
 * <p>调度形态与 {@code AuditSampleJob} / {@code MerchantLicenseJob} 保持一致：
 * 选主锁 + 单轮上限（在服务层）+ 异常不外抛 + 手动入口。
 * <ul>
 *   <li><b>选主</b>：多实例部署时每台都会跑 {@code @Scheduled}，不选主会并发删同一批行；</li>
 *   <li><b>异常不外抛</b>：抛出去会让调度线程后续几轮静默停摆；</li>
 *   <li><b>手动入口</b>：{@code POST /api/admin/products/recycle/purge}。</li>
 * </ul>
 *
 * <p><b>保留期的定义在服务层</b>（{@code deleted_at < now - 30d}），这个任务只是执行者。
 * 所以即使任务停摆几天，恢复接口仍然会按同一个 30 天口径拒绝过期商品，
 * 不会出现「只要清理任务没跑，回收站就能无限期恢复」。
 */
@Service
public class ProductRecycleJob {

    private static final Logger log = LoggerFactory.getLogger(ProductRecycleJob.class);

    private static final String TASK_NAME = "product-recycle-purge";
    private static final long LEADER_TTL_MS = 600_000L;

    private final SellerProductService sellerProductService;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;

    public ProductRecycleJob(SellerProductService sellerProductService,
                             SchedLeaderLock leaderLock,
                             @Value("${duoduo.product.recycle.purge-enabled:true}") boolean enabled) {
        this.sellerProductService = sellerProductService;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        if (enabled) {
            log.info("商品回收站清理任务已启用（每日一轮，保留期 {} 天）",
                    SellerProductService.RECYCLE_RETENTION_DAYS);
        }
    }

    /** 调度入口：每日一轮（86400000ms），初始延迟 5 分钟 */
    @Scheduled(fixedDelayString = "${duoduo.product.recycle.purge-interval-ms:86400000}",
            initialDelayString = "${duoduo.product.recycle.purge-initial-delay-ms:300000}")
    public void purge() {
        if (!enabled) {
            return;
        }
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;         // 别的实例正在跑本轮
        }
        try {
            int purged = sellerProductService.purgeExpired(LocalDateTime.now(ZoneOffset.UTC));
            if (purged > 0) {
                log.info("商品回收站清理完成：物理删除 {} 件", purged);
            }
        } catch (Exception ex) {
            log.error("商品回收站清理任务异常（下轮重试）", ex);      // ★ 异常不外抛
        }
    }
}
