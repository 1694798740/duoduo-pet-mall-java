package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.service.SellerProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 商品管理接口（{@code /api/seller/products/**}，M9 商品侧）。
 *
 * <p>与交易侧 {@code /api/seller/**} 同一套准入模型：卖家或运营 / 管理员；
 * 可见范围由登录态推导，接口不接受普通卖家指定的 sellerId。
 *
 * <p>网关与过滤器注意（踩过一次的坑）：
 * <ul>
 *   <li>网关必须把 {@code /api/seller/products/**} 路由到 duoduo-product，且<b>不加白名单</b> ——
 *       商品服务要靠 {@code X-User-*} 头识别「这是哪个卖家」；</li>
 *   <li>本服务的 {@code InternalTokenFilter} 只保护 {@code /api/products/internal/**} 与
 *       库存写路径，不保护本控制器 —— 内部凭据只能证明「你是自己人」，证明不了「这商品是你的」，
 *       归属判断必须走登录态。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/seller/products")
public class SellerProductController {

    private final SellerProductService sellerProductService;

    public SellerProductController(SellerProductService sellerProductService) {
        this.sellerProductService = sellerProductService;
    }

    /** 我的商品列表（分页 + 标题关键词）；运营 / 管理员可显式按 sellerId 筛选 */
    @GetMapping
    public ApiResponse<PageResult<SellerProductView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerProductService.list(keyword, sellerId, page, pageSize));
    }

    /** 上下架请求体：{@code {"action": "ON_SALE" | "OFF_SHELF"}} */
    public record StatusRequest(String action) {
    }

    @PostMapping("/{spuId}/status")
    public ApiResponse<SellerProductView> changeStatus(@PathVariable Long spuId,
                                                       @RequestBody StatusRequest request) {
        return ApiResponse.ok(sellerProductService.changeStatus(spuId, request == null ? null : request.action()));
    }
}
