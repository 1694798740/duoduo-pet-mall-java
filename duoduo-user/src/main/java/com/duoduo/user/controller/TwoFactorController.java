package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.TotpDisableRequest;
import com.duoduo.user.dto.UserDtos.TotpEnableRequest;
import com.duoduo.user.dto.UserDtos.TotpEnableView;
import com.duoduo.user.dto.UserDtos.TotpSetupView;
import com.duoduo.user.dto.UserDtos.TotpStatusView;
import com.duoduo.user.service.TwoFactorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E21 · 两步验证（TOTP）自助管理。
 *
 * <p><b>路径刻意挂在 {@code /api/auth/2fa/**} 而不是 {@code /api/admin/**}</b>：
 * 它是"账号安全设置"，与登录同属认证域；而且**它必须在账号还没通过第二步时也能被访问到**
 * —— 否则会形成死锁：要绑定 2FA 得先进后台，要进后台得先绑 2FA。
 * 现在这条链路是通的：登录成功（此时若账号尚未启用 2FA 则不要求动态码）→ 拿到令牌 →
 * 调本控制器的 setup/enable 完成绑定。
 *
 * <p><b>网关白名单里没有这几个路径</b>（白名单是精确列举的，不会误放），
 * 因此它们一律要求登录态；未登录访问会被网关挡在 401，压根到不了这里。
 *
 * <p><b>与"强制"的关系</b>：本控制器只负责"让用户能绑、能关"。
 * "绑定之后必须验证"这条规则落在登录链路（{@code AuthService.login}），
 * 不在这里重复判断 —— 判定点只有一个，见 {@code TwoFactorService} 的类注释。
 */
@RestController
@RequestMapping("/api/auth/2fa")
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    public TwoFactorController(TwoFactorService twoFactorService) {
        this.twoFactorService = twoFactorService;
    }

    /** 当前账号的两步验证状态（前端据此决定是否显示绑定引导） */
    @GetMapping("/status")
    public ApiResponse<TotpStatusView> status() {
        return ApiResponse.ok(twoFactorService.status(UserContext.requireUserId()));
    }

    /**
     * 第一步：获取密钥与 otpauth 链接（此时尚未启用）。
     *
     * <p>不加 {@code @Audited}：它只是**生成一个候选密钥**，还没有改变账号的安全状态
     * （未启用的密钥不参与任何校验）。真正改变状态的是 enable —— 那一步留痕。
     * 若这里也记一条，"生成过几次二维码"会淹没真正重要的"什么时候启用的"。
     */
    @PostMapping("/setup")
    public ApiResponse<TotpSetupView> setup() {
        return ApiResponse.ok(twoFactorService.setup(UserContext.requireUserId()));
    }

    /** 第二步：用一次正确的动态码确认绑定，并**一次性**返回恢复码明文 */
    @PostMapping("/enable")
    @Audited(action = AuditAction.USER_TOTP_ENABLE)
    public ApiResponse<TotpEnableView> enable(@Valid @RequestBody TotpEnableRequest request) {
        return ApiResponse.ok(twoFactorService.enable(UserContext.requireUserId(), request.code()));
    }

    /**
     * 解绑（动态码与恢复码二选一）。
     *
     * <p>请求体设为可选：手机丢失、只能用恢复码的场景也走同一个端点，
     * 而"两个都不给"由服务层给出明确的 400 提示（比在这里做参数校验更容易说清原因）。
     */
    @PostMapping("/disable")
    @Audited(action = AuditAction.USER_TOTP_DISABLE)
    public ApiResponse<Void> disable(@RequestBody(required = false) TotpDisableRequest request) {
        String code = request == null ? null : request.code();
        String recoveryCode = request == null ? null : request.recoveryCode();
        twoFactorService.disable(UserContext.requireUserId(), code, recoveryCode);
        return ApiResponse.ok();
    }
}
