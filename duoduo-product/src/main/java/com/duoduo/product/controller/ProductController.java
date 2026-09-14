package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductDtos.ProductDetail;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
import com.duoduo.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
