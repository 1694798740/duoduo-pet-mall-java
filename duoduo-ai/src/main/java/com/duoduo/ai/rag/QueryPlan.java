package com.duoduo.ai.rag;

import java.util.List;

/**
 * RAG 检索计划：用户话 + 结构化过滤条件。
 */
public record QueryPlan(
        String queryText,
        String species,
        String lifeStage,
        Long categoryId,
        Long budgetMinCents,
        Long budgetMaxCents,
        List<String> excludeAllergens,
        boolean inStockOnly,
        int topK
) {
}
