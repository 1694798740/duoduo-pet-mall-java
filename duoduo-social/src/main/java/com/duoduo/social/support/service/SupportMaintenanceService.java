package com.duoduo.social.support.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.client.UploadCleanupClient;
import com.duoduo.social.mapper.SupportMessageMapper;
import com.duoduo.social.mapper.SupportSessionMapper;
import com.duoduo.social.support.entity.SupportMessageEntity;
import com.duoduo.social.support.entity.SupportSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客服会话生命周期维护（2026-10-23）。
 *
 * <h2>两个任务，两条不同的语义</h2>
 * <ol>
 *   <li><b>自动关闭</b>（默认每 5 分钟扫一次）：双方 24 小时都没有新消息的 OPEN 会话 → CLOSED。
 *       关闭只是**客服侧**的收尾标记：买家完全无感，他再发一条消息会话就自动重开。</li>
 *   <li><b>过期清理</b>（默认每天 04:20）：关闭满 30 天、且期间双方没有任何新消息的会话 →
 *       物理删除会话与全部消息，并把图片/视频对象从存储里删掉。</li>
 * </ol>
 *
 * <h2>为什么不用分布式锁</h2>
 * 两个任务都写成「条件写在 SQL 里」的形式：自动关闭是
 * {@code UPDATE ... WHERE status='OPEN' AND last_message_at < ?}，
 * 多实例同时跑只有第一个真的改到行；清理的删除也带
 * {@code WHERE status='CLOSED' AND closed_at < ?}，第二个实例 affected=0。
 * 既然天然幂等，再加一层 leader 锁只会多一个可能坏掉的组件。
 *
 * <h2>清理顺序为什么是「先删对象、再删行」</h2>
 * 反过来（先删行）一旦对象删除失败，这批 URL 就永久失去了上下文 —— 库里没有记录，
 * 谁也不知道桶里还有哪些孤儿对象。现在的顺序在失败时**保留会话行**，下一轮重试即可；
 * 代价最多是「多留 30 天」，而多留是可见、可补救的，丢失不可补救。
 */
