package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.CartDtos.AddCartRequest;
import com.duoduo.trade.dto.CartDtos.CartItemView;
import com.duoduo.trade.dto.CartDtos.CartView;
import com.duoduo.trade.dto.CartDtos.SelectionRequest;
import com.duoduo.trade.dto.CartDtos.UpdateQuantityRequest;
import com.duoduo.trade.service.CartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 购物车接口（需登录）。
 *
 * <p>路径由网关按 {@code /api/cart/**} 路由到本服务；网关已完成鉴权，
 * 服务端再从 {@code X-User-*} 取身份，<b>请求体与路径里都没有 userId</b>。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」，所有规则（数量上下限、库存校验、
 * 失效判定）都在服务层，便于单测直接覆盖服务层。
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** 购物车列表（含实时价格、可售库存与失效原因） */
    @GetMapping
    public ApiResponse<CartView> list() {
        return ApiResponse.ok(cartService.list());
    }

    /** 件数角标；不查商品服务，可高频调用 */
    @GetMapping("/count")
    public ApiResponse<Map<String, Integer>> count() {
        return ApiResponse.ok(Map.of("count", cartService.count()));
    }

    /** 加入购物车；同 SKU 重复加购累加数量。quantity 省略时按 1 件处理 */
    @PostMapping("/items")
    public ApiResponse<CartItemView> add(@Valid @RequestBody AddCartRequest request) {
        return ApiResponse.ok(cartService.add(request));
    }

    /** 修改数量 */
    @PatchMapping("/items/{skuId}")
    public ApiResponse<CartItemView> updateQuantity(@PathVariable Long skuId,
                                                    @Valid @RequestBody UpdateQuantityRequest request) {
        return ApiResponse.ok(cartService.updateQuantity(skuId, request.quantity()));
    }

    /** 勾选 / 取消勾选 */
    @PatchMapping("/items/{skuId}/selection")
    public ApiResponse<CartItemView> updateSelection(@PathVariable Long skuId,
                                                      @Valid @RequestBody SelectionRequest request) {
        return ApiResponse.ok(cartService.updateSelected(skuId, request.selected()));
    }

    /** 删除一条 */
    @DeleteMapping("/items/{skuId}")
    public ApiResponse<Void> remove(@PathVariable Long skuId) {
        cartService.remove(skuId);
        return ApiResponse.ok();
    }

    /** 清空已勾选条目（下单成功后调用） */
    @DeleteMapping("/selected")
    public ApiResponse<Map<String, Integer>> removeSelected() {
        return ApiResponse.ok(Map.of("removed", cartService.removeSelected()));
    }
}
