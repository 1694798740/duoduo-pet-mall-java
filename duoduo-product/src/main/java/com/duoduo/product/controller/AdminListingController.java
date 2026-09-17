package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.ListingAuditRequest;
import com.duoduo.product.dto.ProductDtos.ListingAuditView;
import com.duoduo.product.service.ListingService;
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(listingService.listForAudit(auditStatus, page, pageSize));
    }

    @PostMapping("/{id}/audit")
    public ApiResponse<ListingAuditView> audit(@PathVariable Long id, @RequestBody ListingAuditRequest request) {
        return ApiResponse.ok(listingService.audit(id, request.action(), request.reason()));
    }
}
