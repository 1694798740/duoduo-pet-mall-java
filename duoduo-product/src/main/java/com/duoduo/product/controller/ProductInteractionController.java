package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.product.service.ProductInteractionService;
import com.duoduo.product.service.ProductInteractionService.PickerProduct;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 收藏夹与足迹（第七十九轮 C 批）：供「我的收藏夹 / 我的足迹」页面
 * 与客服「+ → 发送商品」的四个来源使用。
 */
@RestController
public class ProductInteractionController {

    private final ProductInteractionService service;

    public ProductInteractionController(ProductInteractionService service) {
        this.service = service;
    }

    /** 切换收藏（幂等）；返回 { favorited } */
    @PostMapping("/api/favorites/{spuId}")
    public ApiResponse<Map<String, Boolean>> favorite(@PathVariable Long spuId) {
        return ApiResponse.ok(Map.of("favorited", service.toggleFavorite(UserContext.requireUserId(), spuId)));
    }

    /** 取消收藏（与 POST 语义相同，提供 REST 语义清晰的入口） */
    @DeleteMapping("/api/favorites/{spuId}")
    public ApiResponse<Map<String, Boolean>> unfavorite(@PathVariable Long spuId) {
        Long userId = UserContext.requireUserId();
        // 已收藏才取消；不收藏时静默成功（幂等）
        if (service.favoritedSpuIds(userId, List.of(spuId)).contains(spuId)) {
            service.toggleFavorite(userId, spuId);
        }
        return ApiResponse.ok(Map.of("favorited", false));
    }

    /** 批量收藏状态：商品详情页回填按钮用 */
    @GetMapping("/api/favorites/status")
    public ApiResponse<List<Long>> status(@RequestParam String spuIds) {
        List<Long> ids = Arrays.stream(spuIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        return ApiResponse.ok(service.favoritedSpuIds(UserContext.requireUserId(), ids));
    }

    @GetMapping("/api/favorites/products")
    public ApiResponse<PageResult<PickerProduct>> favorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.listFavorites(UserContext.requireUserId(), page, pageSize));
    }

    @GetMapping("/api/history/products")
    public ApiResponse<PageResult<PickerProduct>> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.listViews(UserContext.requireUserId(), page, pageSize));
    }

    @DeleteMapping("/api/history")
    public ApiResponse<Long> clearHistory() {
        return ApiResponse.ok(service.clearViews(UserContext.requireUserId()));
    }
}
