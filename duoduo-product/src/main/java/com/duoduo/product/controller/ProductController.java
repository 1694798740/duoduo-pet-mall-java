package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.service.ProductInteractionService;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos;
import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductDtos.ProductDetail;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
import com.duoduo.product.dto.ProductDtos.SkuPurchaseView;
import com.duoduo.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商品接口（公开）。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」，所有规则（区间语义、排序白名单、
 * 分页上限、违禁词）都在服务层，便于单测直接覆盖服务层而不依赖 HTTP。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductInteractionService interactionService;

    public ProductController(ProductService productService,
                             ProductInteractionService interactionService) {
        this.productService = productService;
        this.interactionService = interactionService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProductCard>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String supplyType,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String lifeStage,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        ProductQuery query = new ProductQuery(keyword, categoryId, brandId, supplyType,
                minPrice, maxPrice, species, lifeStage, inStockOnly, sort, page, pageSize);
        return ApiResponse.ok(productService.search(query));
    }

    /**
     * A14 · P3 搜索纠错建议：`GET /api/products/search/suggestion?keyword=猫良` → `{"suggestion":"猫粮"}`。
     *
     * <p>路径刻意用**两段**（`/search/suggestion`）：`/{spuId}` 只匹配单段，这样不会与商品详情冲突。
     * 词表来自库内品牌/分类/在售标题，给不出靠谱建议时返回空对象（前端不显示提示）。
     */
    @GetMapping("/search/suggestion")
    public ApiResponse<Map<String, String>> suggestKeyword(@RequestParam("keyword") String keyword) {
        String suggestion = productService.suggestKeyword(keyword);
        return ApiResponse.ok(suggestion == null ? Map.of() : Map.of("suggestion", suggestion));
    }

    /**
     * 店铺主页在售商品列表（第八十二轮；2026-10-22 补 keyword 站内搜索）：
     * `GET /api/products/store/{sellerId}?page=&pageSize=&sort=&keyword=`。
     *
     * <p>路径用**两段**（`/store/{sellerId}`）：`/{spuId}` 只匹配单段，不会冲突（同 A14 建议接口的判断）。
     * 公开只读（网关 `/api/products/**` 已整体免登）。只认「在售 + 已过审」的公开可见口径；
     * 店铺名随 {@link ProductCard#storeName} 下发。sellerId = 0（自营）也走同一入口，
     * 前端用 {@code /store/0} 展示「多多自营」货架。keyword 只在<b>本店范围内</b>过滤标题（淘宝店铺内搜索）。
     */
    @GetMapping("/store/{sellerId:\\d+}")
    public ApiResponse<PageResult<ProductCard>> storeProducts(
            @PathVariable Long sellerId,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(productService.storeProducts(sellerId, sort, keyword, page, pageSize));
    }

    /**
     * 商品详情。
     *
     * <p><b>{@code spuId} 必须是纯数字</b>（R60 压测发现）：不限定形状时，任何一段非数字路径
     * （{@code /api/products/home}、{@code /api/products/abc}）都会被这里捕获，
     * 然后倒在为 {@code "home"} 转 Long 上 → 抛 {@code NumberFormatException} →
     * 兜底成 <b>500</b>。而它正确的答案是 404（没有这个路径），500 会把「拼错 URL」
     * 记成服务器故障，告警、重试、排障全都往错的方向走。
     */
    @GetMapping("/{spuId:\\d+}")
    public ApiResponse<ProductDetail> detail(@PathVariable Long spuId) {
        // 足迹旁路记录：已登录才记，失败不影响详情（recordView 内部吞异常）
        com.duoduo.common.context.LoginUser user = com.duoduo.common.context.UserContext.get();
        if (user != null) {
            interactionService.recordView(user.userId(), spuId);
        }
        return ApiResponse.ok(productService.detail(spuId));
    }

    /**
     * 批量取 SKU 采购视图（内部接口，供交易服务加购 / 下单调用）。
     *
     * <p>路径落在 {@code /internal/skus/batch}：两段路径不会与单段的 {@code /{spuId}} 冲突。
     *
     * <p>暴露面说明：视图里带卖家归属（sellerId），属于服务间口径字段 ——
     * 按本类此前留下的约定，加入商家 ID 这类字段时必须改为内部路由。
     * 现在 {@code /api/products/internal/**} 由 {@code InternalTokenFilter} 校验
     * {@code X-Internal-Token}（外部直接调用返回 403），交易侧的 Feign 客户端
     * 由 {@code InternalCallConfig} 统一带上该请求头。
     *
     * <p><b>A4 例外（已明确记录，非疏漏）</b>：{@link ProductCard} 新增了 {@code sellerId}，
     * 而 {@code /api/products} 是公开路由。理由是「买家要看卖家信用」——
     * 信用分是**卖家**的可信度，商品详情页要展示它就必须知道卖家是谁，
     * 而商品域的 {@code ListingCard}（闲置卡片，同属公开 C 端视图）本来就带 {@code sellerId}，
     * 硬性禁止只会让两处口径不一致。该字段的用途**仅限展示卖家公开信用**，
     * 不构成任何内部标识泄露：它就是一个面向买家的公开身份。
     * 反向的、真正属于服务间口径的**采购视图**（{@code SkuPurchaseView}）仍严格留在内部路由。
     */
    @GetMapping("/internal/skus/batch")
    public ApiResponse<List<SkuPurchaseView>> batchSkus(@RequestParam("ids") List<Long> ids) {
        return ApiResponse.ok(productService.purchaseViews(ids));
    }

    /**
     * AI 导购候选召回（内部接口，A9，供 duoduo-ai 经内部凭据调用）。
     *
     * <p>把一句用户消息变成**真实商品库**里的候选卡片 —— AI 服务只负责组织回复，
     * 推荐的每一个商品都出自这里，从根上堵死「模型编造不存在的商品」。
     *
     * <p><b>三个约束参数（第五十七轮）</b>：{@code budgetMin} / {@code budgetMax} 是用户
     * 预算下/上限（单位**分**），{@code categoryIds} 是逗号分隔的类目 id。
     * 它们在这里变成 SQL 条件而不是提示词里的一句话 —— 超预算的商品**根本进不了候选池**，
     * 于是「模型推了超预算的货」在结构上不可能发生。
     * 三个都可空：不传即无约束（行为与第五十七轮之前一致）。
     */
    @GetMapping("/internal/candidates")
    public ApiResponse<List<ProductCard>> candidates(@RequestParam("keyword") String keyword,
                                                     @RequestParam(defaultValue = "8") int limit,
                                                     @RequestParam(required = false) Long budgetMin,
                                                     @RequestParam(required = false) Long budgetMax,
                                                     @RequestParam(required = false) String categoryIds) {
        return ApiResponse.ok(productService.internalCandidates(
                keyword, limit, budgetMin, budgetMax, parseIds(categoryIds)));
    }

    /**
     * 类目简表（内部接口，第五十七轮 · 供 AI 域用）。
     *
     * <p>落在 {@code /api/products/internal/**} 下，由 {@code InternalTokenFilter} 校验凭据。
     * AI 域拿它做两件事：从用户话里认出品类、生成澄清问题的可点选项。
     */
    @GetMapping("/internal/categories")
    public ApiResponse<List<ProductDtos.CategoryBrief>> internalCategories() {
        return ApiResponse.ok(productService.internalCategories());
    }

    /**
     * 对账用：商品域里**持有冻结库存**或库存异常的 SKU id 清单（S3，内部接口）。
     *
     * <p><b>为什么必须有这个"反查"入口，而不是只让交易域传自己知道的 SKU</b>：
     * 交易域能给出的只有「我这边记着有冻结」的 SKU。只按这个集合去比，
     * <b>「交易域少记了」这个方向永远测不到</b> —— 商品域明明有 5 件冻结、
     * 交易域一条冻结记录都没有时，交易域根本不会把那个 SKU 送过来比。
     * 单向的对账看起来在跑，实际只覆盖了一半的失效方向。
     *
     * <p>返回 id 清单而不是明细：调用方（交易域）本来就要再调一次
     * {@code /internal/skus/batch} 拿原始库存两列，多传一份明细只是重复。
     *
     * <p>判据刻意写得宽（冻结不为 0、或库存为负都算），因为这是一个
     * **候选集**而不是结论集：宽一点只会让对账多看几个 SKU，窄一点就会漏测。
     */
    @GetMapping("/internal/skus/frozen-holders")
    public ApiResponse<List<Long>> frozenHolders() {
        return ApiResponse.ok(productService.frozenHolderSkuIds());
    }

    /** 逗号分隔的 id 串 → 列表；非法片段直接跳过（宁可少过滤，也不要因为脏参数整单 400） */
    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // 跳过非法片段
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 批量取商品快照（内部接口，第四十三轮 · 分享圈，供履约域发布分享时落快照）。
     *
     * <p>与 {@code /internal/skus/batch} 同一条两段路径约定：不与单段 {@code /{spuId}} 冲突，
     * 由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}（外部直接调用返回 403）。
     * 查不到的 id 静默缺席，由调用方判定「商品不存在」—— 与采购视图同一宽容口径。
     */
    @GetMapping("/internal/cards")
    public ApiResponse<List<ProductDtos.InternalCardView>> internalCards(@RequestParam("ids") List<Long> ids) {
        return ApiResponse.ok(productService.internalCards(ids));
    }
}
