package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.common.security.perm.RequirePerm;
import com.duoduo.user.dto.AuditDtos.AuditLogView;
import com.duoduo.user.dto.AuditDtos.AuditQuery;
import com.duoduo.user.service.AuditLogService;
import com.duoduo.common.web.CsvExport;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台操作审计查询（E18）。
 *
 * <p><b>权限</b>：类级 {@code @RequirePerm(Permission.AUDIT_LOG_READ)} ——
 * 「运营可见」由服务端判定，前端不渲染入口只是体验优化，不是边界（E11/E14 同一纪律）。
 *
 * <p><b>★ 本类只有 GET，这是设计要求而不是巧合</b>：审计日志的完整性靠「没有任何
 * 修改路径」来保证，而不是靠「没人会去改」。{@code audit-log-smoke.ps1} 里有一条静态断言
 * 扫描整个仓库，确认不存在对审计 mapper 的 update/delete 调用、也不存在
 * {@code t_audit_log} 的 UPDATE/DELETE 语句 —— 包括将来有人「顺手加一个清理端点」这种情况。
 * 若确实需要清理（例如按类目归档），必须先改 {@code AuditCategory.immutable()} 的判据
 * 并同步那条断言，让「允许删什么」这件事变成一次显式的决定。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequirePerm(Permission.AUDIT_LOG_READ)
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    public AdminAuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 检索审计记录。
     *
     * <p>过滤条件全部可选；不带任何条件即「最近若干条」，按时间倒序。
     * 时间参数接受 {@code 2026-10-09}（整日，止端按「含」处理）或
     * {@code 2026-10-09T13:00:00}，格式不对直接 400 而不是静默忽略
     * （静默忽略会让人以为「这段时间真的没发生什么」）。
     */
    @GetMapping
    public ApiResponse<PageResult<AuditLogView>> list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(auditLogService.search(
                new AuditQuery(actorId, tenantId, category, action, outcome, targetId, from, to),
                page, pageSize));
    }

    /**
     * 审计日志导出（矩阵第二批）。
     *
     * <p>与检索同一筛选、同一权限（类级 {@code AUDIT_LOG_READ}），单次上限 5 万行；
     * 导出是只读动作，不写审计 —— 否则「看审计」本身会淹没被看的内容。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long actorId,
                       @RequestParam(required = false) Long tenantId,
                       @RequestParam(required = false) String category,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String outcome,
                       @RequestParam(required = false) Long targetId,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       HttpServletResponse response) throws java.io.IOException {
        java.util.List<AuditLogView> views = auditLogService.exportSearch(
                new AuditQuery(actorId, tenantId, category, action, outcome, targetId, from, to));
        CsvExport.write(response, "操作审计-" + CsvExport.stamp(), HEADERS,
                views.stream().map(AdminAuditLogController::row).toList());
    }

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "时间", "动作", "类目", "结果", "操作人ID", "操作人手机", "目标类型", "目标ID",
            "租户", "改前", "改后", "详情", "IP", "请求ID");

    private static java.util.List<String> row(AuditLogView view) {
        return java.util.List.of(
                CsvExport.beijingTime(view.createdAt()),
                view.action() == null ? "" : view.action(),
                view.category() == null ? "" : view.category(),
                view.outcome() == null ? "" : view.outcome(),
                view.actorId() == null ? "" : view.actorId(),
                view.actorPhone() == null ? "" : view.actorPhone(),
                view.targetType() == null ? "" : view.targetType(),
                view.targetId() == null ? "" : view.targetId(),
                view.tenantId() == null ? "" : view.tenantId(),
                view.beforeValue() == null ? "" : view.beforeValue(),
                view.afterValue() == null ? "" : view.afterValue(),
                view.detail() == null ? "" : view.detail(),
                view.ip() == null ? "" : view.ip(),
                view.requestId() == null ? "" : view.requestId());
    }
}
