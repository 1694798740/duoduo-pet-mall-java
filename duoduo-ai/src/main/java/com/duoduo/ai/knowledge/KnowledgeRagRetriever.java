package com.duoduo.ai.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 宠物百科检索（2026-10-28）。
 *
 * <p>当前先用 MySQL 做确定性关键词排序（数据量小、可解释），
 * ES 索引就绪后可替换为 BM25 + 向量混合检索，调用方不变。
 */
@Service
public class KnowledgeRagRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRagRetriever.class);

    private final KnowledgeDocMapper mapper;

    public KnowledgeRagRetriever(KnowledgeDocMapper mapper) {
        this.mapper = mapper;
    }

    public List<Snippet> retrieve(String query, String species, String lifeStage, int topK) {
        try {
            List<KnowledgeDocEntity> docs = mapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocEntity>()
                            .eq(KnowledgeDocEntity::getStatus, "ACTIVE")
                            .orderByDesc(KnowledgeDocEntity::getUpdatedAt)
                            .last("LIMIT 200"));
            String q = query == null ? "" : query;
            return docs.stream()
                    .filter(d -> species == null || d.getSpecies() == null || species.equals(d.getSpecies()))
                    .filter(d -> lifeStage == null || d.getLifeStage() == null || lifeStage.equals(d.getLifeStage()))
                    .map(d -> new Snippet(d.getId(), d.getTitle(), d.getContent(), d.getSource(),
                            score(d, q)))
                    .filter(s -> s.score() > 0)
                    .sorted(Comparator.comparingInt(Snippet::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("知识库检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static int score(KnowledgeDocEntity d, String query) {
        String text = (d.getTitle() == null ? "" : d.getTitle())
                + (d.getContent() == null ? "" : d.getContent());
        int score = 0;
        for (String token : query.split("[\\s,，。；;]+")) {
            if (token.length() >= 2 && text.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    public record Snippet(Long docId, String title, String content, String source, int score) {
    }
}
