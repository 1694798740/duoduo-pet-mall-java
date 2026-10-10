package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.fulfilment.dto.ShareDtos.ReportHandleRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportView;
import com.duoduo.fulfilment.service.ShareReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台（运营）分享举报处理台（第五十六轮）。
 *
 * <p>与用户侧的举报入口分开：可见范围不同（这里是全平台）、权限不同（运营准入）、
 * 动作不同（下架 / 驳回）。准入由 {@code OperatorGuard.requireOperator()} 把关 ——
 * 与售后仲裁工作台同一套写法。
 *
 * <p><b>路由必须在网关里显式列出</b>：履约域的路由是按具体子路径列举的
 * （不是 {@code /api/admin/**} 兜底），漏了这一段会**静默 404** 而不是报错。
 */
@RestController
@RequestMapping("/api/admin/share-reports")
public class ShareAdminReportController {

    private final ShareReportService reportService;

    public ShareAdminReportController(ShareReportService reportService) {
        this.reportService = reportService;
    }

    /** 全平台举报列表（可按状态 PENDING / ACCEPTED / REJECTED 过滤，分页） */
    @GetMapping
    public ApiResponse<PageResult<ReportView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.listForAdmin(status, page, pageSize));
    }

    /** 处理举报：ACCEPT（成立 → 内容下架）/ REJECT（不成立），**处理说明必填** */
    @PostMapping("/{id}/handle")
    @Audited(action = AuditAction.SHARE_REPORT_HANDLE)
    public ApiResponse<ReportView> handle(@PathVariable Long id,
                                          @Valid @RequestBody ReportHandleRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.handle(operator.userId(), id, request));
    }
}
