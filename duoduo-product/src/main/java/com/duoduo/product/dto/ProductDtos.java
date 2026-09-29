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
            String status,
            List<CategoryNode> children
    ) {
    }

    public record BrandView(
            Long id,
            String name,
            String logo,
            String country,
            String story,
            String status
    ) {
    }

    /** 字典项（物种 / 生命阶段 / 成色），前端筛选器直接消费 */
    public record DictItem(String code, String label) {
    }

    // ---------------- 运营管理（M10 平台管理后台） ----------------

    /** 类目保存请求（创建 / 更新共用；id 为空表示新建） */
    public record CategorySaveRequest(
            String name,
            Long parentId,
            Integer sortNo,
            String icon
    ) {
    }

    /** 品牌保存请求（创建 / 更新共用） */
    public record BrandSaveRequest(
            String name,
            String logo,
            String country,
            String story
    ) {
    }

    /**
     * 发布闲置请求（C2C 卖家侧写链路）。
     *
     * <p>{@code sellerId} <b>刻意不在此处</b>：卖家身份只能来自服务端校验过的令牌，
     * 请求体里给一个「谁是卖家」的字段，等于把归属交给客户端自证。
     *
     * <p>{@code auditStatus} / {@code status} 同样不接受客户端传入 ——
     * 新发布一律落 {@code PENDING}（由服务端决定），否则发布者可以直接把
     * {@code auditStatus=APPROVED} 塞进来，绕过整条人工审核链。
     */
    public record ListingPublishRequest(
            String title,
            String description,
            Long categoryId,
            Long price,
            Long originalPrice,
            String conditionLevel,
            String city,
            List<String> images,
            String species,
            String breed
    ) {
    }

    /** 闲置上下架请求；action = ON_SALE / OFF_SHELF（不认识的动作必须 400，绝不静默当成下架） */
    public record ListingStatusRequest(
            String action
    ) {
    }

    /**
     * 卖家视角的「我的闲置」条目。
     *
     * <p>与 {@link ListingCard} 的关键差别：这里<b>必须带出 {@code status} / {@code auditStatus}
     * 与驳回原因</b>。公开列表只暴露审核通过的可见内容，卖家却需要看到自己那些
     * 「还没通过」「被驳回」的条目 —— 否则发布完就查无此项，卖家只能反复重发。
     * 驳回原因更是刚需：不告诉他为什么被拒，他就只能猜着改。
     */
    public record ListingMineView(
            Long id,
            String title,
            Long price,
            String conditionLevel,
            String city,
            String cover,
            String species,
            String status,
            String statusLabel,
            String auditStatus,
            String auditReason,
            int viewCount,
            int wantCount,
            String createdAt
    ) {
    }

    /** 闲置审核请求；action = APPROVE / REJECT，驳回时 reason 必填 */
    public record ListingAuditRequest(
            String action,
            String reason
    ) {
    }

    /** 闲置审核列表条目（运营视角，含未审核通过的内容） */
    public record ListingAuditView(
            Long id,
            Long sellerId,
            String title,
            Long price,
            String conditionLevel,
            String city,
            String species,
            String auditStatus,
            String auditReason,
            String createdAt
    ) {
    }

    // ---------------- 商品 ----------------

    /**
     * SKU 视图。
     *
     * <p>{@code stock} 的语义是「<b>可售余量</b>」= 真实库存 − 已被他人下单冻结的量，
     * 也就是「我现在还能买几件」——C 端只需要这个口径，把冻结量暴露出去反而会引起困惑。
     * 原始库存与冻结量的差异只在商品服务的内部接口里体现。
     *
     * <p>{@code sellable} 是服务端算好的「是否可下单」，避免前端把
     * 「库存为 0」「已下架」两套规则各写一遍。
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
            String skuStatus,
            /**
             * 卖家归属（t_user.id；0 = 平台自营）。
             * 交易服务下单时用它做「按卖家拆单」——之前全部写 0（自营），
             * 是因为商品域还没有归属字段；现在补上，第三方商家的订单才能正确落到商家端。
             */
            Long sellerId
    ) {
    }

    /** 商家端「我的商品」视图：SPU 主档 + SKU 汇总（数量与总库存），管理列表够用 */
    public record SellerProductView(
            Long id,
            String title,
            String mainImage,
            String status,
            String statusLabel,
            Long sellerId,
            long minPrice,
            long maxPrice,
            int salesCount,
            int skuCount,
            int totalStock
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
            boolean inStock,
            /**
             * 卖家归属（t_user.id；0 = 平台自营）。
             *
             * <p>商品详情页据此展示「卖家信用分」——买家看的是**卖家**的可信度，
             * 而不是商品的属性，因此必须随卡片带出来；缺了它前端只能一律按自营展示，
             * 第三方商家的信用就永远露不出来。
             */
            Long sellerId
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
            /** 浏览次数：页面每被打开或刷新一次 +1（同一人可累加） */
            int viewCount,
            /** 想要人数：按咨询行为去重统计（同一买家对同一闲置只计 1 次）—— 与 viewCount 是不同口径 */
            int wantCount,
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

    /**
     * 卖家视角详情（第五十三轮）：「我的闲置」编辑回填专用。
     *
     * <p>公开详情 {@link ListingDetail} 对未过审内容一律 404（防状态码探测），卖家的
     * 被驳回条目用它拿不到自己的内容 —— 编辑重提必须有一个 owner 视角的读端点。
     * 因此这里带出<b>全集字段</b>：描述、全部图片、类目（公开卡只有首图无描述），
     * 并保留 status / auditStatus / auditReason 供页面判定当前状态。
     *
     * <p>路径安全：挂在 {@code /api/listings/mine/{id}}（三段），天然落在网关免登白名单
     * {@code /api/listings/*} 之外（Ant {@code *} 不跨 {@code /}），归属校验在服务层。
     */
    public record ListingOwnerView(
            Long id,
            String title,
            String description,
            Long categoryId,
            Long price,
            Long originalPrice,
            String conditionLevel,
            String city,
            List<String> images,
            String species,
            String breed,
            String status,
            String statusLabel,
            String auditStatus,
            String auditReason,
            int viewCount,
            int wantCount,
            String createdAt
    ) {
    }

    /**
     * 服务间：闲置卡事实（第五十三轮，社交域「问一问」LISTING 消息用）。
     *
     * <p>与商城商品卡 {@code InternalCardView} 同一裁剪口径：只要展示与定人必需的
     * 五个字段。{@code sellerId} 用于服务端按闲置归属定收件人 —— 不信前端。
     * <b>内部接口</b>：落在 {@code /api/listings/internal/**}，由 InternalTokenFilter
     * 校验 {@code X-Internal-Token}（带出 sellerId 属内部口径，不能匿名枚举）。
     */
    /**
     * 「想要」上报结果（第五十四轮，服务间）。
     *
     * <p>{@code counted}：{@code true} = 这位买家是第一次咨询，计数已 +1；
     * {@code false} = 已在册（同一买家对同一闲置只记一次），本次未计数。
     *
     * <p>调用方<b>不需要</b>据此改变用户可见行为（消息照发）—— 它的价值在于
     * 给联调与自动化断言一个明确的可判定返回值，而不是让调用方去猜「到底计没计」。
     */
    public record WantResult(
            boolean counted
    ) {
    }

    public record InternalListingView(
            Long id,
            String title,
            String mainImage,
            Long price,
            Long sellerId
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

    /**
     * 内部快照视图（第四十三轮 · 分享圈）：履约域发布分享时落商品快照用。
     *
     * <p>只给分享展示必需的四个字段（id / 标题 / 主图 / 最低价）：
     * 跨域接口按调用方真正需要裁剪字段（与 {@code UserBrief} 同一口径），
     * 类目、品牌、卖家归属等一概不带 —— 透露面最小，契约也最稳。
     */
    /**
     * 内部商品卡（服务间）。
     *
     * <p>跨域接口按调用方真正需要裁剪字段（与 {@code UserBrief} 同一口径），
     * 类目、品牌等一概不带 —— 透露面最小，契约也最稳。
     *
     * <p>{@code sellerId}（第四十七轮）：社交域「问一问」要在服务端定位收件人 ——
     * 买家点咨询时，收件人由本域事实（SPU 归属卖家）决定，而不是信前端传的 userId，
     * 否则「咨询」会被当成向任意用户发消息的通道。履约域的旧契约副本少这一个字段，
     * Feign/Jackson 对未知字段默认忽略，互不影响。
     */
    public record InternalCardView(
            Long id,
            String title,
            String mainImage,
            Long minPrice,
            Long sellerId
    ) {
    }
}
