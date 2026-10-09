package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos;
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
import java.math.RoundingMode;
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
    /** E14 · 审核通过（公开可见的必要条件之一，与 status=ON_SALE 同时成立） */
    private static final String AUDIT_APPROVED = "APPROVED";
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
        List<SpuEntity> spus = spuMapper.selectList(visibleSpuWrapper()
                .select(SpuEntity::getTitle)
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
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper();

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

    /**
     * 商品详情（公开）。
     *
     * <p><b>可见性判定与检索完全一致</b>（E14）：在售 <b>且</b> 审核通过。
     * 详情必须自己做这道闸 —— 否则「商品没过审、但把 {@code /api/products/{id}} 的链接
     * 直接发出去」就绕过了审核，公开列表里看不到的东西照样能被打开。
     * 未过审与不存在<b>同样返回 404</b>（而不是 403）：状态码差异会让人用
     * 「404 与 403 的区别」探测出「这个 id 存在但没过审」，等于把审核结论泄漏出去。
     */
    public ProductDetail detail(Long spuId) {
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null
                || !STATUS_ON_SALE.equals(spu.getStatus())
                || !AUDIT_APPROVED.equals(spu.getAuditStatus())) {
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
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper();
        if (excludeSpuId != null) {
            wrapper.ne(SpuEntity::getId, excludeSpuId);
        }
        wrapper.orderByDesc(SpuEntity::getSalesCount)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    public List<ProductCard> newest(int limit) {
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper()
                .orderByDesc(SpuEntity::getCreatedAt)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    /**
     * 按宠物物种筛选。
     * 这里用 EXISTS 子查询而不是 JOIN，是为了避免同一 SPU 有多个匹配 SKU 时出现重复行。
     */
    public List<ProductCard> bySpecies(String species, int limit) {
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper()
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
        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper()
                .in(SpuEntity::getCategoryId, categoryService.expandCategoryIds(categoryId));
        if (excludeSpuId != null) {
            wrapper.ne(SpuEntity::getId, excludeSpuId);
        }
        wrapper.orderByDesc(SpuEntity::getSalesCount)
                .orderByDesc(SpuEntity::getId);
        return toCards(selectTop(wrapper, limit));
    }

    /**
     * 按 id 批量取卡片（<b>内部取数口，刻意不做可见性过滤</b>）。
     *
     * <p>与 {@link #internalCards} 同一口径：调用方要的是「这些 id 对应什么」，
     * 而不是「这些 id 现在对买家可见吗」。已下单 / 已收藏的商品即便后来下架或
     * 被驳回，历史记录里仍要显示它的标题与图片 —— 在这里按在售过滤会让那些
     * 页面出现「查无此物」的空白。是否需要过滤由调用方按场景决定。
     *
     * <p>因此：<b>新增前台读路径时不要直接复用它</b>，公开检索 / 详情一律走
     * {@link #visibleSpuWrapper}（那条闸门上过测试，见 ProductVisibilityTest）。
     */
    public List<ProductCard> byIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        List<SpuEntity> spus = spuMapper.selectBatchIds(new LinkedHashSet<>(spuIds));
        return toCards(spus);
    }

    /**
     * 批量取商品快照（内部接口，第四十三轮 · 分享圈）。
     *
     * <p>调用方是履约域：发布分享时把商品标题 / 主图 / 最低价落成**快照**，
     * 商品后续改名换图不影响历史分享的展示（与评价快照同一条不变性原理）。
     *
     * <p>不按在售状态过滤：分享指向的商品可能随后下架，但历史分享仍应可看 ——
     * 是否在售由前端跳详情页时自然感知，快照接口不做这道闸。
     */
    public List<ProductDtos.InternalCardView> internalCards(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return spuMapper.selectBatchIds(new LinkedHashSet<>(spuIds)).stream()
                .map(spu -> new ProductDtos.InternalCardView(
                        spu.getId(),
                        spu.getTitle(),
                        spu.getMainImage(),
                        nz(spu.getMinPrice()),
                        // 第四十七轮：社交域「问一问」在服务端定位收件人（SPU 归属卖家）
                        spu.getSellerId(),
                        // 第七十三轮：自营/入驻判定随卡下发 —— 自营商品的 sellerId 归零后，
                        // 「问一问」改按 supplyType 把自营咨询定到官方客服
                        spu.getSupplyType()))
                .toList();
    }

    /**
     * 评分回写（第七十三轮）：履约域评价落库后推送的真实聚合结果落列。
     *
     * <p>为什么由评价域算、本域只落列：聚合口径（平均分、条数、是否含未审核）只在评价域
     * 有一份事实，两边各算一遍迟早漂移。本域对补丁做防御性校验（spuId 必须存在、
     * 数值必须合法），坏补丁跳过并计数，不让一条脏数据拖垮整批。
     *
     * <p>历史包袱清理：t_spu.rating / rating_count 原是种子假数据（与真实评价无关），
     * 本链路上线后该两列的唯一写入口就是这里 —— 展示层据此做到「有真实评价才显示评分」。
     */
    public int recomputeRatings(List<ProductDtos.RatingPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (ProductDtos.RatingPatch patch : patches) {
            if (patch == null || patch.spuId() == null
                    || patch.avgRating() == null || patch.ratingCount() == null
                    || patch.ratingCount() < 0
                    || patch.avgRating() < 0 || patch.avgRating() > 5) {
                log.warn("忽略非法评分补丁: {}", patch);
                continue;
            }
            SpuEntity spu = spuMapper.selectById(patch.spuId());
            if (spu == null) {
                log.warn("评分补丁指向不存在的 SPU {}: 跳过", patch.spuId());
                continue;
            }
            spu.setRating(BigDecimal.valueOf(patch.avgRating()).setScale(1, RoundingMode.HALF_UP));
            spu.setRatingCount(patch.ratingCount());
            spuMapper.updateById(spu);
            applied++;
        }
        return applied;
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
            // E14：可售还要求**审核通过**。前台本来就看不见未过审商品，
            // 但购物车里可能躺着「加购后商品被驳回」的条目 —— 加购校验与下单快照
            // 都读这个字段，把审核维度一起算进来，那种条目就会自然变成「不可售」，
            // 而不是等到下单时才在交易域炸一个语焉不详的错误。
            String spuAudit = spu == null ? null : spu.getAuditStatus();
            boolean sellable = STATUS_ON_SALE.equals(spuStatus)
                    && AUDIT_APPROVED.equals(spuAudit)
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
                    // 采购视图的 sellerId 是**交易归属**口径（第七十三轮）：
                    // 自营商品（self）恒为 0（平台自营）—— 运营的商家端按 {自己, 0} 查单，
                    // 之前透传 t_spu.seller_id（自营=官方客服账号，那是「咨询归属」），
                    // 导致用户买自营商品生成的订单落在一个运营看不见的卖家头上，待发货查不到。
                    // 入驻（merchant）商品仍透传归属卖家，订单进卖家自己的商家端。
                    spu == null ? 0L
                            : ("self".equals(spu.getSupplyType()) ? 0L
                                : (spu.getSellerId() == null ? 0L : spu.getSellerId())));
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

    /**
     * <b>公开可见性的唯一一处定义</b>（E14）：在售 <b>且</b> 审核通过。
     *
     * <p>为什么必须收口成一个方法而不是在各查询里各写一遍：
     * 公开读路径有 7 条（检索 / 详情 / 热门 / 最新 / 物种精选 / 同类推荐 / AI 召回）外加
     * ES 一条 —— 任何人新增一条路径时漏掉审核条件，未过审商品就会从前台冒出来，
     * 而这类缺陷<b>没有任何报错、也不影响其他页面</b>，只能靠用户投诉发现。
     * 收口之后「加过滤」是一个不动脑的动作（起手用这个方法），
     * 并由 ProductVisibilityTest 反向钉住：往库里塞一条 PENDING 商品，
     * 每条路径都必须查不到它。
     *
     * <p>与 C2C 闲置 {@code ListingService.visibleWrapper()} 同一范式、同一口径
     * （status=ON_SALE 且 auditStatus=APPROVED），全站商品可见性只有一种解释。
     *
     * <p>平台自营商品（supply_type='self'）的 audit_status 恒为 APPROVED，
     * 所以本方法对自营商品等价于「只看在售」—— 免审商品不会被这道闸挡住。
     */
    private LambdaQueryWrapper<SpuEntity> visibleSpuWrapper() {
        return new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .eq(SpuEntity::getAuditStatus, AUDIT_APPROVED);
    }

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
     *
     * <p>（无约束重载：保留给不喜欢带条件的调用方，行为与第五十七轮之前完全一致）
     */
    public List<ProductCard> internalCandidates(String message, int limit) {
        return internalCandidates(message, limit, null, null, null);
    }

    /**
     * A9 · 候选召回（**带结构化约束**，第五十七轮）。
     *
     * <p><b>为什么约束必须在这里过滤，而不是写在提示词里</b>：提示词约束的是「模型的措辞」，
     * 不是「模型能看到什么」。用户说「预算 200 以内」而候选里躺着 328 元的商品时，
     * 模型一旦不听话就会把它推出去 —— 而这是用户**一眼就能发现**的那种错。
     * 所以预算与品类在这里变成 SQL 条件：**模型根本看不到超预算的商品**，
     * 也就没有「推错」的可能。这与「不得编造商品」的 id 反查是同一种思路：
     * 把正确性做成结构，而不是做成提醒。
     *
     * <p><b>价格语义（容易搞反，写清楚）</b>：商品有价格区间 [minPrice, maxPrice]（分）。
     * <ul>
     *   <li>{@code budgetMax}（用户预算上限，如「200 元以内」）→ 条件是
     *       {@code spu.min_price <= budgetMax}：「这个商品里有 200 元以内的选择」就算符合 ——
     *       一个 199~299 的商品对「200 以内」的预算是有意义的（最低配 199 能买到）；</li>
     *   <li>{@code budgetMin}（用户预算下限，如「200 元以上」）→ 条件是
     *       {@code spu.max_price >= budgetMin}：对称地，「这个商品里有 200 元以上的选择」。</li>
     * </ul>
     * 反过来写（用 min_price 判下限）会把「199~299」这种商品误杀，而把「299~399」误放 ——
     * 这正是「看起来对、实际反了」的典型。
     *
     * <p><b>品类会展开子类目</b>：用户说的「猫粮」通常是一级类目，直接按它过滤会一条都查不到；
     * 复用 {@code CategoryService.expandCategoryIds} 把它展开成「自己 + 子类目」再过滤。
     *
     * <p><b>为什么不用 {@code having} 之类的后置过滤</b>：后置过滤会让「池子 60 → 过滤后剩 2」
     * 这种形态变成常态，重排失去意义。条件放在 SQL 里，池子本身就是符合条件的。
     */
    public List<ProductCard> internalCandidates(String message, int limit,
                                                Long budgetMin, Long budgetMax,
                                                List<Long> categoryIds) {
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

        LambdaQueryWrapper<SpuEntity> wrapper = visibleSpuWrapper();
        // ★ 约束（第五十七轮）：在 SQL 层过滤，模型看不到不符合条件的商品
        if (budgetMax != null) {
            wrapper.le(SpuEntity::getMinPrice, budgetMax);
        }
        if (budgetMin != null) {
            wrapper.ge(SpuEntity::getMaxPrice, budgetMin);
        }
        List<Long> expanded = expandCategories(categoryIds);
        if (!expanded.isEmpty()) {
            wrapper.in(SpuEntity::getCategoryId, expanded);
        }
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

    /**
     * 类目简表（内部接口，第五十七轮 · 供 AI 域用）。
     *
     * <p>AI 域拿它做两件事：从用户话里认出品类、生成澄清问题的可点选项。
     * 只回启用中的类目（{@code CategoryService.flat()} 已保证），
     * 否则「AI 推荐了一个已停用的类目」会变成新的脏数据来源。
     */
    public List<ProductDtos.CategoryBrief> internalCategories() {
        return categoryService.flat().stream()
                .map(n -> new ProductDtos.CategoryBrief(n.id(), n.name(), n.depth()))
                .toList();
    }

    /** 把「可能是父类目」的 id 列表展开成「自己 + 子类目」的并集（去重、保序） */
    private List<Long> expandCategories(List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : categoryIds) {
            if (id == null) {
                continue;
            }
            ids.addAll(categoryService.expandCategoryIds(id));
        }
        return List.copyOf(ids);
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
