package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.api.StatusCount;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.web.CsvExport;
import com.duoduo.fulfilment.dto.ShareDtos.ReportBatchHandleRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportHandleRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportView;
import com.duoduo.fulfilment.service.ShareReportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 全平台举报列表（可按状态 PENDING / ACCEPTED / REJECTED 过滤，可搜理由/内容 ID，分页） */
    @GetMapping
    public ApiResponse<PageResult<ReportView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.listForAdmin(status, keyword, page, pageSize));
    }

    /** 举报导出（2026-10-28 · 导入导出第一批）：按状态 / 关键词导出 CSV */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        java.util.List<ReportView> rows = reportService.exportForAdmin(status, keyword);
        CsvExport.write(response, "举报处理-" + CsvExport.stamp(), HEADERS,
                rows.stream().map(ShareAdminReportController::row).toList());
    }

    /** 运营导航角标：未读待处理举报数（2026-10-27 · 点击详情即消除） */
    @GetMapping("/unread-count")
    public ApiResponse<List<StatusCount>> unreadCount() {
        LoginUser operator = OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.adminUnreadCount(operator.userId()));
    }

    /** 点开举报详情 = 已读；返回最新未读数（前端用它覆盖本地乐观值） */
    @PostMapping("/{id}/read")
    public ApiResponse<List<StatusCount>> markRead(@PathVariable Long id) {
        LoginUser operator = OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.markRead(operator.userId(), id));
    }

    /** 处理举报：ACCEPT（成立 → 内容下架）/ REJECT（不成立），**处理说明必填** */
    @PostMapping("/{id}/handle")
    @Audited(action = AuditAction.SHARE_REPORT_HANDLE)
    public ApiResponse<ReportView> handle(@PathVariable Long id,
                                          @Valid @RequestBody ReportHandleRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        return ApiResponse.ok(reportService.handle(operator.userId(), id, request));
    }

    /**
     * 批量处理举报（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条走与单条相同的处理逻辑：ACCEPT 会下架每一条被举报的内容，
     * 已处理过的一条逐条失败、其余继续。成功条目写运营已读并返回最新未读分桶。
     */
    @PostMapping("/batch-handle")
    @Audited(action = AuditAction.SHARE_REPORT_HANDLE)
    public ApiResponse<BatchDtos.Result> batchHandle(@Valid @RequestBody ReportBatchHandleRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        AuditTrail.note("批量处理举报 " + request.ids().size() + " 条：" + request.action());
        return ApiResponse.ok(reportService.handleBatch(operator.userId(), request.ids(),
                new ReportHandleRequest(request.action(), request.note())));
    }

    // ---------------- CSV 导出列定义 ----------------

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "举报ID", "目标类型", "目标ID", "举报人ID", "理由", "状态", "内容摘要",
            "处理人ID", "处理时间", "处理说明", "举报时间");

    private static java.util.List<String> row(ReportView view) {
        return java.util.List.of(
                String.valueOf(view.id()),
                text(view.targetType()),
                view.targetId() == null ? "" : String.valueOf(view.targetId()),
                view.reporterId() == null ? "" : String.valueOf(view.reporterId()),
                text(view.reason()),
                text(view.status()),
                text(view.targetExcerpt()),
                view.handledBy() == null ? "" : String.valueOf(view.handledBy()),
                CsvExport.beijingTime(view.handledAt()),
                text(view.handleNote()),
                CsvExport.beijingTime(view.createdAt()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
