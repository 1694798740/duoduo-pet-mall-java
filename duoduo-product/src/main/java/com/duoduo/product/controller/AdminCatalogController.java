package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.product.dto.ProductDtos.BrandSaveRequest;
import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.dto.ProductDtos.CategorySaveRequest;
import com.duoduo.product.service.CategoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营侧基础数据维护（类目 / 品牌），平台管理后台（M10）。
 *
 * <p>全部接口要求运营或管理员角色，由 {@link OperatorGuard#requireOperator()} 把关 ——
 * 网关只负责把角色透传成 {@code X-User-Role}，真正的准入边界在这里，前端按角色显隐入口不算安全边界。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final CategoryService categoryService;

    public AdminCatalogController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // ---------------- 类目 ----------------

    @GetMapping("/categories")
    public ApiResponse<List<CategoryNode>> categories() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.flatForAdmin());
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryNode> createCategory(@RequestBody CategorySaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryNode> updateCategory(@PathVariable Long id, @RequestBody CategorySaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.updateCategory(id, request));
    }

    @PutMapping("/categories/{id}/status")
    public ApiResponse<Void> setCategoryStatus(@PathVariable Long id, @RequestParam boolean active) {
        OperatorGuard.requireOperator();
        categoryService.setCategoryStatus(id, active);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        categoryService.deleteCategory(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 品牌 ----------------

    @GetMapping("/brands")
    public ApiResponse<List<BrandView>> brands() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.adminBrands());
    }

    @PostMapping("/brands")
    public ApiResponse<BrandView> createBrand(@RequestBody BrandSaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.createBrand(request));
    }

    @PutMapping("/brands/{id}")
    public ApiResponse<BrandView> updateBrand(@PathVariable Long id, @RequestBody BrandSaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.updateBrand(id, request));
    }

    @PutMapping("/brands/{id}/status")
    public ApiResponse<Void> setBrandStatus(@PathVariable Long id, @RequestParam boolean active) {
        OperatorGuard.requireOperator();
        categoryService.setBrandStatus(id, active);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/brands/{id}")
    public ApiResponse<Void> deleteBrand(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        categoryService.deleteBrand(id);
        return ApiResponse.ok(null);
    }
}
