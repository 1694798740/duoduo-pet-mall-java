package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.product.dto.AiCatalogDtos.AiProductCard;
import com.duoduo.product.dto.AiCatalogDtos.AiProductFact;
import com.duoduo.product.dto.AiCatalogDtos.AiSkuFact;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuAiProfileEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuAiProfileMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 域的商品检索与实时事实接口（2026-10-28）。
 *
 * <p>为什么单独一个 Service，而不是让 AI 直接查商品库：
 * 商品可见性（ON_SALE + APPROVED + 未删除）、价格区间、库存聚合的规则只能有一处实现。
 * AI 只消费裁剪后的卡片，既拿不到管理面字段，也不会出现「AI 看到的商品状态与商城不一致」。
 *
 * <p>结构化筛选优先用 {@code t_spu_ai_profile}；没有档案时从标题推断物种/生命阶段，
 * 保证存量商品也能被基本过滤，而不是「没建档就全部不过滤」。
 */
@Service
public class AiCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AiCatalogService.class);
    private static final int MAX_POOL = 200;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final SpuAiProfileMapper profileMapper;
    private final CategoryService categoryService;
    private final ObjectMapper mapper;

    public AiCatalogService(SpuMapper spuMapper, SkuMapper skuMapper,
                            SpuAiProfileMapper profileMapper, CategoryService categoryService,
                            ObjectMapper mapper) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.profileMapper = profileMapper;
        this.categoryService = categoryService;
        this.mapper = mapper;
    }

    public List<AiProductCard> search(String query, String species, String lifeStage,
                                      Long categoryId, Long budgetMin, Long budgetMax,
                                      String excludeAllergens, boolean inStockOnly, int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper();
        if (categoryId != null) {
            List<Long> expanded = categoryService.expandCategoryIds(categoryId);
            if (!expanded.isEmpty()) {
                wrapper.in(SpuEntity::getCategoryId, expanded);
            }
        }
        String kw = query == null ? "" : query.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(SpuEntity::getTitle, kw).or().like(SpuEntity::getSubtitle, kw));
        }
        wrapper.orderByDesc(SpuEntity::getSalesCount).last("LIMIT " + MAX_POOL);
        List<SpuEntity> pool = spuMapper.selectList(wrapper);
        if (pool.isEmpty()) {
            return List.of();
        }

        Map<Long, SpuAiProfileEntity> profiles = profilesOf(pool.stream().map(SpuEntity::getId).toList());
        Map<Long, List<SkuEntity>> skus = skusOf(pool.stream().map(SpuEntity::getId).toList());
        Set<String> excluded = splitCsv(excludeAllergens);

        record Scored(SpuEntity spu, SpuAiProfileEntity profile, List<SkuEntity> skuList, int score) {
        }
        List<Scored> matched = new ArrayList<>();
        for (SpuEntity spu : pool) {
            SpuAiProfileEntity profile = profiles.get(spu.getId());
            List<SkuEntity> skuList = skus.getOrDefault(spu.getId(), List.of());
            if (!matches(spu, profile, skuList, species, lifeStage, budgetMin, budgetMax,
                    excluded, inStockOnly)) {
                continue;
            }
            matched.add(new Scored(spu, profile, skuList, score(spu, profile, kw)));
        }
        matched.sort(Comparator
                .comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.spu().getSalesCount() == null ? 0 : s.spu().getSalesCount(),
                        Comparator.reverseOrder())
                .thenComparing(s -> s.spu().getId(), Comparator.reverseOrder()));

        log.info("AI 商品检索 query={} species={} stage={} pool={} matched={}",
                kw, species, lifeStage, pool.size(), matched.size());
        return matched.stream().limit(size).map(s -> toCard(s.spu(), s.profile(), s.skuList())).toList();
    }

    /** 按 spuIds 回源实时事实；只返回当前可见商品，缺失的 id 由 AI 域视为失效并丢弃 */
    public List<AiProductFact> facts(String spuIdsCsv) {
        List<Long> ids = splitCsv(spuIdsCsv).stream().map(Long::valueOf).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SpuEntity> spus = spuMapper.selectList(visibleSpuWrapper().in(SpuEntity::getId, ids));
        if (spus.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SkuEntity>> skus = skusOf(spus.stream().map(SpuEntity::getId).toList());
        return spus.stream().map(spu -> {
            List<SkuEntity> list = skus.getOrDefault(spu.getId(), List.of());
            List<AiSkuFact> skuFacts = list.stream()
                    .map(s -> new AiSkuFact(s.getId(), specText(s.getSpecJson()), nz(s.getPrice()),
                            s.getStock() == null ? 0 : s.getStock(), s.getStatus()))
                    .toList();
            return new AiProductFact(spu.getId(), spu.getTitle(), spu.getSubtitle(),
                    nz(spu.getMinPrice()), nz(spu.getMaxPrice()), stockOf(list) > 0, skuFacts);
        }).toList();
    }

    private LambdaQueryWrapper<SpuEntity> visibleSpuWrapper() {
        return new LambdaQueryWrapper<SpuEntity>()
                .isNull(SpuEntity::getDeletedAt)
                .eq(SpuEntity::getStatus, "ON_SALE")
                .eq(SpuEntity::getAuditStatus, "APPROVED");
    }

    private Map<Long, SpuAiProfileEntity> profilesOf(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SpuAiProfileEntity> map = new HashMap<>();
        for (SpuAiProfileEntity p : profileMapper.selectBatchIds(spuIds)) {
            map.put(p.getSpuId(), p);
        }
        return map;
    }

    private Map<Long, List<SkuEntity>> skusOf(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> all = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .in(SkuEntity::getSpuId, spuIds)
                .orderByAsc(SkuEntity::getId));
        Map<Long, List<SkuEntity>> map = new HashMap<>();
        for (SkuEntity sku : all) {
            map.computeIfAbsent(sku.getSpuId(), ignored -> new ArrayList<>()).add(sku);
        }
        return map;
    }

    private boolean matches(SpuEntity spu, SpuAiProfileEntity profile, List<SkuEntity> skus,
                            String species, String lifeStage, Long budgetMin, Long budgetMax,
                            Set<String> excluded, boolean inStockOnly) {
        long minPrice = nz(spu.getMinPrice());
        long maxPrice = nz(spu.getMaxPrice());
        if (budgetMax != null && minPrice > budgetMax) {
            return false;
        }
        if (budgetMin != null && maxPrice < budgetMin) {
            return false;
        }
        if (inStockOnly && stockOf(skus) <= 0) {
            return false;
        }
        String resolvedSpecies = speciesOf(spu, profile);
        if (species != null && !species.isBlank() && !resolvedSpecies.isBlank()
                && !species.equalsIgnoreCase(resolvedSpecies)) {
            return false;
        }
        List<String> stages = stagesOf(spu, profile);
        if (lifeStage != null && !lifeStage.isBlank() && !stages.isEmpty()
                && stages.stream().noneMatch(lifeStage::equalsIgnoreCase)) {
            return false;
        }
        if (!excluded.isEmpty()) {
            Set<String> allergens = new LinkedHashSet<>(
                    jsonList(profile == null ? null : profile.getAllergens()));
            for (String e : excluded) {
                if (allergens.contains(e) || (spu.getTitle() != null && spu.getTitle().contains(e))) {
                    return false;
                }
            }
        }
        return true;
    }

    private int score(SpuEntity spu, SpuAiProfileEntity profile, String query) {
        int score = 0;
        if (!query.isBlank()) {
            String title = (spu.getTitle() == null ? "" : spu.getTitle())
                    + (spu.getSubtitle() == null ? "" : spu.getSubtitle());
            if (title.contains(query)) {
                score += 8;
            }
            for (String token : query.split("\\s+")) {
                if (!token.isBlank() && title.contains(token)) {
                    score += 3;
                }
            }
        }
        if (profile != null && profile.getContentSummary() != null) {
            score += 1;
        }
        if (spu.getRating() != null) {
            score += (int) Math.round(spu.getRating().doubleValue());
        }
        return score;
    }

    private AiProductCard toCard(SpuEntity spu, SpuAiProfileEntity profile, List<SkuEntity> skus) {
        return new AiProductCard(
                spu.getId(), spu.getTitle(), spu.getSubtitle(),
                nz(spu.getMinPrice()), nz(spu.getMaxPrice()),
                spu.getSalesCount() == null ? 0 : spu.getSalesCount(),
                spu.getRating() == null ? 0d : spu.getRating().doubleValue(),
                stockOf(skus) > 0,
                speciesOf(spu, profile),
                stagesOf(spu, profile),
                profile == null ? List.of() : jsonList(profile.getAllergens()),
                profile == null ? List.of() : jsonList(profile.getFunctionTags()),
                profile == null ? null : profile.getPackageSize(),
                profile == null ? null : profile.getContentSummary());
    }

    private static int stockOf(List<SkuEntity> skus) {
        return skus.stream().mapToInt(s -> s.getStock() == null ? 0 : s.getStock()).sum();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static String speciesOf(SpuEntity spu, SpuAiProfileEntity profile) {
        if (profile != null && profile.getSpecies() != null && !profile.getSpecies().isBlank()
                && !"other".equalsIgnoreCase(profile.getSpecies())) {
            return profile.getSpecies();
        }
        String title = spu.getTitle() == null ? "" : spu.getTitle();
        if (title.contains("猫")) {
            return "cat";
        }
        if (title.contains("狗") || title.contains("犬")) {
            return "dog";
        }
        if (title.contains("仓鼠") || title.contains("兔") || title.contains("龙猫")) {
            return "small_pet";
        }
        if (title.contains("鸟") || title.contains("鹦鹉")) {
            return "bird";
        }
        if (title.contains("鱼")) {
            return "fish";
        }
        if (title.contains("爬") || title.contains("蜥蜴") || title.contains("龟")) {
            return "reptile";
        }
        return "";
    }

    private static List<String> stagesOf(SpuEntity spu, SpuAiProfileEntity profile) {
        if (profile != null && profile.getLifeStages() != null && !profile.getLifeStages().isBlank()) {
            return Arrays.stream(profile.getLifeStages().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        String title = spu.getTitle() == null ? "" : spu.getTitle();
        if (title.contains("幼") || title.contains("奶糕") || title.contains("离乳")) {
            return List.of("BABY");
        }
        if (title.contains("老年") || title.contains("高龄")) {
            return List.of("SENIOR");
        }
        if (title.contains("成猫") || title.contains("成犬")) {
            return List.of("ADULT");
        }
        return List.of();
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.copyOf(splitCsv(json));
        }
    }

    private static Set<String> splitCsv(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split("[,，;；]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String specText(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return "";
        }
        return specJson.trim();
    }
}
