package com.duoduo.ai.client;

import com.duoduo.ai.client.dto.AiProductCard;
import com.duoduo.ai.client.dto.AiProductFact;
import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域 AI 内部接口：结构化检索 + 实时事实回源。
 */
@FeignClient(name = "duoduo-product", contextId = "aiCatalogClient",
        path = "/api/products/internal/ai", configuration = AiFeignConfig.class)
public interface AiCatalogClient {

    @GetMapping("/search")
    ApiResponse<List<AiProductCard>> search(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "lifeStage", required = false) String lifeStage,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "budgetMin", required = false) Long budgetMin,
            @RequestParam(value = "budgetMax", required = false) Long budgetMax,
            @RequestParam(value = "excludeAllergens", required = false) String excludeAllergens,
            @RequestParam(value = "inStockOnly", defaultValue = "true") boolean inStockOnly,
            @RequestParam(value = "limit", defaultValue = "8") int limit);

    @GetMapping("/facts")
    ApiResponse<List<AiProductFact>> facts(@RequestParam("spuIds") String spuIds);
}
