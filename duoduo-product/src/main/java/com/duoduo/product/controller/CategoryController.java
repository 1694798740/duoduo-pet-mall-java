package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 类目接口（公开，网关白名单放行）。
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 两级类目树，用于首页金刚区与筛选面板 */
    @GetMapping
    public ApiResponse<List<CategoryNode>> tree() {
        return ApiResponse.ok(categoryService.tree());
    }

    /** 扁平列表，用于后台管理与下拉选择 */
    @GetMapping("/flat")
    public ApiResponse<List<CategoryNode>> flat() {
        return ApiResponse.ok(categoryService.flat());
    }

    @GetMapping("/{id}")
    public ApiResponse<CategoryNode> get(@PathVariable Long id) {
        return ApiResponse.ok(categoryService.get(id));
    }
}
