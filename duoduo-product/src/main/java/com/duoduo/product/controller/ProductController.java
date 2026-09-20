package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
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

    public ProductController(ProductService productService) {
        this.productService = productService;
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

    @GetMapping("/{spuId}")
    public ApiResponse<ProductDetail> detail(@PathVariable Long spuId) {
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
}
