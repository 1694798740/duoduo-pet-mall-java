package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.MerchantDtos.ApplicationSummary;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.RejectRequest;
import com.duoduo.user.service.MerchantLicenseJob;
import com.duoduo.user.service.MerchantOnboardingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入驻审核台后端（E10 提供接口，E11 提供页面与 e2e）。
 *
 * <p>路径 {@code /api/admin/merchants/**} 属后台专属路径族（E8：仅 admin 入口令牌可调），
 * 服务端再用 {@link OperatorGuard} 把角色这道防线补齐 —— 两层各司其职。
 */
@RestController
@RequestMapping("/api/admin/merchants")
public class AdminMerchantController {

    private final MerchantOnboardingService onboardingService;
    private final MerchantLicenseJob licenseJob;
    private final com.duoduo.user.service.MerchantAuditSampleService sampleService;
    private final com.duoduo.user.service.MerchantAuditSampleJob sampleJob;

    public AdminMerchantController(MerchantOnboardingService onboardingService,
                                   MerchantLicenseJob licenseJob,
                                   com.duoduo.user.service.MerchantAuditSampleService sampleService,
                                   com.duoduo.user.service.MerchantAuditSampleJob sampleJob) {
        this.onboardingService = onboardingService;
        this.licenseJob = licenseJob;
        this.sampleService = sampleService;
        this.sampleJob = sampleJob;
    }

    @GetMapping
    public ApiResponse<PageResult<ApplicationSummary>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.adminPage(page, pageSize, status));
    }

    /**
     * 手动触发一轮资质巡检（E15）：临期提醒 + 过期自动停业，返回本轮处理计数。
     *
     * <p>刻意不做「只读的预览」而是真的执行 —— 运营需要它来回答两个实际问题：
     * ① 刚给某家店补录了有效期，想知道现在的结论是什么；
     * ② 库里数据被人工改过（如修错日期），要立刻按新数据重算一遍，
     * 而不能等到下一轮 6 小时之后。处置动作全部是条件更新 + 只升档才提醒，可安全重复调用。
     */
    @PostMapping("/license-sweep")
    public ApiResponse<MerchantLicenseJob.SweepReport> licenseSweep() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(licenseJob.sweepNow());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> detail(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.adminDetail(Long.valueOf(id)));
    }

    /** 开始审核：SUBMITTED → REVIEWING（幂等） */
    @PostMapping("/{id}/start-review")
    public ApiResponse<ApplicationView> startReview(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.startReview(
                Long.valueOf(id), UserContext.requireUserId()));
    }

    /** 通过并开店（幂等：重复通过返回现状） */
    @PostMapping("/{id}/approve")
    public ApiResponse<ApplicationView> approve(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.approve(
                Long.valueOf(id), UserContext.requireUserId()));
    }

    /** 驳回（结构化原因必填；结论一致时幂等） */
    @PostMapping("/{id}/reject")
    public ApiResponse<ApplicationView> reject(@PathVariable("id") String id,
                                               @RequestBody RejectRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.reject(
                Long.valueOf(id), UserContext.requireUserId(), request.reasons()));
    }

    // ---------------- E28 · 入驻事后抽查 ----------------
    // 路径刻意用两段（/audit-sla/...）而不是挂在 /{id} 下面：
    // /{id} 是「按 id 取详情」，与一个固定的子资源名放在同一层，
    // 匹配优先级要靠框架的"字面量优先"规则来解释 —— 将来有人改成自定义匹配就会踩坑。

    /** 入驻抽查列表：PENDING（默认）/ PASSED / REJECTED / ALL */
    @GetMapping("/audit-sla/samples")
    public ApiResponse<PageResult<com.duoduo.user.dto.MerchantAuditSlaDtos.AuditSampleView>> auditSamples(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleService.pageSamples(status, page, pageSize));
    }

    /** 复核一条入驻抽查任务：PASS / REJECT（判违规必须填原因；判违规 = 直接停业 + 联动下架） */
    @PostMapping("/audit-sla/samples/{id}/review")
    public ApiResponse<com.duoduo.user.dto.MerchantAuditSlaDtos.AuditSampleView> reviewAuditSample(
            @PathVariable("id") String id,
            @RequestBody com.duoduo.user.dto.MerchantAuditSlaDtos.ReviewRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleService.review(Long.valueOf(id),
                request == null ? null : request.action(),
                request == null ? null : request.note()));
    }

    /** SLA 自证指标（读它会顺带评估并执行自动收紧） */
    @GetMapping("/audit-sla/metrics")
    public ApiResponse<com.duoduo.user.dto.MerchantAuditSlaDtos.AuditMetrics> auditMetrics() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleService.metrics());
    }

    /** 立即跑一轮入驻抽查（抽昨天那批自动放行的店铺） */
    @PostMapping("/audit-sla/sweep")
    public ApiResponse<com.duoduo.user.dto.MerchantAuditSlaDtos.SampleReport> auditSampleSweep() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleJob.sweepNow());
    }
}
