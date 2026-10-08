package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.CreditUpdateRequest;
import com.duoduo.user.dto.UserDtos.CreditUpdateResult;
import com.duoduo.user.dto.UserDtos.RealNameRequest;
import com.duoduo.user.dto.UserDtos.UpdateProfileRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.service.AuthService;
import com.duoduo.user.service.PetProfileService;
import com.duoduo.user.support.AuthCookieWriter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号信息接口。
 *
 * <p>当前登录用户一律取自 {@link UserContext}（由网关校验令牌后透传），
 * **绝不接受客户端传入 userId**，从根上消除越权读取他人资料的可能。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final PetProfileService petProfileService;

    public UserController(AuthService authService, PetProfileService petProfileService) {
        this.authService = authService;
        this.petProfileService = petProfileService;
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.profile(UserContext.requireUserId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserView> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(UserContext.requireUserId(), request));
    }

    /** 实名认证：通过后解锁发布闲置与提现（ACC-04） */
    @PostMapping("/me/realname")
    public ApiResponse<UserView> submitRealName(@Valid @RequestBody RealNameRequest request) {
        return ApiResponse.ok(authService.submitRealName(UserContext.requireUserId(), request));
    }

    /**
     * 【E10 废除说明】本控制器原有的 {@code POST /me/merchant} 与 {@code POST /me/merchant/token}
     * （实名即直接升 SELLER）已删除 —— 那条路径让「平台审核商家」形同虚设：
     * 任何实名账号调一下接口就拿到卖家身份，审核台还没看到人，店已经开了。
     * 入驻的正式流程见 {@link MerchantApplicationController}（提交材料 → 平台审核 → 开店）；
     * 审核通过后的角色升级发生在 {@code MerchantOnboardingService.approve}，
     * 不在任何商家可达的端点上。E8 的"重签令牌固定商家入口"语义随入口迁移到
     * {@code MerchantApplicationController.activate}。
     */

    /** 入驻后的新访问令牌（小程序通道用） */
    public record MerchantTokenResponse(String accessToken, long expiresInSeconds) {
    }

    /**
     * 服务间只读：批量取用户公开资料（昵称 / 头像）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}，
     * 因此即便有人带着登录态经网关访问，也会被服务自身拒绝（403）。
     * 参数用「逗号分隔字符串」而不是 {@code List<Long>}：两端的集合参数编码规则
     * （重复 query vs 逗号分隔）在不同 Feign 版本下有过变化，固定成单值后行为完全确定。
     *
     * <p>返回裁剪过的 {@link com.duoduo.user.dto.UserDtos.UserBrief}：只有 id / 昵称 / 头像，
     * 不含手机号、实名信息与角色。
     */
    @GetMapping("/internal/briefs")
    public ApiResponse<java.util.List<com.duoduo.user.dto.UserDtos.UserBrief>> briefs(
            @org.springframework.web.bind.annotation.RequestParam("ids") String ids) {
        java.util.List<Long> userIds = java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return ApiResponse.ok(authService.userBriefs(userIds));
    }

    /**
     * 服务间读：批量取角色快照（第四十七轮）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}。
     * 调用方是社交域：发私信时把发送方角色落列，通知中心据此分流好友 / 商家消息。
     * 只回 id + role（低敏感），不整份 UserView 下发 —— 与 {@code /internal/briefs} 同一裁剪口径。
     */
    @GetMapping("/internal/roles")
    public ApiResponse<java.util.List<com.duoduo.user.dto.UserDtos.UserRoleBrief>> roles(
            @org.springframework.web.bind.annotation.RequestParam("ids") String ids) {
        java.util.List<Long> userIds = java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return ApiResponse.ok(authService.userRoles(userIds));
    }

    /**
     * 服务间读：AI 导购用的宠物档案（第五十八轮 · AI 加强项 S1）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token} ——
     * 与 {@code /internal/briefs} 同一道门。只回 {@link com.duoduo.user.dto.UserDtos.PetBrief}
     * （名称 / 物种 / 品种 / 生命阶段 / 过敏原），**不含病史等健康数据** ——
     * 裁剪理由见该 DTO 的注释。
     *
     * <p>单用户参数（{@code userId}）而不是批量：调用方只有 AI 域一个，
     * 且它一次只为一个对话取档案。批量端点在没有第二个调用方时只是多一份越权面。
     */
    @GetMapping("/internal/pet-profiles")
    public ApiResponse<java.util.List<com.duoduo.user.dto.UserDtos.PetBrief>> petProfiles(
            @org.springframework.web.bind.annotation.RequestParam("userId") Long userId) {
        return ApiResponse.ok(petProfileService.briefsForAssistant(userId));
    }

    /**
     * 服务间写：回写信用分（A4）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token} ——
     * 与 {@code /internal/briefs} 同一道门。调用方是履约域的信用分刷新任务，
     * 分数由它按权重表算出；用户域只负责存，不做算法。
     *
     * <p>为什么路径参数是 {@code userId} 而不是从登录态取：这是**服务间**调用，
     * 没有「当前登录用户」这个概念，被写的对象就是参数本身。
     * 用户侧能改的资料（昵称 / 头像）仍走 {@code /api/users/me}，身份一律取自登录态（INV-8）。
     */
    @PostMapping("/internal/{userId}/credit")
    public ApiResponse<CreditUpdateResult> updateCredit(@PathVariable Long userId,
                                                       @Valid @RequestBody CreditUpdateRequest request) {
        return ApiResponse.ok(authService.updateCreditScore(userId, request.score(), request.source()));
    }
}
