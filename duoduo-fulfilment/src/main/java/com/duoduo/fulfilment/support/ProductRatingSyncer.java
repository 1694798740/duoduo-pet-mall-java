package com.duoduo.fulfilment.support;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.ProductClient;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品评分回写（第七十三轮）：评价落库事务提交后，把受影响 SPU 的真实聚合
 * （平均分 / 条数）推给商品域，落到 t_spu.rating / rating_count。
 *
 * <p>为什么是 AFTER_COMMIT 事件而不是方法末尾直接调：
 * <ul>
 *   <li><b>事务边界</b>：回写若发生在事务内，评价还没提交商品域就先更新 ——
 *       本域一旦回滚，就会出现「商品页有评分、评价区是空的」幽灵数据；</li>
 *   <li><b>best-effort</b>：评分列是展示性冗余，商品域暂时不可用时只 log.warn，
 *       不把「评价提交成功」的响应拖成 5xx；下次任一评价再落到该 SPU 时会自然重算补上。</li>
 * </ul>
 *
 * <p>触发点有两个：评价创建（新增可见评价）与评价可见性变更（H-02 后台下架 / 恢复）。
 * 追评不改评分，不触发回写。
 *
 * <p><b>为什么要处理「聚合不存在」</b>：下架某商品的最后一条通过评价后，
 * {@code ratingAggregatesBySpu} 对该 SPU 返回空行 —— 若只按结果集推补丁，
 * 商品页会停在旧评分与旧条数，出现「评价区空了、评分还在」的矛盾。
 * 因此对**每个受影响的 SPU** 都生成补丁，无聚合时显式推 {@code 0 分 / 0 条}。
 */
@Component
public class ProductRatingSyncer {

    private static final Logger log = LoggerFactory.getLogger(ProductRatingSyncer.class);

    private final ReviewMapper reviewMapper;
    private final ProductClient productClient;

    public ProductRatingSyncer(ReviewMapper reviewMapper, ProductClient productClient) {
        this.reviewMapper = reviewMapper;
        this.productClient = productClient;
    }

    /** 评价创建事件：payload 只带受影响的 spuId，聚合在监听器里现算（拿的是已提交后的数据） */
    public record ReviewCreated(List<Long> spuIds) {
    }

    /** 评价可见性变更（H-02 下架 / 恢复）：payload 同样是受影响的 spuId */
    public record ReviewVisibilityChanged(List<Long> spuIds) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreated event) {
        syncRatings(event.spuIds(), "评价创建");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewVisibilityChanged(ReviewVisibilityChanged event) {
        syncRatings(event.spuIds(), "评价可见性变更");
    }

    /**
     * 按 SPU 现算真实聚合并回写。
     *
     * <p>对每个输入 spuId 都生成补丁：查不到聚合（该商品已无通过评价）时推 0，
     * 这是下架最后一条评价后必须的反向补偿；否则商品页会停在旧分数。
     */
    private void syncRatings(List<Long> rawSpuIds, String trigger) {
        List<Long> spuIds = rawSpuIds == null ? List.of()
                : rawSpuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (spuIds.isEmpty()) {
            return;
        }
        try {
            Map<Long, ProductClient.RatingPatch> patches = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : reviewMapper.ratingAggregatesBySpu(spuIds)) {
                long spuId = asLong(row.get("spuId"));
                patches.put(spuId, new ProductClient.RatingPatch(
                        spuId, asDouble(row.get("avgRating")), (int) asLong(row.get("cnt"))));
            }
            for (Long spuId : spuIds) {
                patches.putIfAbsent(spuId, new ProductClient.RatingPatch(spuId, 0.0, 0));
            }
            ApiResponse<Integer> response = productClient.recomputeRatings(new ArrayList<>(patches.values()));
            log.info("评分回写完成（{}）：SPU {} → 商品域生效 {} 列", trigger, spuIds,
                    response == null || response.data() == null ? 0 : response.data());
        } catch (Exception e) {
            // best-effort：评分是冗余展示列，同步失败不影响评价主流程；对账任务会兜底修正
            log.warn("评分回写失败（{}，SPU {}），等待下次评价或对账触发重算", trigger, spuIds, e);
        }
    }

    /**
     * 启动后对账一次（2026-10-18）：修掉历史种子假数据与「回写曾失败」的漂移。
     *
     * <p>为什么不在建库脚本里直接改：假数据与真实评价的差异只有评价库知道，
     * 一次性 SQL 只是把 4.0 强行改成 0，遇到「其实有评价只是没回写」的商品会把真数据也抹掉。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile("启动");
    }

    /**
     * 定时对账（默认每小时）：兜住 best-effort 回写的偶发失败。
     * 间隔与首次延迟都可用配置覆盖，便于测试时调小。
     */
    @Scheduled(fixedDelayString = "${duoduo.review.rating-reconcile-interval-ms:3600000}",
            initialDelayString = "${duoduo.review.rating-reconcile-initial-ms:120000}")
    public void reconcilePeriodically() {
        reconcile("定时");
    }

    /**
     * 对账主流程：候选（商品域说「我有评分」）∪ 评价库真实聚合 → 补丁。
     *
     * @return 商品域实际生效的行数（失败时 0，不抛异常 —— 对账失败不该阻断业务）
     */
    public int reconcile(String trigger) {
        try {
            List<ProductClient.RatingCandidateBrief> candidatesResp = null;
            ApiResponse<List<ProductClient.RatingCandidateBrief>> candidatesAnswer =
                    productClient.ratingCandidates();
            if (candidatesAnswer != null) {
                candidatesResp = candidatesAnswer.data();
            }
            List<ProductClient.RatingCandidateBrief> candidates =
                    candidatesResp == null ? List.of() : candidatesResp;

            List<RatingReconcile.Candidate> claimed = new ArrayList<>();
            for (ProductClient.RatingCandidateBrief c : candidates) {
                if (c != null && c.spuId() != null) {
                    claimed.add(new RatingReconcile.Candidate(
                            c.spuId(), c.ratingCount() == null ? 0 : c.ratingCount()));
                }
            }

            List<RatingReconcile.Aggregate> actual = new ArrayList<>();
            for (Map<String, Object> row : reviewMapper.ratingAggregatesAll()) {
                actual.add(new RatingReconcile.Aggregate(
                        asLong(row.get("spuId")), asDouble(row.get("avgRating")), (int) asLong(row.get("cnt"))));
            }

            List<ProductClient.RatingPatch> patches = RatingReconcile.plan(claimed, actual);
            if (patches.isEmpty()) {
                // ★ 0 也要说出来：否则与「这段没跑」长得一样
                log.info("评分对账（{}）：候选 0 个、有评价 0 个 —— 已核对，无需回写", trigger);
                return 0;
            }
            ApiResponse<Integer> response = productClient.recomputeRatings(patches);
            int applied = response == null || response.data() == null ? 0 : response.data();
            log.info("评分对账（{}）：候选 {} / 有评价 {} / 补丁 {} / 生效 {}",
                    trigger, claimed.size(), actual.size(), patches.size(), applied);
            return applied;
        } catch (Exception e) {
            log.warn("评分对账失败（{}）—— 下一轮会重试，不影响评价主流程", trigger, e);
            return 0;
        }
    }

    private long asLong(Object value) {        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0d : Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
