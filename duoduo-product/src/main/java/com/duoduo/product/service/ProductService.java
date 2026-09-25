package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductDtos.ProductDetail;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
import com.duoduo.product.support.search.SearchIndexer;
import com.duoduo.product.dto.ProductDtos.SkuPurchaseView;
import com.duoduo.product.dto.ProductDtos.SkuView;
import com.duoduo.product.dto.ProductDtos.Suitability;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.CatalogLookup;
import com.duoduo.product.support.Csv;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品检索与详情。
 *
 * <p>三个刻意的工程决策：
 * <ol>
 *   <li><b>不 JOIN SKU</b>：列表页只查 SPU（价格区间已冗余），需要「是否有货」时
 *       按当前页的 spuId 集合<b>批量</b>查一次 SKU，把 N 次查询压成 1 次</li>
 *   <li><b>分页必须稳定</b>：所有排序都追加 id 作为兜底排序键，否则同销量/同价格的商品
 *       在翻页时可能重复出现或整条漏掉（MySQL 不保证同值行的顺序）</li>
 *   <li><b>价格区间是「相交」语义</b>：筛选 100 元以上时，99~199 的套装应当命中，
 *       所以条件是 {@code maxPrice >= min && minPrice <= max}，而不是很常见的错误写法
 *       {@code minPrice >= min}</li>
 * </ol>
 */
@Service
public class ProductService {

    /** 与 ES 的 index.max_result_window 默认值对齐；超出即拒绝，避免深分页把 ES 打爆 */
    private static final int MAX_RESULT_WINDOW = 10000;

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String SKU_ACTIVE = "ACTIVE";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Set<String> ALLOWED_SORTS =
            Set.of("default", "price_asc", "price_desc", "sales", "rating", "newest");

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final CategoryService categoryService;
    private final CatalogLookup catalogLookup;
    private final BannedWordGuard bannedWordGuard;
    private final ObjectMapper objectMapper;
    /** A10 · ES 检索引擎（可选：开关关闭时 bean 不存在，检索一律走 MySQL） */
    private final org.springframework.beans.factory.ObjectProvider<
            com.duoduo.product.support.search.EsSearchEngine> esSearchProvider;

