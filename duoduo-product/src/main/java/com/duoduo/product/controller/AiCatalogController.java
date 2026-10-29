package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.AiCatalogDtos.AiProductCard;
import com.duoduo.product.dto.AiCatalogDtos.AiProductFact;
import com.duoduo.product.service.AiCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 域内部商品接口（2026-10-28）。
 *
 * <p>路径在 {@code /api/products/internal/**} 下，由 {@code InternalTokenFilter} 校验内部签名；
 * 不经网关、不对公网开放。
 */
@RestController
@RequestMapping("/api/products/internal/ai")
public class AiCatalogController {

    private final AiCatalogService aiCatalogService;

    public AiCatalogController(AiCatalogService aiCatalogService) {
        this.aiCatalogService = aiCatalogService;
    }

    /** 结构化商品检索：物种 / 生命阶段 / 预算 / 品类 / 过敏原 / 有货 */
    @GetMapping("/search")
    public ApiResponse<List<AiProductCard>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String lifeStage,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long budgetMin,
            @RequestParam(required = false) Long budgetMax,
            @RequestParam(required = false) String excludeAllergens,
            @RequestParam(defaultValue = "true") boolean inStockOnly,
            @RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.ok(aiCatalogService.search(query, species, lifeStage, categoryId,
                budgetMin, budgetMax, excludeAllergens, inStockOnly, limit));
    }

    /** 实时价格 / SKU / 库存回源：AI 生成前后都必须以这里的返回为准 */
    @GetMapping("/facts")
    public ApiResponse<List<AiProductFact>> facts(@RequestParam("spuIds") String spuIds) {
        return ApiResponse.ok(aiCatalogService.facts(spuIds));
    }
}
