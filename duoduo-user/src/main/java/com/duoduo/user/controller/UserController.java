package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.user.dto.UserDtos.CreditUpdateRequest;
import com.duoduo.user.dto.UserDtos.CreditUpdateResult;
import com.duoduo.user.dto.UserDtos.DeleteAccountRequest;
import com.duoduo.user.dto.UserDtos.DeleteAccountView;
import com.duoduo.user.dto.UserDtos.DeleteBlockersView;
import com.duoduo.user.dto.UserDtos.RealNameRequest;
import com.duoduo.user.dto.UserDtos.UpdateProfileRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.service.AccountDeletionService;
import com.duoduo.user.service.AuthService;
import com.duoduo.user.service.PetProfileService;
import com.duoduo.user.support.AuthCookieWriter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final com.duoduo.user.service.MerchantStaffService staffService;
    private final com.duoduo.user.service.MerchantOnboardingService onboardingService;
    private final com.duoduo.user.service.MerchantViolationService violationService;
    /** C7 · 账号注销（软注销 + 匿名化，见 AccountDeletionService 类注释） */
    private final AccountDeletionService accountDeletionService;

    public UserController(AuthService authService, PetProfileService petProfileService,
                          com.duoduo.user.service.MerchantStaffService staffService,
                          com.duoduo.user.service.MerchantOnboardingService onboardingService,
                          com.duoduo.user.service.MerchantViolationService violationService,
                          AccountDeletionService accountDeletionService) {
        this.staffService = staffService;
        this.onboardingService = onboardingService;
        this.violationService = violationService;
        this.authService = authService;
        this.petProfileService = petProfileService;
        this.accountDeletionService = accountDeletionService;
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

    // ---------------- C7 · 账号注销 ----------------

    /**
     * 注销预检：有哪些事没做完会挡住注销。
     *
     * <p>与「提交注销」分开两个端点，是为了让端侧能在用户点确认<b>之前</b>
     * 就把阻塞清单摆出来（弹窗里列出待处理的订单/售后/店铺/闲置），
     * 而不是等用户输了密码才被告知不行。
     */
    @GetMapping("/me/delete-account")
    public ApiResponse<DeleteBlockersView> deleteAccountBlockers() {
        return ApiResponse.ok(accountDeletionService.precheck(UserContext.requireUserId()));
    }

    /**
     * 提交注销申请。
     *
     * <p>被业务挡住时返回 <b>409 ACCOUNT_DELETE_BLOCKED</b>，message 里列出阻塞项；
     * 端侧应先调上面的预检端点，正常不会走到这里。
     */
    @PostMapping("/me/delete-account")
    public ApiResponse<DeleteAccountView> requestDeleteAccount(
            @Valid @RequestBody DeleteAccountRequest request) {
        DeleteAccountView view = accountDeletionService.request(
                UserContext.requireUserId(), request.password(), request.reason());
        if (!view.blockedItems().isEmpty()) {
            throw new BizException(ErrorCode.ACCOUNT_DELETE_BLOCKED,
                    "有未完成的业务需要先处理：" + describe(view.blockedItems()));
        }
        return ApiResponse.ok(view);
    }

    /**
     * 撤销注销申请（冷静期内，需登录态）。
     *
     * <p>登录态此时已不可用（注销期间登录被停用），所以这只是便捷入口；
     * 真正的撤销通道是 {@code POST /api/auth/account-deletion/cancel}（手机号 + 密码）。
     */
    @DeleteMapping("/me/delete-account")
    public ApiResponse<Void> cancelDeleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.cancelByUserId(UserContext.requireUserId(), request.password());
        return ApiResponse.ok();
    }

    private static String describe(List<com.duoduo.user.dto.UserDtos.BlockedItemView> items) {
        return items.stream()
                .map(i -> i.detail())
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
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
    /**
     * E13 · 成员身份查询（内部端点，交易 / 商品域做归属判定用）。
     *
     * <p>查不到（店主 / 无店 / 已停用）返回 null —— **停用即查不到**，
     * 这就是"停用店员即时生效"的实现机制：成员身份不进令牌快照，每次实时查。
     */
    @GetMapping("/internal/merchant-membership")
    public ApiResponse<com.duoduo.user.dto.MerchantDtos.MembershipInfo> merchantMembership(
            @org.springframework.web.bind.annotation.RequestParam("userId") Long userId) {
        return ApiResponse.ok(staffService.membershipOf(userId));
    }

    /**
     * E15 · 店铺资质与营业状态（内部端点，商品域的上架门禁用）。
     *
     * <p>与 {@code /internal/merchant-membership} 的区别：那个查「这个人是不是店员」（按 userId），
     * 这个查「这家店现在能不能卖东西」（按<b>店主</b> userId）。店主自己走商家端时
     * 不经过成员查询，所以只补成员查询是拦不住「停业店铺的店主继续上架」的。
     *
     * <p>无入驻申请（平台自营的 ownerUserId、或还没申请过）→ {@code null}，
     * 调用方按“不阻塞”处理。
     */
    @GetMapping("/internal/merchant-license")
    public ApiResponse<com.duoduo.user.dto.MerchantDtos.MerchantLicenseInfo> merchantLicense(
            @org.springframework.web.bind.annotation.RequestParam("ownerUserId") Long ownerUserId) {
        return ApiResponse.ok(onboardingService.licenseInfoOf(ownerUserId));
    }

    /**
     * E28 · 商家风险画像（内部端点，商品域判「自动放行还是转人工」用）。
     *
     * <p>与 {@code /internal/merchant-license} 是两个问题：那个判「能不能卖」，
     * 这个判「值不值得重点看」。合成一个端点会让「只想问能不能卖」的调用方
     * 也拿到违规计数，将来给这份视图加字段时两边被迫一起改。
     *
     * <p>无入驻记录 → {@code null}（调用方按「无风险标记」处理）。
     */
    @GetMapping("/internal/merchant-risk")
    public ApiResponse<com.duoduo.user.dto.MerchantDtos.MerchantRiskInfo> merchantRisk(
            @org.springframework.web.bind.annotation.RequestParam("ownerUserId") Long ownerUserId) {
        return ApiResponse.ok(onboardingService.riskInfoOf(ownerUserId));
    }

    /**
     * E28 · 上报一次商家违规（内部端点，商品域在判违规后调用）。
     *
     * <p>为什么是这个方向（商品域 → 用户域）而不是反过来：违规判定发生在**商品侧**
     * （人工驳回、抽查判违规），用户域无从得知。做成「商品域判完主动上报」，
     * 比用户域定时扫商品库少一次全量扫描，也不会因为扫描周期让停业迟到。
     */
    @PostMapping("/internal/merchant-violation")
    public ApiResponse<com.duoduo.user.dto.MerchantDtos.MerchantViolationInfo> merchantViolation(
            @org.springframework.web.bind.annotation.RequestParam("ownerUserId") Long ownerUserId,
            @org.springframework.web.bind.annotation.RequestParam(value = "reason", required = false)
            String reason) {
        return ApiResponse.ok(violationService.markViolation(ownerUserId, reason));
    }

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