    public ProductService(SpuMapper spuMapper,
                          SkuMapper skuMapper,
                          CategoryService categoryService,
                          CatalogLookup catalogLookup,
                          BannedWordGuard bannedWordGuard,
                          ObjectMapper objectMapper,
                          org.springframework.beans.factory.ObjectProvider<
                                  com.duoduo.product.support.search.EsSearchEngine> esSearchProvider) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.categoryService = categoryService;
        this.catalogLookup = catalogLookup;
        this.bannedWordGuard = bannedWordGuard;
        this.objectMapper = objectMapper;
        this.esSearchProvider = esSearchProvider;
    }

    // ---------------- 检索 ----------------

    // ── 搜索纠错（A14 · P3）────────────────────────────────────────────────
    //
    // 关键教训（实测得出）：**词表粒度决定纠错能不能用**。
    // 第一版只把「标题按空格切出的整词」放进词表，切出来的是「幼猫粮」「冻干双拼全价猫粮」这类长词，
    // 而用户打的是「猫粮」「冻干」——词表里没有 → 纠错永远给不出建议（更糟：「猫粮」被当错词，错配成「猫主粮」）。
    // 因此词表 = 词本身 + 其 **2~4 字连续子串**，并预建**拼音索引**（同音命中 O(1)）与**长度索引**
    // （形近只比长度 ±1 的桶），既准又快。
    private volatile Set<String> correctionWordSet = Set.of();
    private volatile Map<String, List<String>> correctionByPinyin = Map.of();
    private volatile Map<Integer, List<String>> correctionByLength = Map.of();
    private volatile long correctionBuiltAt = 0L;
    private static final long CORRECTION_TTL_MS = 5 * 60 * 1000L;

    /**
     * 搜索纠错建议：给「搜不到东西」的输入找一个更可能的词（前端据此提示「你是不是想搜 X」）。
     *
     * <p><b>词表零维护成本</b>：品牌名 + 分类名 + 在售商品标题切词，全部来自库内既有数据。
     *
     * <p>两级判定，按「更符合中文输入习惯」排序：
     * <ol>
     *   <li><b>拼音完全相同</b>（复用 {@link SearchIndexer#pinyin}）—— 中文错误绝大多数是拼音输入法
     *       选错字（「猫良」→「猫粮」），查预建索引 O(1)；</li>
     *   <li><b>形近</b>：长度差 ≤ 1、至少共享一个汉字、编辑距离 ≤ max(1, 长度/3)。</li>
     * </ol>
     * 给不出靠谱候选时返回 {@code null} —— 宁可不建议，也不要给错建议。
     */
    public String suggestKeyword(String rawKeyword) {
        String kw = trimToNull(rawKeyword);
        if (kw == null || kw.length() < 2 || kw.length() > 12) {
            return null; // 单字 / 长句都不做纠错
        }
        ensureCorrectionIndex();
        if (correctionWordSet.contains(kw)) {
            return null; // 词表里本来就有这个说法 → 输入没问题
        }

        // ① 同音优先
        List<String> same = correctionByPinyin.get(SearchIndexer.pinyin(kw));
        if (same != null && !same.isEmpty()) {
            String pick = pickClosest(same, kw);
            if (pick != null) {
                return pick;
            }
        }

        // ② 形近：只扫长度 ±1 的桶，且要求共享至少一个汉字（先筛掉绝大多数字）
        int limit = Math.max(1, kw.length() / 3);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int len = Math.max(2, kw.length() - 1); len <= kw.length() + 1; len++) {
            for (String cand : correctionByLength.getOrDefault(len, List.of())) {
                if (!shareAnyChar(kw, cand)) {
                    continue;
                }
                int dist = editDistance(kw, cand);
                if (dist <= limit && dist < bestDist) {
                    bestDist = dist;
                    best = cand;
                }
            }
        }
        return best;
    }

    /** 同音候选里挑最贴近的：先看有没有共同字，再取最短的（更可能是用户本意） */
    private static String pickClosest(List<String> candidates, String kw) {
        String hit = null;
        String shortest = null;
        for (String cand : candidates) {
            if (cand.equals(kw)) {
                return null; // 同音词里就有原词 → 不算错误输入
            }
            if (shareAnyChar(kw, cand) && (hit == null || cand.length() < hit.length())) {
                hit = cand;
            }
            if (shortest == null || cand.length() < shortest.length()) {
                shortest = cand;
            }
        }
        return hit != null ? hit : shortest;
    }

    private static boolean shareAnyChar(String a, String b) {
        for (int i = 0; i < a.length(); i++) {
            if (b.indexOf(a.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 构建纠错索引（5 分钟 TTL 懒加载） */
    private void ensureCorrectionIndex() {
        long now = System.currentTimeMillis();
        if (now - correctionBuiltAt < CORRECTION_TTL_MS && !correctionWordSet.isEmpty()) {
            return;
        }
        Set<String> words = new LinkedHashSet<>();
        categoryService.brands().forEach(b -> addCorrectionWord(words, b.name()));
        categoryService.flat().forEach(c -> addCorrectionWord(words, c.name()));
        List<SpuEntity> spus = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                .select(SpuEntity::getTitle)
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .last("LIMIT 300"));
        for (SpuEntity spu : spus) {
            String title = spu.getTitle() == null ? "" : spu.getTitle();
            for (String part : title.split("[\\s,，、/×]+")) {
                addCorrectionWord(words, part);
            }
        }

        Map<String, List<String>> byPinyin = new HashMap<>();
        Map<Integer, List<String>> byLength = new HashMap<>();
        for (String w : words) {
            String py = SearchIndexer.pinyin(w);
            if (!py.isEmpty()) {
                byPinyin.computeIfAbsent(py, k -> new ArrayList<>()).add(w);
            }
            byLength.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
        }
        correctionWordSet = Set.copyOf(words);
        correctionByPinyin = byPinyin;
        correctionByLength = byLength;
        correctionBuiltAt = now;
        log.info("纠错词表已构建 词数={} 同音组={}", words.size(), byPinyin.size());
    }

    /**
     * 词 + 其 2~4 字连续子串。
     *
     * <p>为什么要子串：用户输入的是「猫粮」「冻干」这类短词，而标题按空格切出来的往往是
     * 「幼猫粮」「冻干双拼全价猫粮」——只收整词，短词就永远不在词表里，纠错等于失效。
     */
    private static void addCorrectionWord(Set<String> words, String raw) {
        String w = raw == null ? "" : raw.trim();
        if (w.length() < 2) {
            return;
        }
        if (w.length() <= 8) {
            words.add(w);
        }
        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i + len <= w.length(); i++) {
                words.add(w.substring(i, i + len));
            }
        }
    }

    /** 标准 Levenshtein 编辑距离（字符级）；两边都 ≤ 12 字，无需滚动数组优化 */
    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    public PageResult<ProductCard> search(ProductQuery query) {
        int pageNo = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.pageSize(), MAX_PAGE_SIZE);

        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice() > query.maxPrice()) {
            throw BizException.validation("最低价不能高于最高价");
        }
        if (query.supplyType() != null && !query.supplyType().isBlank()
                && !Set.of("self", "merchant").contains(query.supplyType().trim())) {
            throw BizException.validation("supplyType 仅支持 self 或 merchant");
        }
        bannedWordGuard.rejectIfHit(query.keyword(), "搜索词");

        // 深分页保护：ES 的 index.max_result_window 默认 10000，from+size 超出会**直接抛错**。
        // 两套实现统一在入口拦，避免「开 ES 报 5xx、关 ES 正常」的口径分裂；
        // 真要做更深的分页应改用 search_after（游标），而不是放大 max_result_window。
        long offset = (long) (pageNo - 1) * pageSize;
        if (offset + pageSize > MAX_RESULT_WINDOW) {
            throw BizException.validation("结果过多，请缩小范围或增加筛选条件（最多翻到第 "
                    + (MAX_RESULT_WINDOW / pageSize) + " 页）");
        }

        // A10 · ES 优先：同义词 / 拼音容错在这里兑现；**任何 ES 异常都原地降级回 MySQL**（验收④）——
        // 降级不是错误分支，是检索的常规运行形态之一，因此只记 WARN 不抛 5xx
        long startedAt = System.nanoTime();
        var esSearch = esSearchProvider.getIfAvailable();
        if (esSearch != null && StringUtils.hasText(query.keyword())) {
            try {
                var expanded = query.categoryId() == null ? null
                        : categoryService.expandCategoryIds(query.categoryId());
                var hits = esSearch.searchProducts(query, expanded, pageNo, pageSize);
                List<SpuEntity> records = hits.ids().isEmpty()
                        ? List.of()
                        : orderByIds(spuMapper.selectBatchIds(hits.ids()), hits.ids());
                // 检索行为日志：分析"哪些词搜不到精确结果""搜索慢在哪一步"的第一手数据
                // （looseFallback=true 表示严格召回为零、结果来自逐字兜底 —— 这类词值得补同义词）
                log.info("商品检索 keyword={} 引擎=es 命中={} 兜底={} 页={}/{} 返回={} 耗时={}ms",
                        query.keyword(), hits.total(), hits.looseFallback(), pageNo, pageSize,
                        records.size(), (System.nanoTime() - startedAt) / 1_000_000);
                return PageResult.of(toCards(records), hits.total(), pageNo, pageSize);
            } catch (Exception ex) {
                log.warn("ES 检索失败，降级 MySQL（设计内行为）: {}", ex.getMessage());
            }
        }

        PageResult<ProductCard> fallback = searchByMysql(query, pageNo, pageSize);
        if (StringUtils.hasText(query.keyword())) {
            log.info("商品检索 keyword={} 引擎=mysql 命中={} 页={}/{} 耗时={}ms",
                    query.keyword(), fallback.total(), pageNo, pageSize,
                    (System.nanoTime() - startedAt) / 1_000_000);
        }
        return fallback;
    }

    /** MySQL 权威路径（ES 未启用 / 降级时的实现，与 ES 查询语义一一对应） */
    private PageResult<ProductCard> searchByMysql(ProductQuery query, int pageNo, int pageSize) {
        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpuEntity::getStatus, STATUS_ON_SALE);

        String keyword = trimToNull(query.keyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(SpuEntity::getTitle, keyword).or().like(SpuEntity::getSubtitle, keyword));
        }
        if (query.categoryId() != null) {
            // 点一级类目时展开其子类目，符合导航预期
            wrapper.in(SpuEntity::getCategoryId, categoryService.expandCategoryIds(query.categoryId()));
        }
        if (query.brandId() != null) {
            wrapper.eq(SpuEntity::getBrandId, query.brandId());
        }
        String supplyType = trimToNull(query.supplyType());
        if (supplyType != null) {
            wrapper.eq(SpuEntity::getSupplyType, supplyType);
        }
        // 区间相交语义（见类注释第 3 点）
        if (query.minPrice() != null) {
            wrapper.ge(SpuEntity::getMaxPrice, query.minPrice());
        }
        if (query.maxPrice() != null) {
            wrapper.le(SpuEntity::getMinPrice, query.maxPrice());
        }
        String species = trimToNull(query.species());
        if (species != null) {
            wrapper.apply("EXISTS (SELECT 1 FROM t_sku s WHERE s.spu_id = t_spu.id"
                    + " AND s.status = 'ACTIVE' AND s.target_species LIKE {0})", "%" + species + "%");
        }
        String lifeStage = trimToNull(query.lifeStage());
        if (lifeStage != null) {
            wrapper.apply("EXISTS (SELECT 1 FROM t_sku s WHERE s.spu_id = t_spu.id"
                    + " AND s.status = 'ACTIVE' AND s.target_life_stage LIKE {0})", "%" + lifeStage + "%");
        }
        if (query.inStockOnly()) {
            // 可售余量 = stock - frozen_stock：只看 stock 会把「已被他人下单锁定」的商品也算作有货
            wrapper.apply("EXISTS (SELECT 1 FROM t_sku s WHERE s.spu_id = t_spu.id"
                    + " AND s.status = 'ACTIVE'"
                    + " AND s.stock - COALESCE(s.frozen_stock, 0) > 0)");
        }
        applySort(wrapper, query.sort());
        wrapper.orderByDesc(SpuEntity::getId);

        Page<SpuEntity> page = spuMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(toCards(page.getRecords()), page.getTotal(), pageNo, pageSize);
    }

    // ---------------- 详情 ----------------

    public ProductDetail detail(Long spuId) {
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null || !STATUS_ON_SALE.equals(spu.getStatus())) {
            throw BizException.notFound("商品");
        }

        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .eq(SkuEntity::getSpuId, spuId)
                .eq(SkuEntity::getStatus, SKU_ACTIVE)
                .orderByAsc(SkuEntity::getPrice));

        List<String> images = new ArrayList<>(Csv.split(spu.getImages()));
        if (images.isEmpty() && spu.getMainImage() != null) {
            images.add(spu.getMainImage());
        }

        return new ProductDetail(
                toCards(List.of(spu)).get(0),
                spu.getDetail(),
                images,
                skus.stream().map(this::toSkuView).toList(),
                aggregateSuitability(skus),
                byCategory(spu.getCategoryId(), spu.getId(), 6),
                topBySales(6, spu.getId()));
    }

    // ---------------- 供首页 / 推荐复用 ----------------

    public List<ProductCard> topBySales(int limit) {
        return topBySales(limit, null);
    }

    /** 按销量取热门商品；{@code excludeSpuId} 用于详情页「同类推荐」排除自身 */
    public List<ProductCard> topBySales(int limit, Long excludeSpuId) {
        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE);
        if (excludeSpuId != null) {
            wrapper.ne(SpuEntity::getId, excludeSpuId);
        }
        wrapper.orderByDesc(SpuEntity::getSalesCount)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    public List<ProductCard> newest(int limit) {
        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .orderByDesc(SpuEntity::getCreatedAt)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    /**
     * 按宠物物种筛选。
     * 这里用 EXISTS 子查询而不是 JOIN，是为了避免同一 SPU 有多个匹配 SKU 时出现重复行。
     */
    public List<ProductCard> bySpecies(String species, int limit) {
        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .apply("EXISTS (SELECT 1 FROM t_sku s WHERE s.spu_id = t_spu.id"
                        + " AND s.status = 'ACTIVE' AND s.target_species LIKE {0})", "%" + species + "%")
                .orderByDesc(SpuEntity::getSalesCount)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    public List<ProductCard> byCategory(Long categoryId, Long excludeSpuId, int limit) {
        if (categoryId == null) {
            return List.of();
        }
        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .in(SpuEntity::getCategoryId, categoryService.expandCategoryIds(categoryId));
        if (excludeSpuId != null) {
            wrapper.ne(SpuEntity::getId, excludeSpuId);
        }
        wrapper.orderByDesc(SpuEntity::getSalesCount)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    public List<ProductCard> byIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        List<SpuEntity> spus = spuMapper.selectBatchIds(new LinkedHashSet<>(spuIds));
        return toCards(spus);
    }

    // ---------------- 供交易服务调用（内部接口） ----------------

    /**
     * 批量取 SKU 采购视图。
     *
     * <p>调用方是交易服务（加购校验、下单快照）。设计要点：
     * <ul>
     *   <li><b>批量</b>：购物车一次可能涉及几十个 SKU，逐条查询会把跨服务调用放大成 N 次</li>
     *   <li><b>查不到不报错</b>：SKU 可能已物理删除，此时返回空列表由调用方判定为「商品不存在」，
     *       比让整个购物车接口 500 更合理</li>
     *   <li><b>不 JOIN</b>：SKU 与 SPU 分两次批量查，避免 JOIN 写法在分库分表后失效</li>
     * </ul>
     */
    public List<SkuPurchaseView> purchaseViews(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<SkuEntity> skus = skuMapper.selectBatchIds(new LinkedHashSet<>(skuIds));
        if (skus.isEmpty()) {
            return List.of();
        }
        Set<Long> spuIds = skus.stream()
                .map(SkuEntity::getSpuId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SpuEntity> spuMap = spuIds.isEmpty()
                ? Map.of()
                : spuMapper.selectBatchIds(spuIds).stream()
                        .collect(Collectors.toMap(SpuEntity::getId, s -> s, (a, b) -> a));

        return skus.stream().map(sku -> {
            SpuEntity spu = spuMap.get(sku.getSpuId());
            int stock = sku.getStock() == null ? 0 : sku.getStock();
            int frozen = sku.getFrozenStock() == null ? 0 : sku.getFrozenStock();
            int available = Math.max(0, stock - frozen);
            String spuStatus = spu == null ? "OFF_SHELF" : spu.getStatus();
            boolean sellable = STATUS_ON_SALE.equals(spuStatus)
                    && SKU_ACTIVE.equals(sku.getStatus())
                    && available > 0;
            String image = sku.getImage() != null && !sku.getImage().isBlank()
                    ? sku.getImage()
                    : (spu == null ? null : spu.getMainImage());
            return new SkuPurchaseView(
                    sku.getId(),
                    sku.getSpuId(),
                    spu == null ? null : spu.getTitle(),
                    specText(sku.getSpecJson()),
                    image,
                    nz(sku.getPrice()),
                    available,
                    sellable,
                    spuStatus,
                    sku.getStatus(),
                    spu == null ? 0L : (spu.getSellerId() == null ? 0L : spu.getSellerId()));
        }).toList();
    }

    /** 把规格 JSON 拍平成「规格:2kg / 口味:冻干双拼」这样的可读串，前端直接展示 */
    private String specText(String specJson) {
        Map<String, String> spec = parseSpec(specJson);
        if (spec.isEmpty()) {
            return null;
        }
        return spec.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(" / "));
    }

    // ---------------- 内部实现 ----------------

    /** 只取前 N 条，不执行 count 查询（列表页之外的场景不需要总数） */
    private List<SpuEntity> selectTop(LambdaQueryWrapper<SpuEntity> wrapper, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        Page<SpuEntity> page = new Page<>(1, size, false);
        return spuMapper.selectPage(page, wrapper).getRecords();
    }

    private void applySort(LambdaQueryWrapper<SpuEntity> wrapper, String sort) {
        String value = (sort == null || sort.isBlank()) ? "default" : sort.trim();
        if (!ALLOWED_SORTS.contains(value)) {
            throw BizException.validation("不支持的排序方式：" + value
                    + "，可选 " + String.join("/", ALLOWED_SORTS));
        }
        switch (value) {
            case "price_asc" -> wrapper.orderByAsc(SpuEntity::getMinPrice);
            case "price_desc" -> wrapper.orderByDesc(SpuEntity::getMinPrice);
            case "sales" -> wrapper.orderByDesc(SpuEntity::getSalesCount);
            case "rating" -> wrapper.orderByDesc(SpuEntity::getRating);
            case "newest" -> wrapper.orderByDesc(SpuEntity::getCreatedAt);
            default -> wrapper.orderByDesc(SpuEntity::getSalesCount);
        }
    }

    /**
     * Entity -> Card 的批量装配。
     * 类目名、品牌名、有货状态都按整页 id 一次性取回，杜绝逐行查询。
     */
    private List<ProductCard> toCards(List<SpuEntity> spus) {
        if (spus == null || spus.isEmpty()) {
            return List.of();
        }
        Set<Long> spuIds = spus.stream().map(SpuEntity::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Boolean> inStock = inStockMap(spuIds);
        Map<Long, String> categoryNames = catalogLookup.categoryNames(
                spus.stream().map(SpuEntity::getCategoryId).toList());
        Map<Long, String> brandNames = catalogLookup.brandNames(
                spus.stream().map(SpuEntity::getBrandId).toList());

        return spus.stream()
                .map(spu -> new ProductCard(
                        spu.getId(),
                        spu.getTitle(),
                        spu.getSubtitle(),
                        spu.getCategoryId(),
                        categoryNames.get(spu.getCategoryId()),
                        spu.getBrandId(),
                        brandNames.get(spu.getBrandId()),
                        spu.getSupplyType(),
                        spu.getMainImage(),
                        nz(spu.getMinPrice()),
                        nz(spu.getMaxPrice()),
                        spu.getSalesCount() == null ? 0 : spu.getSalesCount(),
                        spu.getRating() == null ? 0d : spu.getRating().doubleValue(),
                        spu.getRatingCount() == null ? 0 : spu.getRatingCount(),
                        Boolean.TRUE.equals(inStock.get(spu.getId())),
                        spu.getSellerId() == null ? 0L : spu.getSellerId()))
                .toList();
    }

    private Map<Long, Boolean> inStockMap(Collection<Long> spuIds) {
        if (spuIds.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .select(SkuEntity::getSpuId, SkuEntity::getStock, SkuEntity::getFrozenStock)
                .in(SkuEntity::getSpuId, spuIds)
                .eq(SkuEntity::getStatus, SKU_ACTIVE));
        Map<Long, Boolean> result = new HashMap<>();
        for (SkuEntity sku : skus) {
            int stock = sku.getStock() == null ? 0 : sku.getStock();
            int frozen = sku.getFrozenStock() == null ? 0 : sku.getFrozenStock();
            boolean available = stock - frozen > 0;
            result.merge(sku.getSpuId(), available, (a, b) -> a || b);
        }
        return result;
    }

    private SkuView toSkuView(SkuEntity sku) {
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        int frozen = sku.getFrozenStock() == null ? 0 : sku.getFrozenStock();
        int available = Math.max(0, stock - frozen);
        boolean food = sku.getIsFood() != null && sku.getIsFood() == 1;
        // 可售 = 已上架 且 还有可售余量（被他人下单冻结的部分不算可售）
        boolean sellable = SKU_ACTIVE.equals(sku.getStatus()) && available > 0;
        return new SkuView(
                sku.getId(),
                sku.getSkuCode(),
                parseSpec(sku.getSpecJson()),
                sku.getImage(),
                nz(sku.getPrice()),
                nz(sku.getMarketPrice()),
                available,
                food,
                Csv.split(sku.getTargetSpecies()),
                Csv.split(sku.getTargetLifeStage()),
                Csv.split(sku.getAllergens()),
                sellable);
    }

    /** 把 SKU 维度的适用属性聚合成商品维度，供「这只宠物能不能吃」的判定 */
    private Suitability aggregateSuitability(List<SkuEntity> skus) {
        boolean food = skus.stream().anyMatch(s -> s.getIsFood() != null && s.getIsFood() == 1);
        Set<String> species = new LinkedHashSet<>();
        Set<String> stages = new LinkedHashSet<>();
        Set<String> allergens = new LinkedHashSet<>();
        for (SkuEntity sku : skus) {
            species.addAll(Csv.split(sku.getTargetSpecies()));
            stages.addAll(Csv.split(sku.getTargetLifeStage()));
            allergens.addAll(Csv.split(sku.getAllergens()));
        }
        return new Suitability(food, List.copyOf(species), List.copyOf(stages), List.copyOf(allergens));
    }

    private Map<String, String> parseSpec(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            // 规格串损坏不应该让整页商品挂掉，降级为空规格
            return Map.of();
        }
    }

    // ---------------- 内部接口（服务间，X-Internal-Token 保护） ----------------

    /**
     * A9 · AI 导购的候选召回（取数口，供 duoduo-ai 经内部凭据调用）。
     *
     * <p>MySQL LIKE 没有分词器，怎么从一句自然语言里召回候选：
     * 把消息压掉标点后切 **2~3 字滑窗词元**，OR 匹配标题 / 副标题 ——
     * 真实意图词（「幼犬」「狗粮」）必然是某个窗口的子串；切出来的无意义片段
     * （「推荐」→「荐幼」）匹配不到东西，是零成本的噪声。命中后再按
     * 「命中词元数 + 销量」重排，噪声自然沉底，不需要真的分词器。
     *
     * <p>只回在售商品；上限 8 条 —— 候选列表是给 LLM 组织回复用的素材，
 * 不是搜索结果页，多了反而稀释推荐质量。
     */
    public List<ProductCard> internalCandidates(String message, int limit) {
        String msg = trimToNull(message);
        if (msg == null) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, 10));
        String compact = msg.replaceAll("[，。！？、,.!?\\s]", "");
        LinkedHashSet<String> grams = new LinkedHashSet<>();
        if (compact.length() <= 12 && compact.length() >= 2) {
            grams.add(compact);
        }
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= compact.length(); i++) {
                grams.add(compact.substring(i, i + n));
            }
        }
        if (grams.isEmpty()) {
            return List.of();
        }
        List<String> tokens = grams.stream().limit(15).toList();

        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE);
        wrapper.and(outer -> {
            for (String g : tokens) {
                outer.or(i -> i.like(SpuEntity::getTitle, g).or().like(SpuEntity::getSubtitle, g));
            }
        });
        wrapper.last("LIMIT 60");
        List<SpuEntity> pool = spuMapper.selectList(wrapper);
        if (pool.isEmpty()) {
            return List.of();
        }
        // 重排：命中的词元越多越相关，同分看销量
        pool.sort((a, b) -> {
            int sa = matchedTokens(a, tokens);
            int sb = matchedTokens(b, tokens);
            if (sa != sb) {
                return Integer.compare(sb, sa);
            }
            return Integer.compare(
                    b.getSalesCount() == null ? 0 : b.getSalesCount(),
                    a.getSalesCount() == null ? 0 : a.getSalesCount());
        });
        return toCards(pool.subList(0, Math.min(size, pool.size())));
    }

    private static int matchedTokens(SpuEntity spu, List<String> tokens) {
        String text = (spu.getTitle() == null ? "" : spu.getTitle())
                + (spu.getSubtitle() == null ? "" : spu.getSubtitle());
        int count = 0;
        for (String t : tokens) {
            if (text.contains(t)) {
                count++;
            }
        }
        return count;
    }

    // ---------------- 内部辅助 ----------------

    /** A10：按 ES 返回的 id 顺序还原记录顺序（相关性 / 排序语义都在 id 列表里） */
    private List<SpuEntity> orderByIds(List<SpuEntity> spus, List<Long> orderedIds) {
        Map<Long, SpuEntity> byId = spus.stream().collect(Collectors.toMap(SpuEntity::getId, s -> s, (a, b) -> a));
        return orderedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    @SuppressWarnings("unused")
    private static double nz(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }
}