@Service
public class SupportMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(SupportMaintenanceService.class);

    private final SupportSessionMapper sessionMapper;
    private final SupportMessageMapper messageMapper;
    private final UploadCleanupClient uploadCleanupClient;

    /** 双方静默多久自动结束（小时） */
    private final long autoCloseHours;
    /** 关闭满多少天且无往来即物理删除（天） */
    private final long cleanupDays;
    /** 每轮最多处理多少个会话（避免一次扫描锁住太多行） */
    private final int cleanupBatch;

    public SupportMaintenanceService(SupportSessionMapper sessionMapper,
                                     SupportMessageMapper messageMapper,
                                     UploadCleanupClient uploadCleanupClient,
                                     @Value("${duoduo.support.auto-close-hours:24}") long autoCloseHours,
                                     @Value("${duoduo.support.cleanup-days:30}") long cleanupDays,
                                     @Value("${duoduo.support.cleanup-batch:100}") int cleanupBatch) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.uploadCleanupClient = uploadCleanupClient;
        this.autoCloseHours = autoCloseHours;
        this.cleanupDays = cleanupDays;
        this.cleanupBatch = cleanupBatch;
    }

    // ---------------- 自动关闭 ----------------

    @Scheduled(fixedDelayString = "${duoduo.support.auto-close-interval-ms:300000}",
            initialDelayString = "${duoduo.support.auto-close-initial-delay-ms:60000}")
    public int autoCloseIdleSessions() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime threshold = now.minusHours(autoCloseHours);
        try {
            int n = sessionMapper.closeIdleSessions(threshold, now);
            if (n > 0) {
                log.info("客服会话自动结束：{} 个（静默超过 {} 小时）", n, autoCloseHours);
            }
            return n;
        } catch (Exception e) {
            // 定时任务不能把异常抛给调度线程：一次失败必须不影响下一轮
            log.warn("客服会话自动结束失败（threshold={}）", threshold, e);
            return 0;
        }
    }

    // ---------------- 过期清理 ----------------

    /** 清理结果（同时用于日志、内部接口返回与自测断言） */
    public record CleanupResult(int deleted, int objects, int skipped) {
    }

    @Scheduled(cron = "${duoduo.support.cleanup-cron:0 20 4 * * *}")
    public CleanupResult cleanupExpiredSessions() {
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusDays(cleanupDays);
        List<SupportSessionEntity> rows;
        try {
            rows = sessionMapper.selectExpired(threshold, cleanupBatch);
        } catch (Exception e) {
            log.warn("查询过期客服会话失败", e);
            return new CleanupResult(0, 0, 0);
        }
        if (rows == null || rows.isEmpty()) {
            return new CleanupResult(0, 0, 0);
        }
        int deleted = 0;
        int skipped = 0;
        int objects = 0;
        for (SupportSessionEntity row : rows) {
            try {
                List<SupportMessageEntity> messages = messageMapper.selectList(
                        new LambdaQueryWrapper<SupportMessageEntity>()
                                .eq(SupportMessageEntity::getSessionId, row.getId()));
                Set<String> urls = mediaUrlsOf(messages);
                if (!deleteObjects(urls)) {
                    // 对象没删干净就不删行：下一轮重试。宁可多留 30 天，也不留孤儿文件
                    skipped++;
                    continue;
                }
                objects += urls.size();
                messageMapper.delete(new LambdaQueryWrapper<SupportMessageEntity>()
                        .eq(SupportMessageEntity::getSessionId, row.getId()));
                // 二次校验 + 删除：与买家「重开」并发时，这里 affected=0，消息也已随会话一起失效
                int n = sessionMapper.deleteExpired(row.getId(), threshold);
                if (n > 0) {
                    deleted++;
                } else {
                    // 行在被删前被重开了：消息已经删掉属于不可逆操作，这里必须显式告警
                    log.warn("客服会话在清理过程中被重新打开，消息已删除 sessionId={}", row.getId());
                }
            } catch (Exception e) {
                skipped++;
                log.warn("清理客服会话失败 sessionId={}", row.getId(), e);
            }
        }
        log.info("客服会话清理完成：删除 {} 个会话 / {} 个媒体对象，跳过 {} 个（阈值 {}）",
                deleted, objects, skipped, threshold);
        return new CleanupResult(deleted, objects, skipped);
    }

    /** 收集需要清理的对象 URL；只认上传目录，贴纸（/stickers/）等静态资源绝不删 */
    private Set<String> mediaUrlsOf(List<SupportMessageEntity> messages) {
        Set<String> urls = new LinkedHashSet<>();
        if (messages == null) {
            return urls;
        }
        for (SupportMessageEntity m : messages) {
            addUploadUrl(urls, m.getMediaUrl());
            addUploadUrl(urls, m.getThumbUrl());
        }
        return urls;
    }

    private void addUploadUrl(Set<String> urls, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String trimmed = url.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            return;
        }
        // uploads/ 是上传对象的唯一前缀；贴纸是 /stickers/*.svg，不满足条件
        if (!trimmed.contains("/uploads/")) {
            return;
        }
        urls.add(trimmed);
    }

    /** 调用履约域批量删除；返回 true 表示可以继续删库里的行（空集合视为成功） */
    private boolean deleteObjects(Set<String> urls) {
        if (urls.isEmpty()) {
            return true;
        }
        try {
            ApiResponse<Integer> resp = uploadCleanupClient.delete(new ArrayList<>(urls));
            if (resp == null) {
                log.warn("媒体清理无响应，跳过本轮（{} 个对象）", urls.size());
                return false;
            }
            if (!"SUCCESS".equals(resp.code())) {
                log.warn("媒体清理失败 code={} message={}（{} 个对象）", resp.code(), resp.message(), urls.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("媒体清理调用失败（{} 个对象）", urls.size(), e);
            return false;
        }
    }
}
