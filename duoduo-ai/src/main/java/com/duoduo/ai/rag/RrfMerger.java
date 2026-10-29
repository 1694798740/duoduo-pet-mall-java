package com.duoduo.ai.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：不依赖 BM25 与 cosine 的分数尺度。
 */
public final class RrfMerger {

    private RrfMerger() {
    }

    public static List<String> merge(List<String> bm25, List<String> knn, int k, int limit) {
        Map<String, Double> score = new LinkedHashMap<>();
        add(score, bm25, k);
        add(score, knn, k);
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void add(Map<String, Double> score, List<String> ids, int k) {
        for (int i = 0; i < ids.size(); i++) {
            score.merge(ids.get(i), 1.0 / (k + i + 1), Double::sum);
        }
    }
}
