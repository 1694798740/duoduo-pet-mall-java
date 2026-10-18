package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.ProductCardBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域客户端：批量取商品快照，用于分享圈发布时落快照（第四十三轮）。
 *
 * <p>内部调用由 {@code X-Internal-Token} 保护（见 {@code InternalCallConfig}），
 * 商品域侧由它自己的 {@code InternalTokenFilter} 校验。
 *
 * <p>为什么由服务端取快照而不是信任前端传标题 / 图片：分享圈是公开信息流，
 * 若快照字段来自请求体，任何人都能把「9.9 元猫粮」的卡片贴在奢侈品商品 id 上 ——
 * 快照必须以商品域事实为准，请求体里只留用户自己写的话（content）。
 */
@FeignClient(name = "duoduo-product", contextId = "fulfilmentProduct", path = "/api/products")
public interface ProductClient {

    /**
     * 批量取商品快照；参数为逗号分隔的 SPU ID。
     *
     * <p>参数用字符串而不是 {@code List<Long>}：与 {@link UserClient#briefs} 同一理由，
     * 固定成单值后两端集合编码行为确定。
     */
    @GetMapping("/internal/cards")
    ApiResponse<List<ProductCardBrief>> cards(@RequestParam("ids") String spuIds);

    /**
     * 评分回写（第七十三轮）：把受影响 SPU 的真实评分聚合推给商品域落列。
     *
     * <p>请求体是补丁列表（spuId / avgRating / ratingCount），返回生效条数。
     * 调用方必须是「事务提交后」—— 回写在事务内执行的话，评价还没提交商品域就先更新，
     * 一旦本域回滚就会出现「没有评价却有评分」的幽灵数据。
     */
    @PostMapping("/internal/ratings/recompute")
    ApiResponse<Integer> recomputeRatings(@RequestBody List<RatingPatch> patches);

    /**
     * 评分对账候选（2026-10-18）：商品域当前 rating_count &gt; 0 的 SPU。
     *
     * <p>与 {@link #recomputeRatings} 配对使用：候选里有、评价库没有 ⇒ 推 0 复位。
     */
    @GetMapping("/internal/ratings/candidates")
    ApiResponse<List<RatingCandidateBrief>> ratingCandidates();

    /**
     * 评分补丁（与商品域 {@code ProductDtos.RatingPatch} 对齐的跨域契约副本）。
     * 字段名必须逐字一致：spuId / avgRating / ratingCount。
     */
    record RatingPatch(Long spuId, Double avgRating, Integer ratingCount) {
    }

    /** 对账候选（与商品域 {@code ProductDtos.RatingCandidate} 对齐） */
    record RatingCandidateBrief(Long spuId, Integer ratingCount) {
    }
}
