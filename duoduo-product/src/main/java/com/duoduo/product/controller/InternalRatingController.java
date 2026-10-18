package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.ProductDtos;
import com.duoduo.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评分回写内部端点（第七十三轮）。
 *
 * <p>路径落在 {@code /api/products/internal/**}：与 {@code /internal/skus/batch}、
 * {@code /internal/cards} 同一条两段路径约定 —— 由商品域 {@code InternalTokenFilter}
 * 校验 {@code X-Internal-Token}，外部直接调用 403。
 *
 * <p>调用方是履约域：评价落库事务提交后，把受影响 SPU 的真实聚合（平均分 / 条数）
 * 推过来落列。商品域的评分列自此只有一个写入口 —— 首页卡片、搜索排序（sort=rating）
 * 消费的都是真实评价，不再是建库时的种子假数据。
 */
@RestController
@RequestMapping("/api/products/internal")
public class InternalRatingController {

    private final ProductService productService;

    public InternalRatingController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/ratings/recompute")
    public ApiResponse<Integer> recompute(@RequestBody List<ProductDtos.RatingPatch> patches) {
        return ApiResponse.ok(productService.recomputeRatings(patches));
    }

    /**
     * 评分对账候选（2026-10-18）：返回本域 rating_count &gt; 0 的 SPU。
     *
     * <p>评价域据此做两件事：① 候选里有、评价库里没有 → 推 0 复位（种子假数据 / 回写失败）；
     * ② 评价库里有、候选里没有 → 补推真实聚合（回写曾失败的漏网）。
     * 有了这条，首页与详情页的评分口径才真正收敛到「评价库」这一个事实源。
     */
    @GetMapping("/ratings/candidates")
    public ApiResponse<List<ProductDtos.RatingCandidate>> candidates() {
        return ApiResponse.ok(productService.ratingCandidates());
    }
}
