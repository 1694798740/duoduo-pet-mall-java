package com.duoduo.product.dto;

import java.util.List;
import java.util.Map;

/**
 * 商品域对外 DTO 集合。
 *
 * <p>约定：
 * <ul>
 *   <li>金额一律是整数「分」，字段名以 Price 结尾的不做小数转换，由前端展示层格式化</li>
 *   <li>多值属性（物种 / 生命阶段 / 过敏原）一律以数组返回，前端不需要再拆逗号串</li>
 *   <li>Service 层只面对这些 record，Entity 不逃逸出仓储层</li>
 * </ul>
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    // ---------------- 类目 / 品牌 ----------------

    /** 类目节点（两级树） */
    public record CategoryNode(
            Long id,
            String name,
            Long parentId,
            int depth,
            String icon,
            List<CategoryNode> children
    ) {
    }

    public record BrandView(
            Long id,
            String name,
            String logo,
            String country,
            String story
    ) {
    }

    /** 字典项（物种 / 生命阶段 / 成色），前端筛选器直接消费 */
    public record DictItem(String code, String label) {
    }

    // ---------------- 商品 ----------------

    /**
     * SKU 视图。
     * {@code sellable} 是服务端算好的「是否可下单」，避免前端把「库存为 0」和「已下架」两套规则各写一遍。
     */
    public record SkuView(
            Long id,
            String skuCode,
            Map<String, String> spec,
            String image,
            long price,
            long marketPrice,
            int stock,
            boolean food,
            List<String> species,
            List<String> lifeStages,
            List<String> allergens,
            boolean sellable
    ) {
    }

    /**
     * 采购视图：交易服务加购 / 下单时需要的 SKU 快照。
     *
     * <p>刻意与 C 端 {@link SkuView} 分开：C 端不需要「可售库存」与原始状态码，
     * 采购方也不需要过敏原等展示字段 —— 两边字段混用会让任一侧的改动都变得危险。
     *
     * <p>{@code availableStock} 是 {@code stock - frozenStock}，即「现在还能买几件」。
     * 库存的冻结与扣减由交易服务负责，商品服务只读。
     */
    public record SkuPurchaseView(
            Long skuId,
            Long spuId,
            String spuTitle,
            String specText,
            String image,
            long price,
            int availableStock,
            boolean sellable,
            String spuStatus,
            String skuStatus
    ) {
    }

    /** 列表卡片：字段刻意精简，只保留列表页渲染与筛选所需的信息 */
    public record ProductCard(
            Long id,
            String title,
            String subtitle,
            Long categoryId,
            String categoryName,
            Long brandId,
            String brandName,
            String supplyType,
            String mainImage,
            long minPrice,
            long maxPrice,
            int salesCount,
            double rating,
            int ratingCount,
            boolean inStock
    ) {
    }

    /** 适配度：把 SKU 上的适用属性聚合到商品维度，供「这只宠物能不能吃」的判定 */
    public record Suitability(
            boolean food,
            List<String> species,
            List<String> lifeStages,
            List<String> allergens
    ) {
    }

    public record ProductDetail(
            ProductCard card,
            String detail,
            List<String> images,
            List<SkuView> skus,
            Suitability suitability,
            List<ProductCard> sameCategory,
            List<ProductCard> alsoViewed
    ) {
    }

    // ---------------- C2C 闲置 ----------------

    public record ListingCard(
            Long id,
            String title,
            Long price,
            long originalPrice,
            String conditionLevel,
            String city,
            String cover,
            String species,
            String breed,
            int viewCount,
            Long sellerId
    ) {
    }

    public record ListingDetail(
            ListingCard card,
            String description,
            List<String> images,
            List<ProductCard> sameCategoryNew
    ) {
    }

    // ---------------- 首页聚合 ----------------

    public record SpeciesPicks(String species, String label, List<ProductCard> items) {
    }

    public record HomeView(
            List<CategoryNode> categories,
            List<ProductCard> recommended,
            List<ProductCard> freshArrivals,
            List<BrandView> brands,
            List<ListingCard> secondhand,
            List<SpeciesPicks> speciesPicks
    ) {
    }

    // ---------------- 查询条件 ----------------

    /**
     * 商品检索条件。
     * 生产形态下这份条件会被翻译成 Elasticsearch 查询；
     * 本阶段由 SQL 实现，对外契约完全一致，替换检索实现不影响调用方。
     */
    public record ProductQuery(
            String keyword,
            Long categoryId,
            Long brandId,
            String supplyType,
            Long minPrice,
            Long maxPrice,
            String species,
            String lifeStage,
            boolean inStockOnly,
            String sort,
            int page,
            int pageSize
    ) {
    }

    public record ListingQuery(
            String keyword,
            String city,
            String species,
            String conditionLevel,
            Long minPrice,
            Long maxPrice,
            String sort,
            int page,
            int pageSize
    ) {
    }
}
