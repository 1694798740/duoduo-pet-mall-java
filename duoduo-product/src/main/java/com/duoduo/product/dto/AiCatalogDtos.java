package com.duoduo.product.dto;

import java.util.List;

/**
 * 商品域 → AI 域的内部契约（2026-10-28）。
 *
 * <p>只暴露 AI 推荐需要的字段，不带 sellerId / 成本价等管理面信息。
 */
public final class AiCatalogDtos {

    private AiCatalogDtos() {
    }

    public record AiProductCard(
            Long spuId,
            String title,
            String subtitle,
            long minPrice,
            long maxPrice,
            int salesCount,
            double rating,
            boolean inStock,
            String species,
            List<String> lifeStages,
            List<String> allergens,
            List<String> functionTags,
            String packageSize,
            String summary
    ) {
    }

    public record AiSkuFact(
            Long skuId,
            String specText,
            long price,
            int stock,
            String status
    ) {
    }

    public record AiProductFact(
            Long spuId,
            String title,
            String subtitle,
            long minPrice,
            long maxPrice,
            boolean inStock,
            List<AiSkuFact> skus
    ) {
    }
}
