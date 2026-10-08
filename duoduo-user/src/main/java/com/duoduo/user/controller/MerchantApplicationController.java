package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.security.ClientEntry;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.MerchantTokenResponse;
import com.duoduo.user.dto.MerchantDtos.SaveDraftRequest;
import com.duoduo.user.service.AuthService;
import com.duoduo.user.service.MerchantOnboardingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家侧入驻申请（E10）。
 *
 * <p>路径挂在 {@code /api/users/me/merchant-application}（非专属路径族）：
 * 入驻表单在商家站点（ent=merchant），但商城侧将来若加入驻入口也能调 ——
 * 端点本身不做入口区分，语义统一为「当前登录用户的入驻申请」。
 *
 * <p>与旧端点的关系：{@code POST /api/users/me/merchant}（实名即升 SELLER）在 E10
 * <b>废除无审核直接开店</b>的语义，改为「发起入驻」的同义入口（见 UserController 注释）；
 * 本控制器才是入驻流程的正式接口。
 */
@RestController
@RequestMapping("/api/users/me/merchant-application")
public class MerchantApplicationController {

    private final MerchantOnboardingService onboardingService;
    private final AuthService authService;

    public MerchantApplicationController(MerchantOnboardingService onboardingService,
                                         AuthService authService) {
        this.onboardingService = onboardingService;
        this.authService = authService;
    }

    /** 查看我的入驻申请（首次查看即创建草稿 —— 表单需要一个稳定落点） */
    @GetMapping
    public ApiResponse<ApplicationView> myApplication() {
        return ApiResponse.ok(onboardingService.ensureApplication(UserContext.requireUserId()));
    }

    /** 保存草稿（店名 + 材料引用）。仅 DRAFT / REJECTED 状态可改 */
    @PutMapping
    public ApiResponse<ApplicationView> saveDraft(@RequestBody SaveDraftRequest request) {
        return ApiResponse.ok(onboardingService.saveDraft(UserContext.requireUserId(), request));
    }

    /** 提交送审（幂等：重复提交返回现状；驳回后可重提） */
    @PostMapping("/submit")
    public ApiResponse<ApplicationView> submit() {
        return ApiResponse.ok(onboardingService.submit(UserContext.requireUserId()));
    }

    /**
     * 激活（审核通过后调用）：返回重签的访问令牌（入口固定商家侧，与入驻重签同一语义）。
     * 小程序 / Bearer 形态的端侧用响应体里的令牌覆盖本地；浏览器通道走 Cookie。
     */
    @PostMapping("/activate")
    public ApiResponse<MerchantTokenResponse> activate() {
        Long userId = UserContext.requireUserId();
        onboardingService.activateIfApproved(userId);
        return ApiResponse.ok(new MerchantTokenResponse(
                authService.renewAccessToken(userId, ClientEntry.MERCHANT),
                authService.accessTtlSeconds()));
    }
}
