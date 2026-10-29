package com.duoduo.ai.client.dto;

import java.util.List;

/**
 * 实时商品事实（价格 / SKU / 库存），生成与护栏都以它为准。
 */
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
