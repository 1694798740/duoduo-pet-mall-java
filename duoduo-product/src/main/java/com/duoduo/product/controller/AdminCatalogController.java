package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.web.CsvExport;
import com.duoduo.common.web.XlsxExport;
import com.duoduo.product.dto.ProductDtos.BrandSaveRequest;
import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.CatalogCsvView;
import com.duoduo.product.dto.ProductDtos.CatalogImportPlanView;
import com.duoduo.product.dto.ProductDtos.CatalogImportRequest;
import com.duoduo.product.dto.ProductDtos.CategoryAttrSaveRequest;
import com.duoduo.product.dto.ProductDtos.CategoryAttrView;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.dto.ProductDtos.CategorySaveRequest;
import com.duoduo.product.service.CatalogCsvService;
import com.duoduo.product.service.CategoryAttrService;
import com.duoduo.product.service.CategoryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 运营侧基础数据维护（类目 / 品牌 / 类目属性模板），平台管理后台（M10）。
 *
 * <p>全部接口要求运营或管理员角色，由 {@link OperatorGuard#requireOperator()} 把关 ——
 * 网关只负责把角色透传成 {@code X-User-Role}，真正的准入边界在这里，前端按角色显隐入口不算安全边界。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    /** 类目 xlsx 模板的说明页：CSV 模板没有说明页，规则写在前端弹窗里，xlsx 里补上 */
    private static final List<String> CATEGORY_NOTES = List.of(
            "1. 名称必填、最长 50 字；同一父类目下重名会失败。",
            "2. 父类目填已有类目的名称；留空表示一级类目（最多两级）。",
            "3. 排序号填非负整数，越小越靠前；留空默认 0。",
            "4. 图标填图片地址（/img/... 或 https://...），可留空。",
            "5. 单次最多 500 行，文件最大 2MB。");

    /** 品牌 xlsx 模板的说明页 */
    private static final List<String> BRAND_NOTES = List.of(
            "1. 名称必填、最长 50 字；同名品牌会走更新而不是新建。",
            "2. 国家/地区最长 30 字，可留空。",
            "3. Logo 地址最长 300 字，填 /img/... 或 https://...，可留空。",
            "4. 品牌故事最长 1000 字，可留空。",
            "5. 单次最多 500 行，文件最大 2MB。");

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private final CategoryService categoryService;
    private final CategoryAttrService categoryAttrService;
    private final CatalogCsvService catalogCsvService;

    public AdminCatalogController(CategoryService categoryService, CategoryAttrService categoryAttrService,
                                  CatalogCsvService catalogCsvService) {
        this.categoryService = categoryService;
        this.categoryAttrService = categoryAttrService;
        this.catalogCsvService = catalogCsvService;
    }

    // ---------------- 类目 ----------------

    @GetMapping("/categories")
    public ApiResponse<List<CategoryNode>> categories() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.flatForAdmin());
    }

    /** 类目导出列（2026-10-28 · 矩阵 P3）：主数据盘点，含父类目名称与启用状态 */
    private static final List<String> CATEGORY_EXPORT_HEADERS = List.of(
            "类目ID", "类目名称", "父类目ID", "父类目名称", "层级", "状态", "图标");

    /**
     * 导出类目 CSV：与后台类目列表同源（含停用），不写审计。
     *
     * <p>父类目名称由同一份扁平列表按 id 反查；查不到时留空，不把 id 伪装成名称。
     */
    @GetMapping("/categories/export")
    public void exportCategories(HttpServletResponse response) throws IOException {
        OperatorGuard.requireOperator();
        List<CategoryNode> all = categoryService.flatForAdmin();
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (CategoryNode node : all) {
            names.put(node.id(), node.name());
        }
        List<List<String>> rows = all.stream().map(node -> List.of(
                String.valueOf(node.id()),
                text(node.name()),
                node.parentId() == null || node.parentId() == 0 ? "" : String.valueOf(node.parentId()),
                node.parentId() == null || node.parentId() == 0 ? "" : text(names.get(node.parentId())),
                String.valueOf(node.depth()),
                node.status() == null ? "" : ("ACTIVE".equals(node.status()) ? "启用" : "停用"),
                text(node.icon()))).toList();
        CsvExport.write(response, "类目-" + CsvExport.stamp(), CATEGORY_EXPORT_HEADERS, rows);
    }

    @PostMapping("/categories")
    @Audited(action = AuditAction.CATEGORY_CREATE)
    public ApiResponse<CategoryNode> createCategory(@RequestBody CategorySaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    @Audited(action = AuditAction.CATEGORY_UPDATE)
    public ApiResponse<CategoryNode> updateCategory(@PathVariable Long id, @RequestBody CategorySaveRequest request) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY");
        return ApiResponse.ok(categoryService.updateCategory(id, request));
    }

    @PutMapping("/categories/{id}/status")
    @Audited(action = AuditAction.CATEGORY_STATUS)
    public ApiResponse<Void> setCategoryStatus(@PathVariable Long id, @RequestParam boolean active) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY");
        AuditTrail.note("类目启用状态 -> " + active);
        categoryService.setCategoryStatus(id, active);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/categories/{id}")
    @Audited(action = AuditAction.CATEGORY_DELETE)
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY");
        categoryService.deleteCategory(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 第三批 · 类目导入（模板 → dry-run → 正式导入） ----------------

    /** 类目导入模板：四列 `名称,父类目,排序号,图标`（只有表头） */
    @GetMapping("/categories/import-template")
    public ApiResponse<CatalogCsvView> categoryImportTemplate() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(catalogCsvService.categoryTemplate());
    }

    /** 类目 xlsx 模板（2026-10-28）：只有表头 + 说明页；导入仍走同一接口 */
    @GetMapping(value = "/categories/import-template", params = "format=xlsx")
    public void categoryImportTemplateXlsx(HttpServletResponse response) throws IOException {
        OperatorGuard.requireOperator();
        XlsxExport.write(response, "category-import-template", "类目导入",
                CatalogCsvService.CATEGORY_COLUMNS, List.of(), CATEGORY_NOTES);
    }

    /**
     * 类目导入预演：逐行给出 CREATE / UPDATE / SKIP / ERROR，<b>不写库</b>。
     *
     * <p>用 POST 而不是 GET：文件内容是请求体，放进 query 会被网关截断。
     */
    @PostMapping("/categories/import/dry-run")
    public ApiResponse<CatalogImportPlanView> checkCategoryImport(
            @RequestBody(required = false) CatalogImportRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(catalogCsvService.checkCategoryImport(request));
    }

    /** 正式导入类目：逐行独立处理，部分成功不回滚；逐行结果见 body */
    @PostMapping("/categories/import")
    @Audited(action = AuditAction.CATEGORY_CREATE)
    public ApiResponse<BatchDtos.Result> importCategories(
            @RequestBody(required = false) CatalogImportRequest request) {
        OperatorGuard.requireOperator();
        BatchDtos.Result result = catalogCsvService.importCategories(request);
        AuditTrail.note("CSV 批量导入类目：共 " + result.total() + " 行，成功 " + result.successCount()
                + " 行，失败 " + result.failedCount() + " 行");
        return ApiResponse.ok(result);
    }

    // ---------------- 类目属性模板（A-10） ----------------

    @GetMapping("/categories/{categoryId}/attrs")
    public ApiResponse<List<CategoryAttrView>> categoryAttrs(@PathVariable Long categoryId) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryAttrService.adminList(categoryId));
    }

    @PostMapping("/categories/{categoryId}/attrs")
    @Audited(action = AuditAction.CATEGORY_ATTR_CREATE)
    public ApiResponse<CategoryAttrView> createCategoryAttr(@PathVariable Long categoryId,
                                                            @RequestBody CategoryAttrSaveRequest request) {
        OperatorGuard.requireOperator();
        CategoryAttrView view = categoryAttrService.create(categoryId, request);
        AuditTrail.target(view.id(), "CATEGORY_ATTR");
        AuditTrail.note("类目 " + categoryId + " 新增属性「" + view.name() + "」");
        return ApiResponse.ok(view);
    }

    @PutMapping("/category-attrs/{id}")
    @Audited(action = AuditAction.CATEGORY_ATTR_UPDATE)
    public ApiResponse<CategoryAttrView> updateCategoryAttr(@PathVariable Long id,
                                                            @RequestBody CategoryAttrSaveRequest request) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY_ATTR");
        return ApiResponse.ok(categoryAttrService.update(id, request));
    }

    @PutMapping("/category-attrs/{id}/status")
    @Audited(action = AuditAction.CATEGORY_ATTR_STATUS)
    public ApiResponse<Void> setCategoryAttrStatus(@PathVariable Long id, @RequestParam boolean active) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY_ATTR");
        AuditTrail.note("类目属性启用状态 -> " + active);
        categoryAttrService.setStatus(id, active);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/category-attrs/{id}")
    @Audited(action = AuditAction.CATEGORY_ATTR_DELETE)
    public ApiResponse<Void> deleteCategoryAttr(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "CATEGORY_ATTR");
        categoryAttrService.delete(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 品牌 ----------------

    @GetMapping("/brands")
    public ApiResponse<List<BrandView>> brands() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.adminBrands());
    }

    /** 品牌导出列（2026-10-28 · 矩阵 P3） */
    private static final List<String> BRAND_EXPORT_HEADERS = List.of(
            "品牌ID", "品牌名称", "国家/地区", "Logo地址", "品牌故事", "状态");

    /** 导出品牌 CSV：与后台品牌列表同源（含停用） */
    @GetMapping("/brands/export")
    public void exportBrands(HttpServletResponse response) throws IOException {
        OperatorGuard.requireOperator();
        List<List<String>> rows = categoryService.adminBrands().stream().map(brand -> List.of(
                String.valueOf(brand.id()),
                text(brand.name()),
                text(brand.country()),
                text(brand.logo()),
                text(brand.story()),
                brand.status() == null ? "" : ("ACTIVE".equals(brand.status()) ? "启用" : "停用"))).toList();
        CsvExport.write(response, "品牌-" + CsvExport.stamp(), BRAND_EXPORT_HEADERS, rows);
    }

    @PostMapping("/brands")
    @Audited(action = AuditAction.BRAND_CREATE)
    public ApiResponse<BrandView> createBrand(@RequestBody BrandSaveRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(categoryService.createBrand(request));
    }

    @PutMapping("/brands/{id}")
    @Audited(action = AuditAction.BRAND_UPDATE)
    public ApiResponse<BrandView> updateBrand(@PathVariable Long id, @RequestBody BrandSaveRequest request) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "BRAND");
        return ApiResponse.ok(categoryService.updateBrand(id, request));
    }

    @PutMapping("/brands/{id}/status")
    @Audited(action = AuditAction.BRAND_STATUS)
    public ApiResponse<Void> setBrandStatus(@PathVariable Long id, @RequestParam boolean active) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "BRAND");
        AuditTrail.note("品牌启用状态 -> " + active);
        categoryService.setBrandStatus(id, active);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/brands/{id}")
    @Audited(action = AuditAction.BRAND_DELETE)
    public ApiResponse<Void> deleteBrand(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "BRAND");
        categoryService.deleteBrand(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 第三批 · 品牌导入（模板 → dry-run → 正式导入） ----------------

    /** 品牌导入模板：四列 `名称,国家/地区,Logo地址,品牌故事`（只有表头） */
    @GetMapping("/brands/import-template")
    public ApiResponse<CatalogCsvView> brandImportTemplate() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(catalogCsvService.brandTemplate());
    }

    /** 品牌 xlsx 模板（2026-10-28）：只有表头 + 说明页 */
    @GetMapping(value = "/brands/import-template", params = "format=xlsx")
    public void brandImportTemplateXlsx(HttpServletResponse response) throws IOException {
        OperatorGuard.requireOperator();
        XlsxExport.write(response, "brand-import-template", "品牌导入",
                CatalogCsvService.BRAND_COLUMNS, List.of(), BRAND_NOTES);
    }

    /** 品牌导入预演：逐行给出 CREATE / UPDATE / SKIP / ERROR，不写库 */
    @PostMapping("/brands/import/dry-run")
    public ApiResponse<CatalogImportPlanView> checkBrandImport(
            @RequestBody(required = false) CatalogImportRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(catalogCsvService.checkBrandImport(request));
    }

    /** 正式导入品牌：逐行独立处理，部分成功不回滚 */
    @PostMapping("/brands/import")
    @Audited(action = AuditAction.BRAND_CREATE)
    public ApiResponse<BatchDtos.Result> importBrands(
            @RequestBody(required = false) CatalogImportRequest request) {
        OperatorGuard.requireOperator();
        BatchDtos.Result result = catalogCsvService.importBrands(request);
        AuditTrail.note("CSV 批量导入品牌：共 " + result.total() + " 行，成功 " + result.successCount()
                + " 行，失败 " + result.failedCount() + " 行");
        return ApiResponse.ok(result);
    }
}
