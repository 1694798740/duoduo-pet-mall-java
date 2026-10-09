package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.MerchantDtos.StaffAdminView;
import com.duoduo.user.service.MerchantStaffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧商家成员总览（E13）：跨租户列出全部店员 / 店主，支持平台直接停用（风控处置）。
 *
 * <p>路径在 {@code /api/admin/**}（E8 admin 专属族）+ 服务端 {@code OperatorGuard} 两层把关。
 * 与商家侧停用的差别：平台停用写独立的审计动作（ADMIN_DISABLE_STAFF），复盘时能区分
 * 「店主自己停的」与「平台处置的」。
 */
@RestController
@RequestMapping("/api/admin/merchant-staff")
public class AdminMerchantStaffController {

    private final MerchantStaffService staffService;

    public AdminMerchantStaffController(MerchantStaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ApiResponse<PageResult<StaffAdminView>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminPage(page, pageSize));
    }

    @PostMapping("/{memberId}/disable")
    public ApiResponse<StaffAdminView> disable(@PathVariable("memberId") long memberId) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminSetStatus(UserContext.requireUserId(), memberId, false));
    }

    @PostMapping("/{memberId}/enable")
    public ApiResponse<StaffAdminView> enable(@PathVariable("memberId") long memberId) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminSetStatus(UserContext.requireUserId(), memberId, true));
    }
}
