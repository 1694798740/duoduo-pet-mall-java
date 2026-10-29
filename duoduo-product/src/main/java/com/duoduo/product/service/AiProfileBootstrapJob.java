package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.product.entity.SpuAiProfileEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SpuAiProfileMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品 AI 属性回填（2026-10-28）。
 *
 * <p>存量商品没有人工维护的 AI 属性时，先从标题/副标题推断物种与生命阶段，
 * 保证结构化搜索「幼猫不推成猫粮」在第一天就能生效；后续由商家/运营补全。
 */
@Component
public class AiProfileBootstrapJob {

    private static final Logger log = LoggerFactory.getLogger(AiProfileBootstrapJob.class);
    private static final int BATCH = 1000;

    private final SpuMapper spuMapper;
    private final SpuAiProfileMapper profileMapper;

    public AiProfileBootstrapJob(SpuMapper spuMapper, SpuAiProfileMapper profileMapper) {
        this.spuMapper = spuMapper;
        this.profileMapper = profileMapper;
    }

    @Scheduled(initialDelayString = "${duoduo.ai.profile-bootstrap-delay-ms:60000}",
            fixedDelayString = "${duoduo.ai.profile-bootstrap-interval-ms:3600000}")
    public void backfill() {
        List<SpuEntity> spus = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                .isNull(SpuEntity::getDeletedAt)
                .orderByDesc(SpuEntity::getId)
                .last("LIMIT " + BATCH));
        if (spus.isEmpty()) {
            return;
        }
        Map<Long, SpuAiProfileEntity> existing = new HashMap<>();
        for (SpuAiProfileEntity p : profileMapper.selectBatchIds(
                spus.stream().map(SpuEntity::getId).toList())) {
            existing.put(p.getSpuId(), p);
        }
        int created = 0;
        for (SpuEntity spu : spus) {
            if (existing.containsKey(spu.getId())) {
                continue;
            }
            SpuAiProfileEntity p = new SpuAiProfileEntity();
            p.setSpuId(spu.getId());
            p.setSpecies(inferSpecies(spu.getTitle()));
            p.setLifeStages(inferStages(spu.getTitle()));
            p.setBreedSizes("");
            p.setAllergens("[]");
            p.setFunctionTags("[]");
            p.setContentSummary(summary(spu));
            p.setSearchText((spu.getTitle() == null ? "" : spu.getTitle()) + " "
                    + (spu.getSubtitle() == null ? "" : spu.getSubtitle()));
            p.setSourceVersion(1L);
            profileMapper.insert(p);
            created++;
        }
        if (created > 0) {
            log.info("AI 商品属性回填：新增 {} 条（扫描 {} 件）", created, spus.size());
        }
    }

    private static String inferSpecies(String title) {
        String t = title == null ? "" : title;
        if (t.contains("猫")) return "cat";
        if (t.contains("狗") || t.contains("犬")) return "dog";
        if (t.contains("仓鼠") || t.contains("兔") || t.contains("龙猫")) return "small_pet";
        if (t.contains("鸟") || t.contains("鹦鹉")) return "bird";
        if (t.contains("鱼")) return "fish";
        if (t.contains("爬") || t.contains("龟") || t.contains("蜥蜴")) return "reptile";
        return "other";
    }

    private static String inferStages(String title) {
        String t = title == null ? "" : title;
        if (t.contains("幼") || t.contains("奶糕") || t.contains("离乳")) return "BABY";
        if (t.contains("老年") || t.contains("高龄")) return "SENIOR";
        if (t.contains("成猫") || t.contains("成犬")) return "ADULT";
        return "";
    }

    private static String summary(SpuEntity spu) {
        StringBuilder sb = new StringBuilder();
        if (spu.getTitle() != null) {
            sb.append(spu.getTitle());
        }
        if (spu.getSubtitle() != null && !spu.getSubtitle().isBlank()) {
            sb.append("：").append(spu.getSubtitle());
        }
        if (spu.getIntro() != null && !spu.getIntro().isBlank()) {
            sb.append(" ").append(spu.getIntro());
        }
        String s = sb.toString();
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
