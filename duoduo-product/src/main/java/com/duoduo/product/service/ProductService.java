package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductDtos.ProductDetail;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
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
import org.springframework.stereotype.Service;

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

    public ProductService(SpuMapper spuMapper,
                          SkuMapper skuMapper,
                          CategoryService categoryService,
                          CatalogLookup catalogLookup,
                          BannedWordGuard bannedWordGuard,
                          ObjectMapper objectMapper) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.categoryService = categoryService;
        this.catalogLookup = catalogLookup;
        this.bannedWordGuard = bannedWordGuard;
        this.objectMapper = objectMapper;
    }

    // ---------------- 检索 ----------------

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
            wrapper.apply("EXISTS (SELECT 1 FROM t_sku s WHERE s.spu_id = t_spu.id"
                    + " AND s.status = 'ACTIVE' AND s.stock > 0)");
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
                        Boolean.TRUE.equals(inStock.get(spu.getId()))))
                .toList();
    }

    private Map<Long, Boolean> inStockMap(Collection<Long> spuIds) {
        if (spuIds.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .select(SkuEntity::getSpuId, SkuEntity::getStock)
                .in(SkuEntity::getSpuId, spuIds)
                .eq(SkuEntity::getStatus, SKU_ACTIVE));
        Map<Long, Boolean> result = new HashMap<>();
        for (SkuEntity sku : skus) {
            boolean available = sku.getStock() != null && sku.getStock() > 0;
            result.merge(sku.getSpuId(), available, (a, b) -> a || b);
        }
        return result;
    }

    private SkuView toSkuView(SkuEntity sku) {
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        boolean food = sku.getIsFood() != null && sku.getIsFood() == 1;
        boolean sellable = SKU_ACTIVE.equals(sku.getStatus()) && stock > 0;
        return new SkuView(
                sku.getId(),
                sku.getSkuCode(),
                parseSpec(sku.getSpecJson()),
                sku.getImage(),
                nz(sku.getPrice()),
                nz(sku.getMarketPrice()),
                stock,
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
