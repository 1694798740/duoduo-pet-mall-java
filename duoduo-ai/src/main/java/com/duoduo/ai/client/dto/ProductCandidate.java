package com.duoduo.ai.client.dto;

/**
 * 商品候选（商品域 → AI 服务的取数口径）。
 *
 * <p>刻意**不含** sellerId / categoryId 等管理面字段：候选是给推荐回复用的展示素材，
 * 按「调用方真正需要什么」裁剪（同 UserBrief 的取舍）。
 */
public record ProductCandidate(
        Long id,
        String title,
        String subtitle,
        long minPrice,
        long maxPrice,
        int salesCount,
        double rating,
        boolean inStock
) {
}
