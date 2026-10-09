package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.service.SellerProductService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品域内部端点 · 店铺停售（E15）。
 *
 * <p><b>为什么放在商品域</b>：商品的交易状态是商品域的事实（{@code t_spu.status}），
 * 用户域只知道「这家店的资质过期了」。让用户域直连商品库就是跨库写 ——
 * 本项目的硬约束是禁止跨库直连，所以那件事必须由商品域自己完成。
 *
 * <p><b>为什么走内部前缀</b>：整个 {@code /api/products/internal/**} 由
 * {@link com.duoduo.product.support.InternalTokenFilter} 拦截，缺 {@code X-Internal-Token}
 * 一律 403。这个端点会让<b>一整家店的所有商品下架</b>，裸露就等于开放
 * 「让任意店铺停售」的开关，比单条商品的下架危险得多。
 *
 * <p><b>不经网关</b>：调用方（用户域）通过服务发现直连本服务，
 * 网关不会把 {@code /api/products/internal/**} 路由进来 —— 内部接口对外不可见。
 */
@RestController
@RequestMapping("/api/products/internal/seller")
public class InternalSellerShelfController {

    private final SellerProductService sellerProductService;

    public InternalSellerShelfController(SellerProductService sellerProductService) {
        this.sellerProductService = sellerProductService;
    }

    /**
     * 下架该店铺的全部在售商品（资质过期联动）。
     *
     * <p>返回的是<b>本次真正改动</b>的商品数：已经下架的不计入。调用方据此判断
     * 「是否需要人工核查」—— 下架数为 0 且店铺确实有商品，说明商品可能压根不在售，
     * 这是正常情况；而调用失败会回非 2xx，与「下架 0 件」区分得很清楚。
     */
    @PostMapping("/{sellerId}/off-shelf")
    public ApiResponse<Integer> offShelf(@PathVariable("sellerId") Long sellerId) {
        return ApiResponse.ok(sellerProductService.offShelfAllBySeller(sellerId));
    }
}
