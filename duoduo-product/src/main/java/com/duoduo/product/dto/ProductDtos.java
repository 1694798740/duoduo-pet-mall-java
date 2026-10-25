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

    /**
     * 类目简表（第五十七轮 · AI 导购）。
     *
     * <p>只给 AI 域用：它需要「类目名 → 类目 id」来做两件事 —— ① 从用户话里认出品类、
     * ② 生成澄清问题的可点选项。刻意只带 id / name / depth：
     * 图标、状态、children 这些是给前端渲染用的，AI 不需要，按调用方真正需要的裁剪。
     */
    public record CategoryBrief(
            Long id,
            String name,
            int depth
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

    /**
     * 类目属性模板项（A-10）。
     *
     * <p>{@code inputType}：TEXT / NUMBER / SELECT；SELECT 时 {@code options} 非空。
     * 属性值不单独建表，仍保存在商品 {@code specParams} 的 KV 里，按 {@code name} 匹配。
     */
    public record CategoryAttrView(
            Long id,
            Long categoryId,
            String name,
            String inputType,
            boolean required,
            List<String> options,
            int sortNo,
            String status
    ) {
    }

    /** 类目属性保存请求（A-10）；启停走独立端点，避免普通编辑被误记成状态变更 */
    public record CategoryAttrSaveRequest(
            String name,
            String inputType,
            Boolean required,
            List<String> options,
            Integer sortNo
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
            /**
             * 总库存与冻结库存的**原始两列**（S3 对账专用）。
             *
             * <p>为什么不复用上面的 {@code availableStock}：那一个是
             * {@code max(0, stock - frozenStock)} —— 那个 {@code max} 恰好把
             * {@code frozenStock > stock} 这个异常**夹成了 0**。前台只会显示"已售罄"，
             * 看不出库存在算错；异常被一个看似合理的展示层兜底吞掉了。
             * 对账必须看原始值，这也正是「对账要独立于展示口径」的具体体现。
             */
            int stock,
            int frozenStock,
            boolean sellable,
            String spuStatus,
            String skuStatus,
            /**
             * 卖家归属（t_user.id；0 = 平台自营）。
             * 交易服务下单时用它做「按卖家拆单」——之前全部写 0（自营），
             * 是因为商品域还没有归属字段；现在补上，第三方商家的订单才能正确落到商家端。
             */
            Long sellerId,
            /**
             * 店铺名（第八十一轮）：第三方 = t_merchant.name（按 sellerId=店主 userId 跨域取名）；
             * 自营 = 固定「多多自营」。查不到店铺时为 null，调用方降级为通用标识。
             * 购物车按店铺分组、商品卡显示自有店名都靠它。
             */
            String storeName
    ) {
    }

    /**
     * 商家端「我的商品」视图：SPU 主档 + SKU 汇总（数量与总库存），管理列表够用。
     *
     * <p><b>审核字段是 E14 新增的刚需</b>（与 {@code ListingMineView} 同一考虑）：
     * 列表只暴露「在售且已过审」的商品，但卖家必须看得到自己那些
     * 「待审 / 被驳回」的条目 —— 否则提交完就查无此项，卖家只能反复重发。
     * 驳回原因更是刚需：不告诉他为什么被拒，他就只能猜着改。
     */
    public record SellerProductView(
            Long id,
            String title,
            String mainImage,
            String status,
            String statusLabel,
            /** PENDING / APPROVED / REJECTED（自营商品恒 APPROVED） */
            String auditStatus,
            /** 审核状态中文文案，由服务端下发（前端不维护映射表） */
            String auditLabel,
            /** 最近一次驳回原因；非驳回态为 null */
            String auditReason,
            Long sellerId,
            long minPrice,
            long maxPrice,
            int salesCount,
            /** 商品评分与评价数（2026-10-24）：来自评价域回写，商家端据此展示口碑 */
            double rating,
            int ratingCount,
            int skuCount,
            int totalStock,
            /** 软删时间（A-09）：非空 = 回收站条目；正常列表恒为 null */
            String deletedAt,
            /** 回收站条目将被物理清理的时间（deletedAt + 30 天）；正常商品为 null */
            String purgeAt
    ) {
    }

    // ---------------- 商品提交与审核（E14） ----------------

    /**
     * SKU 提交项（新建 / 展示共用）。
     *
     * <p>{@code specName} 会被写成 {@code {"规格": "<值>"}} —— 与既有
     * {@code t_sku.spec_json} 的展示口径一致（{@code ProductService.specText} 把它渲染成
     * 「规格:2kg」）。把「规格」做成一维而不是任意键值对，是因为商家的真实需求里
     * 99% 是「一份商品有几种规格」（2kg / 5kg、单包 / 三包装），
     * 任意键值对表单会让发布页复杂到没人愿意填。
     */
    /**
     * 商品级键值对（2026-10-24）：规格参数 / 包装规格共用。
     *
     * <p>为什么用 KV 而不是固定列：不同类目的参数差异极大（猫粮看粗蛋白/适用阶段，
     * 猫砂看结团性/粉尘，用品看尺寸/材质），固定列要么建几十个空列，要么永远不够。
     * 前后端都按 [{@code name, value}] 传，落库是 JSON 文本。
     */
    public record KvItem(String name, String value) {
    }

    public record SellerSkuInput(
            String specName,
            Long price,
            Integer stock,
            String image
    ) {
    }

    /**
     * 新建商品请求（E14）。
     *
     * <p>{@code sellerId} / {@code supplyType} / {@code status} / {@code auditStatus}
     * <b>刻意都不在这里</b>（与 {@code ListingPublishRequest} 同一纪律）：
     * 归属由登录态推导、供货类型由服务端判定（运营建的算平台自营、免审；
     * 商家建的算第三方、必审）、审核态一律由服务端决定。
     * 请求体里给出任何一个，都等于让客户端自证「我该不该被审核」。
     */
    public record SellerProductCreateRequest(
            String title,
            String subtitle,
            Long categoryId,
            Long brandId,
            String mainImage,
            List<String> images,
            /** 商品介绍（纯文本）；历史字段 detail 保留兼容，新代码用它 */
            String detail,
            String intro,
            /** 富文本详情 HTML（服务端白名单消毒） */
            String detailHtml,
            List<KvItem> specParams,
            List<KvItem> packaging,
            List<SellerSkuInput> skus
    ) {
        /** 兼容旧调用（2026-10-24 新增内容字段前的 8 参构造）：新字段缺省 = 不设置 */
        public SellerProductCreateRequest(String title, String subtitle, Long categoryId, Long brandId,
                                          String mainImage, List<String> images, String detail,
                                          List<SellerSkuInput> skus) {
            this(title, subtitle, categoryId, brandId, mainImage, images, detail,
                    null, null, null, null, skus);
        }
    }

    /**
     * 编辑请求（E14 扩展）：内容任意字段不传即不改（三态语义同旧版 title / mainImage）。
     *
     * <p>{@code version} 必填（A-07）：它是编辑回填时读到的乐观锁版本。
     * 提交时若与服务端当前版本不一致，说明期间有他人（另一位店员 / 运营审核）
     * 改过同一件商品，服务端返回 409，客户端应刷新后重试 ——
     * 少了它，「整行覆盖」会把并发落下的审核结论（如 REJECTED）悄悄冲回旧值。
     */
    public record SellerProductUpdateRequest(
            String title,
            String subtitle,
            String mainImage,
            List<String> images,
            String detail,
            String intro,
            String detailHtml,
            /** null = 不改；空数组 = 清空 */
            List<KvItem> specParams,
            List<KvItem> packaging,
            Long categoryId,
            Long brandId,
            Integer version
    ) {
    }

    /**
     * SKU 改价 / 改库存项（2026-10-24）。
     *
     * <p>为什么只允许这两个字段：规格结构与图片属于「内容」，改它们要重审；
     * 价格与库存是高频运营动作，行业通行做法是不重审、但要校验与留痕。
     * id 必须属于该 SPU，否则视为越权（不区分「不存在」与「不是你的」）。
     */
    public record SkuPriceStockInput(Long id, Long price, Integer stock) {
    }

    /** SKU 改价 / 改库存请求（批量；一次提交本次编辑过的行） */
    public record SellerSkuPriceStockRequest(List<SkuPriceStockInput> skus) {
    }

    /** SKU 视图（商家侧编辑回填 / 审核台核对用） */
    public record SellerSkuView(
            Long id,
            String specName,
            long price,
            int stock,
            String image,
            String status
    ) {
    }

    /**
     * 商家视角商品详情（E14）：编辑回填的唯一数据源。
     *
     * <p>为什么不复用公开的 {@code ProductDetail}：公开详情对未过审商品一律 404
     * （防状态码探测），而卖家必须能读回自己被驳回的商品才能修改重提 ——
     * 这是两个完全不同的可见性规则，共用一个 DTO 迟早会把其中一条弄错。
     */
    public record SellerProductDetailView(
            Long id,
            String title,
            String subtitle,
            Long categoryId,
            Long brandId,
            String mainImage,
            List<String> images,
            String detail,
            String intro,
            String detailHtml,
            List<KvItem> specParams,
            List<KvItem> packaging,
            String supplyType,
            String status,
            String statusLabel,
            String auditStatus,
            String auditLabel,
            String auditReason,
            String auditAt,
            Long sellerId,
            long minPrice,
            long maxPrice,
            List<SellerSkuView> skus,
            /** 乐观锁版本（A-07）：编辑提交时必须原样带回 */
            Integer version
    ) {
    }

    /**
     * 平台审核台条目（E14）。
     *
     * <p>比商家视角多带<b>全部背审材料</b>（详情正文、全部图片、类目名、品牌名、SKU 明细）——
     * 审核是「看着内容下判定」，让运营点进二级页才看得到图片，是最容易导致
     * 「看都不看直接通过」的产品设计。
     */
    public record AdminProductAuditView(
            Long id,
            Long sellerId,
            String title,
            String subtitle,
            String mainImage,
            List<String> images,
            String detail,
            String intro,
            String detailHtml,
            List<KvItem> specParams,
            List<KvItem> packaging,
            Long categoryId,
            String categoryName,
            Long brandId,
            String brandName,
            String supplyType,
            String status,
            String auditStatus,
            String auditLabel,
            String auditReason,
            String auditAt,
            long minPrice,
            long maxPrice,
            List<SellerSkuView> skus,
            String createdAt,
            /** 乐观锁版本（A-07）：审核提交时必须原样带回，防止审的是旧内容 */
            Integer version
    ) {
    }

    /** 商品审核请求；action = APPROVE / REJECT，驳回时 reason 必填 */
    public record AdminProductAuditRequest(
            String action,
            String reason,
            /** 审核详情打开时的版本（A-07）；缺失返回 400，过期返回 409 */
            Integer version
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
            Long sellerId,
            /**
             * 店铺名（第八十一轮）：第三方 = 商家自有店名（t_merchant.name）；
             * 自营 = 「多多自营」。首页 / 列表 / 购物车据此显示真实店铺，
             * 而不是千篇一律的「品牌入驻」。
             */
            String storeName
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
            /** 商品介绍（纯文本，向后兼容历史数据） */
            String detail,
            /** 富文本详情 HTML（已消毒；为空时端侧回落 detail） */
            String detailHtml,
            String intro,
            List<KvItem> specParams,
            List<KvItem> packaging,
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
            Long sellerId,
            /**
             * 供货类型（self 自营 / merchant 品牌入驻，第七十三轮）。
             *
             * <p>「问一问」的自营路由依据：自营商品（self）的 {@code sellerId} 一律为 0
             * （平台自营，参与交易拆单与商家端可见口径），咨询收件人由社交域改按本字段
             * 定到官方客服账号 —— 「卖家归属」与「咨询归属」自此分离，不再共用一个字段。
             */
            String supplyType
    ) {
    }

    /**
     * 评分回写补丁（第七十三轮）：履约域在评价落库后，把该 SPU 的真实聚合结果推过来，
     * 商品域只负责落列 —— 聚合口径（平均分、条数）以评价域为准，本域不重复实现。
     *
     * <p>为什么是「补丁列表」而不是逐个调用：一次批量下单评价可能覆盖多件商品，
     * 一把推完把跨服务调用从 N 次压成 1 次。
     */
    public record RatingPatch(
            Long spuId,
            Double avgRating,
            Integer ratingCount
    ) {
    }

    /**
     * 评分对账候选（2026-10-18）：本域认为「自己有评分」的 SPU 清单。
     *
     * <p>用途只有一个 —— 让评价域拿真实聚合与它做差集：
     * 候选里有、评价库里没有的 SPU，说明这一列是历史种子假数据（或回写曾失败），
     * 必须被复位成 0；否则首页卡片会显示「4.0 分 / 4 条评价」，而详情页一条评价都没有。
     */
    public record RatingCandidate(
            Long spuId,
            Integer ratingCount
    ) {
    }
}
