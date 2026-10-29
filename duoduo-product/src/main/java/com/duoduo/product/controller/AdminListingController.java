package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.CsvExport;
import com.duoduo.product.dto.ProductDtos.ListingAuditRequest;
import com.duoduo.product.dto.ProductDtos.ListingAuditView;
import com.duoduo.product.dto.ProductDtos.ListingBatchAuditRequest;
import com.duoduo.product.service.ListingService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营侧闲置内容审核，平台管理后台（M10）。
 *
 * <p>公开的 {@code /api/listings} 只暴露「已审核通过」的内容；这里把待审 / 已驳回 / 全部
 * 一并给运营看，是审核工作台的数据源。审核结果落 {@code auditStatus / auditReason / auditAt}，
 * 驳回必须填原因。
 */
@RestController
@RequestMapping("/api/admin/listings")
public class AdminListingController {

    private final ListingService listingService;

    public AdminListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @GetMapping
    public ApiResponse<PageResult<ListingAuditView>> list(
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(listingService.listForAudit(auditStatus, keyword, page, pageSize));
    }

    /**
     * 闲置内容审核导出（矩阵第二批）。
     *
     * <p>与列表同筛选（auditStatus / keyword），单次上限 5 万行；
     * 路径是字面量 {@code /export}，不会被 {@code /{id}} 捕获。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String auditStatus,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        java.util.List<ListingAuditView> views = listingService.exportForAudit(auditStatus, keyword);
        CsvExport.write(response, "闲置审核-" + CsvExport.stamp(), HEADERS,
                views.stream().map(AdminListingController::row).toList());
    }

    @PostMapping("/{id}/audit")
    @Audited(action = AuditAction.LISTING_AUDIT)
    public ApiResponse<ListingAuditView> audit(@PathVariable Long id, @RequestBody ListingAuditRequest request) {
        AuditTrail.target(id, "LISTING");
        AuditTrail.change("待审核", request.action());
        return ApiResponse.ok(listingService.audit(id, request.action(), request.reason()));
    }

    /**
     * 批量审核闲置（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条走与单条相同的审核逻辑：驳回原因必填、已处理的条目按幂等成功。
     * 运营闲置审核角标是「待审数」，审完自然减少，故 counts 为空。
     */
    @PostMapping("/batch-audit")
    @Audited(action = AuditAction.LISTING_AUDIT)
    public ApiResponse<BatchDtos.Result> batchAudit(@RequestBody ListingBatchAuditRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw BizException.validation("ids 不能为空");
        }
        if (request.ids().size() > 100) {
            throw BizException.validation("单次最多批量审核 100 条闲置");
        }
        AuditTrail.note("批量审核 " + request.ids().size() + " 条：" + request.action());
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(request.ids().size());
        int success = 0;
        for (Long id : request.ids()) {
            if (id == null || !seen.add(id)) {
                items.add(new BatchDtos.Item(id == null ? "" : String.valueOf(id), false,
                        "VALIDATION", id == null ? "id 不能为空" : "重复的闲置 id"));
                continue;
            }
            try {
                listingService.audit(id, request.action(), request.reason());
                success++;
                items.add(new BatchDtos.Item(String.valueOf(id), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(request.ids().size(), success,
                request.ids().size() - success, items, java.util.List.of()));
    }

    // ---------------- 导出列定义 ----------------

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "闲置ID", "卖家ID", "标题", "价格(元)", "成色", "城市", "品种", "审核状态", "驳回原因", "创建时间");

    private static java.util.List<String> row(ListingAuditView view) {
        return java.util.List.of(
                String.valueOf(view.id()),
                String.valueOf(view.sellerId()),
                text(view.title()),
                view.price() == null ? "" : CsvExport.yuan(view.price()),
                text(view.conditionLevel()),
                text(view.city()),
                text(view.species()),
                text(view.auditStatus()),
                text(view.auditReason()),
                CsvExport.beijingTime(view.createdAt()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
