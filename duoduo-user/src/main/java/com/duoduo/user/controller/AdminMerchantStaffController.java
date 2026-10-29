package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.web.CsvExport;
import com.duoduo.user.dto.MerchantDtos.StaffAdminView;
import com.duoduo.user.service.MerchantStaffService;
import jakarta.servlet.http.HttpServletResponse;
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
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminPage(page, pageSize, keyword));
    }

    /** 平台成员导出（矩阵第二批）：与列表同一关键字筛选，单次上限 5 万行 */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        java.util.List<StaffAdminView> views = staffService.exportAdmin(keyword);
        CsvExport.write(response, "平台成员-" + CsvExport.stamp(), HEADERS,
                views.stream().map(AdminMerchantStaffController::row).toList());
    }

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "成员ID", "店铺ID", "店铺", "用户ID", "手机号", "角色", "状态", "加入时间");

    private static java.util.List<String> row(StaffAdminView view) {
        return java.util.List.of(
                text(view.memberId()),
                text(view.merchantId()),
                text(view.storeName()),
                text(view.userId()),
                text(view.phoneMasked()),
                text(view.role()),
                text(view.status()),
                CsvExport.beijingTime(view.createdAt()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @PostMapping("/{memberId}/disable")
    @Audited(action = AuditAction.ADMIN_DISABLE_STAFF)
    public ApiResponse<StaffAdminView> disable(@PathVariable("memberId") long memberId) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminSetStatus(UserContext.requireUserId(), memberId, false));
    }

    @PostMapping("/{memberId}/enable")
    @Audited(action = AuditAction.ADMIN_ENABLE_STAFF)
    public ApiResponse<StaffAdminView> enable(@PathVariable("memberId") long memberId) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(staffService.adminSetStatus(UserContext.requireUserId(), memberId, true));
    }
}
