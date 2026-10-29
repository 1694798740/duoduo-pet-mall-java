package com.duoduo.ai.client.dto;

import java.util.List;

/**
 * 商品域 AI 检索卡片（字段与 duoduo-product 的 AiCatalogDtos.AiProductCard 对齐）。
 */
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
