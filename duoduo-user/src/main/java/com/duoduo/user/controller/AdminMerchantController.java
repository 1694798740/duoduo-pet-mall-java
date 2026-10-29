package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.CsvExport;
import com.duoduo.user.dto.MerchantDtos.ApplicationSummary;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.BatchIdsRequest;
import com.duoduo.user.dto.MerchantDtos.BatchRejectRequest;
import com.duoduo.user.dto.MerchantDtos.RejectReason;
import com.duoduo.user.dto.MerchantDtos.RejectRequest;
import com.duoduo.user.service.MerchantLicenseJob;
import com.duoduo.user.service.MerchantOnboardingService;
import jakarta.servlet.http.HttpServletResponse;
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
    private final com.duoduo.user.service.MerchantIpWhitelistService ipWhitelistService;

    public AdminMerchantController(MerchantOnboardingService onboardingService,
                                   MerchantLicenseJob licenseJob,
                                   com.duoduo.user.service.MerchantAuditSampleService sampleService,
                                   com.duoduo.user.service.MerchantAuditSampleJob sampleJob,
                                   com.duoduo.user.service.MerchantIpWhitelistService ipWhitelistService) {
        this.onboardingService = onboardingService;
        this.licenseJob = licenseJob;
        this.sampleService = sampleService;
        this.sampleJob = sampleJob;
        this.ipWhitelistService = ipWhitelistService;
    }

    @GetMapping
    public ApiResponse<PageResult<ApplicationSummary>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.adminPage(page, pageSize, status, keyword));
    }

    /**
     * 入驻审核台导出（2026-10-28 · 导入导出第一批）：按状态 / 关键词导出 CSV。
     *
     * <p>路径是字面量 {@code /export}，不会被 {@code /{id}} 捕获。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        java.util.List<ApplicationSummary> rows = onboardingService.exportSummaries(status, keyword);
        CsvExport.write(response, "入驻审核-" + CsvExport.stamp(), HEADERS,
                rows.stream().map(AdminMerchantController::row).toList());
    }

    /**
     * 批量通过并开店（2026-10-28 · 批量操作第一批）。
     *
     * <p>{@code SUBMITTED} 会先自动进入审核中再通过 —— 运营在列表里勾选「待审核」直接点通过，
     * 是这类工作台最自然的用法；要求先逐条点「开始审核」只是把系统流程当成用户的负担。
     * 已在审核中 / 已通过的逐条沿用服务层的幂等语义；其余状态（驳回、停业等）逐条失败并带回原因。
     */
    @PostMapping("/batch-approve")
    @Audited(action = AuditAction.MERCHANT_APPROVE)
    public ApiResponse<BatchDtos.Result> batchApprove(@RequestBody BatchIdsRequest request) {
        OperatorGuard.requireOperator();
        java.util.List<String> ids = requireBatchIds(request == null ? null : request.ids());
        Long operatorId = UserContext.requireUserId();
        AuditTrail.note("批量通过 " + ids.size() + " 家：" + String.join(",", ids));
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(ids.size());
        int success = 0;
        for (String raw : ids) {
            try {
                Long merchantId = parseId(raw);
                ApplicationView current = onboardingService.adminDetail(merchantId);
                if ("SUBMITTED".equals(current.status())) {
                    onboardingService.startReview(merchantId, operatorId);
                }
                onboardingService.approve(merchantId, operatorId);
                success++;
                items.add(new BatchDtos.Item(raw, true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(raw, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(raw, false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(ids.size(), success, ids.size() - success,
                items, java.util.List.of()));
    }

    /**
     * 批量驳回（2026-10-28）：结构化的驳回原因对全部选中商户共用。
     *
     * <p>原因缺失时整批 400 —— 驳回结论要回给商家看，没有原因的驳回等于让商家无从修改。
     */
    @PostMapping("/batch-reject")
    @Audited(action = AuditAction.MERCHANT_REJECT)
    public ApiResponse<BatchDtos.Result> batchReject(@RequestBody BatchRejectRequest request) {
        OperatorGuard.requireOperator();
        java.util.List<String> ids = requireBatchIds(request == null ? null : request.ids());
        java.util.List<RejectReason> reasons = request.reasons();
        if (reasons == null || reasons.isEmpty()) {
            throw BizException.validation("驳回必须给出结构化原因");
        }
        Long operatorId = UserContext.requireUserId();
        AuditTrail.note("批量驳回 " + ids.size() + " 家：" + String.join(",", ids));
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(ids.size());
        int success = 0;
        for (String raw : ids) {
            try {
                onboardingService.reject(parseId(raw), operatorId, reasons);
                success++;
                items.add(new BatchDtos.Item(raw, true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(raw, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(raw, false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(ids.size(), success, ids.size() - success,
                items, java.util.List.of()));
    }

    /** 批量请求的公共校验：非空、去重、上限 100 */
    private static java.util.List<String> requireBatchIds(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.validation("ids 不能为空");
        }
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                throw BizException.validation("ids 不能包含空值");
            }
            distinct.add(raw.trim());
        }
        if (distinct.size() > 100) {
            throw BizException.validation("单次最多批量操作 100 家商户");
        }
        return new java.util.ArrayList<>(distinct);
    }

    private static Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            throw BizException.validation("商户 id 非法：" + raw);
        }
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
    @Audited(action = AuditAction.MERCHANT_LICENSE_SWEEP)
    public ApiResponse<MerchantLicenseJob.SweepReport> licenseSweep() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(licenseJob.sweepNow());
    }

    /**
     * E-06 · 平台清除某店铺的 IP 白名单（救援通道）。
     *
     * <p>存在的唯一原因：店主把白名单配错，会把自己整间店锁在商家端之外，
     * 而修复入口就长在那个进不去的页面里。运营在这里清空后，网关副本同时失效，
     * 店主即可重新登录并配一份正确的。
     *
     * <p>必须留痕（{@link AuditAction#ADMIN_RESET_MERCHANT_IP_WHITELIST}）：
     * 这是「平台替商家摘掉一条安全边界」，与重置 2FA 属于同一类需要被追问的操作。
     */
    @PostMapping("/{merchantId}/ip-whitelist/clear")
    @Audited(action = AuditAction.ADMIN_RESET_MERCHANT_IP_WHITELIST)
    public ApiResponse<com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistView> clearIpWhitelist(
            @PathVariable("merchantId") long merchantId) {
        OperatorGuard.requireOperator();
        AuditTrail.target(merchantId, "MERCHANT");
        AuditTrail.note("平台清除商家 IP 白名单（店主被锁在门外时的救援）");
        return ApiResponse.ok(ipWhitelistService.clearByAdmin(merchantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> detail(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.adminDetail(Long.valueOf(id)));
    }

    /** 开始审核：SUBMITTED → REVIEWING（幂等） */
    @PostMapping("/{id}/start-review")
    @Audited(action = AuditAction.MERCHANT_AUDIT_START)
    public ApiResponse<ApplicationView> startReview(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.startReview(
                Long.valueOf(id), UserContext.requireUserId()));
    }

    /** 通过并开店（幂等：重复通过返回现状） */
    @PostMapping("/{id}/approve")
    @Audited(action = AuditAction.MERCHANT_APPROVE)
    public ApiResponse<ApplicationView> approve(@PathVariable("id") String id) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.approve(
                Long.valueOf(id), UserContext.requireUserId()));
    }

    /** 驳回（结构化原因必填；结论一致时幂等） */
    @PostMapping("/{id}/reject")
    @Audited(action = AuditAction.MERCHANT_REJECT)
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
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleService.pageSamples(status, keyword, page, pageSize));
    }

    /** 复核一条入驻抽查任务：PASS / REJECT（判违规必须填原因；判违规 = 直接停业 + 联动下架） */
    @PostMapping("/audit-sla/samples/{id}/review")
    @Audited(action = AuditAction.AUDIT_SLA_REVIEW)
    public ApiResponse<com.duoduo.user.dto.MerchantAuditSlaDtos.AuditSampleView> reviewAuditSample(
            @PathVariable("id") String id,
            @RequestBody com.duoduo.user.dto.MerchantAuditSlaDtos.ReviewRequest request) {
        OperatorGuard.requireOperator();
        // 目标 = 被复核的抽查任务（平台级动作里唯一有具体目标的一个）
        AuditTrail.target(Long.parseLong(id), "AUDIT_SAMPLE");
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
    @Audited(action = AuditAction.AUDIT_SLA_SWEEP)
    public ApiResponse<com.duoduo.user.dto.MerchantAuditSlaDtos.SampleReport> auditSampleSweep() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(sampleJob.sweepNow());
    }

    // ---------------- CSV 导出列定义 ----------------

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "商户ID", "店铺名", "状态", "店主用户ID", "店主手机号",
            "申请时间", "审核时间", "资质到期", "资质状态", "停业原因");

    private static java.util.List<String> row(ApplicationSummary item) {
        return java.util.List.of(
                text(item.merchantId()),
                text(item.storeName()),
                text(item.status()),
                text(item.ownerUserId()),
                text(item.ownerPhoneMasked()),
                CsvExport.beijingTime(item.appliedAt()),
                CsvExport.beijingTime(item.reviewedAt()),
                CsvExport.beijingTime(item.licenseExpireAt()),
                text(item.licenseStage()),
                text(item.suspendedReason()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
