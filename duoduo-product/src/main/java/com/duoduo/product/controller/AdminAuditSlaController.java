package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.common.security.perm.RequirePerm;
import com.duoduo.product.dto.AuditSlaDtos.AuditMetrics;
import com.duoduo.product.dto.AuditSlaDtos.AuditSampleView;
import com.duoduo.product.dto.AuditSlaDtos.ReviewRequest;
import com.duoduo.product.dto.AuditSlaDtos.SampleReport;
import com.duoduo.product.service.AuditSampleJob;
import com.duoduo.product.service.AuditSampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核 SLA 运营接口（E28）：抽查队列 / 复核 / 自证指标 / 手动触发。
 *
 * <p>权限：类级 {@link RequirePerm}（权限点 {@code audit:sla}）——「运营可见」这件事
 * 由**服务端**判定，不能靠前端不渲染入口；后台的入口级判断只是体验优化，不是边界
 * （与 E11/E14 审核台同一纪律）。
 *
 * <p><b>E16 起用注解而非命令式</b>：本类原先 4 个方法各自写一行
 * {@code OperatorGuard.requireOperator()}。改成类级注解后，权限在签名上一眼可见，
 * 新增方法也不会「忘了写那一行 → 接口静默变成公开」。**改法的对价是必须实测
 * 「无权限者访问 → 403」**（注解失效的方向是静默放开，见 RequirePerm 注释），
 * 已加进 {@code scripts/audit-sla-smoke.ps1} 的越权段。
 *
 * <p>与 {@code /api/admin/products}（商品审核台）分开挂一个路径族：
 * 那个是「人工待审队列」，这个是「已经自动放行、现在回头抽查」，
 * 两者的待办语义完全不同。合成一个页面会让运营分不清「这条在等我审」
 * 和「这条已经在卖了、我只是复核一下」—— 后者更紧急，且处理错了两边后果不同。
 */
@RestController
@RequestMapping("/api/admin/audit-sla")
@RequirePerm(Permission.AUDIT_SLA)
public class AdminAuditSlaController {

    private final AuditSampleService sampleService;
    private final AuditSampleJob sampleJob;

    public AdminAuditSlaController(AuditSampleService sampleService, AuditSampleJob sampleJob) {
        this.sampleService = sampleService;
        this.sampleJob = sampleJob;
    }

    /**
     * 抽查列表。
     *
     * @param status PENDING（默认）/ PASSED / REJECTED / ALL
     */
    @GetMapping("/samples")
    public ApiResponse<PageResult<AuditSampleView>> samples(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sampleService.pageSamples(status, page, pageSize));
    }

    /** 复核一条抽查任务：PASS / REJECT（判违规必须填原因） */
    @PostMapping("/samples/{id}/review")
    @Audited(action = AuditAction.AUDIT_SLA_REVIEW)
    public ApiResponse<AuditSampleView> review(@PathVariable("id") Long id,
                                               @RequestBody ReviewRequest request) {
        String action = request == null ? null : request.action();
        String note = request == null ? null : request.note();
        return ApiResponse.ok(sampleService.review(id, action, note));
    }

    /**
     * SLA 自证指标（抽中违规率 / 平均复核时长 / 误判率 + 当前抽样比例）。
     *
     * <p>读这个接口会**顺带评估并执行自动收紧**（连续 3 天违规率超标则比例翻倍）——
     * 不做成两个接口，是因为「看指标」的人就是该知道「比例变了没」的人。
     */
    @GetMapping("/metrics")
    public ApiResponse<AuditMetrics> metrics() {
        return ApiResponse.ok(sampleService.metrics());
    }

    /**
     * 立即跑一轮抽查（抽昨天那批自动放行商品）。
     *
     * <p>存在的理由与 E15 的「立即巡检资质」按钮一样：验收时能当场看效果，
     * 线上出问题时有个不依赖等定时的抓手。反复点也不会重复建任务（唯一键幂等）。
     */
    @PostMapping("/sweep")
    @Audited(action = AuditAction.AUDIT_SLA_SWEEP)
    public ApiResponse<SampleReport> sweep() {
        return ApiResponse.ok(sampleJob.sweepNow());
    }
}
