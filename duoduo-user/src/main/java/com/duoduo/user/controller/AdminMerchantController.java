package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.MerchantDtos.ApplicationSummary;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.RejectRequest;
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

    public AdminMerchantController(MerchantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping
    public ApiResponse<PageResult<ApplicationSummary>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(onboardingService.adminPage(page, pageSize, status));
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
}
