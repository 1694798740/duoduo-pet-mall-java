package com.duoduo.fulfilment.support;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.ProductClient;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 * <p>没有删评价功能、追评不改评分，所以本链路只有「新增评价」一个触发点，
 * 不存在需要反向补偿的路径。
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreated event) {
        List<Long> spuIds = event.spuIds() == null ? List.of()
                : event.spuIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (spuIds.isEmpty()) {
            return;
        }
        try {
            List<ProductClient.RatingPatch> patches = reviewMapper.ratingAggregatesBySpu(spuIds).stream()
                    .map(row -> new ProductClient.RatingPatch(
                            asLong(row.get("spuId")),
                            asDouble(row.get("avgRating")),
                            (int) asLong(row.get("cnt"))))
                    .toList();
            if (patches.isEmpty()) {
                return;
            }
            ApiResponse<Integer> response = productClient.recomputeRatings(patches);
            log.info("评分回写完成：SPU {} → 商品域生效 {} 列", spuIds,
                    response == null || response.data() == null ? 0 : response.data());
        } catch (Exception e) {
            // best-effort：评分是冗余展示列，同步失败不影响评价主流程；下次评价会重新触发全量聚合
            log.warn("评分回写失败（SPU {}），等待下次评价触发重算", spuIds, e);
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
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
