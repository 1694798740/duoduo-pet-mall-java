package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.LoginRequest;
import com.duoduo.user.dto.UserDtos.LoginResponse;
import com.duoduo.user.dto.UserDtos.RefreshRequest;
import com.duoduo.user.dto.UserDtos.RegisterRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.dto.UserDtos.WechatLoginRequest;
import com.duoduo.user.service.AuthService;
import com.duoduo.user.support.AuthCookieWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与账号接口。
 *
 * <p>控制器只做「解析请求 → 调服务 → 格式化响应」，不含业务规则。
 *
 * <p><b>两套令牌下发通道，按调用方形态分流（A14 修复）</b>：
 * <ul>
 *   <li><b>浏览器（Web）</b>：`/login`、`/register`、`/refresh`、`/wechat` 维持原样 ——
 *       响应体是扁平的 {@link UserView}，令牌只走 httpOnly Cookie，前端不允许把令牌写入
 *       localStorage。这几个端点的形状是**既有契约**，前端与 smoke 断言都依赖它，不动。</li>
 *   <li><b>小程序 / 移动端</b>：小程序没有 Cookie 语义，令牌必须走响应体并由端侧存入
 *       自己的 storage，再以 {@code Authorization: Bearer} 回传。为此提供
 *       {@code /login/token}、{@code /wechat/token} 两个**纯增量**端点，
 *       返回 {@link LoginResponse}（user + accessToken + refreshToken + expiresInSeconds）。</li>
 * </ul>
 *
 * <p>刻意用「新增端点」而不是「改既有端点的响应体」：后者会让 Web 侧
 * （读扁平 UserView）与既有冒烟断言同时失效，属于破坏性变更；新增端点对现有调用方零影响。
 * 两条通道共用同一个 {@link AuthService}，令牌签发与校验逻辑只有一份。
 *
 * <p>安全：`/token` 两个端点与对应的浏览器端点同级免登（它们本身就是「还没有登录态」时用的），
 * 已在网关白名单中**逐条精确列出**；{@code /wechat/bind} 需要登录态，绝不在白名单内。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Cookie 名与属性统一在 AuthCookieWriter：角色升级后重签令牌也要用同一份口径
    private static final String COOKIE_ACCESS = AuthCookieWriter.COOKIE_ACCESS;
    private static final String COOKIE_REFRESH = AuthCookieWriter.COOKIE_REFRESH;

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletResponse response) {
        LoginResponse result = authService.register(request);
        writeCookies(response, result);
        return ApiResponse.ok(result.user());
    }

    @PostMapping("/login")
    public ApiResponse<UserView> login(@Valid @RequestBody LoginRequest request,
                                       @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                       HttpServletResponse response) {
        LoginResponse result = authService.login(request, userAgent);
        writeCookies(response, result);
        return ApiResponse.ok(result.user());
    }

    /**
     * 移动端密码登录：令牌走响应体，不写 Cookie。
     *
     * <p>与 {@link #login} 共用同一份认证逻辑（密码校验、失败计数、令牌签发都只有一份），
     * 差别仅在令牌的下发通道。
     */
    @PostMapping("/login/token")
    public ApiResponse<LoginResponse> loginWithToken(@Valid @RequestBody LoginRequest request,
                                                     @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(authService.login(request, userAgent));
    }

    /** 前端在收到 401 后自动调用；成功即可重试原请求 */
    @PostMapping("/refresh")
    public ApiResponse<UserView> refresh(@RequestBody(required = false) RefreshRequest request,
                                         HttpServletRequest servletRequest,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                         HttpServletResponse response) {
        String token = request != null && request.refreshToken() != null && !request.refreshToken().isBlank()
                ? request.refreshToken()
                : readCookie(servletRequest, COOKIE_REFRESH);
        LoginResponse result = authService.refresh(token, userAgent);
        writeCookies(response, result);
        return ApiResponse.ok(result.user());
    }

    /** 移动端刷新令牌：与 {@link #refresh} 同一逻辑，令牌走响应体（refreshToken 可由端侧传入） */
    @PostMapping("/refresh/token")
    public ApiResponse<LoginResponse> refreshWithToken(@RequestBody(required = false) RefreshRequest request,
                                                       @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String token = request == null ? null : request.refreshToken();
        return ApiResponse.ok(authService.refresh(token, userAgent));
    }

    /**
     * A13 · 微信小程序登录（免登白名单端点）。
     *
     * <p>小程序环境没有 Cookie（平台差异），令牌只走响应体、由小程序端存入其
     * storage，并以 Bearer 头回传；访问令牌短时效，过期由 401 流程重走登录。
     *
     * <p><b>注意</b>：本端点保持「扁平 UserView」的历史形状（既有冒烟断言依赖），
     * 小程序请使用 {@link #wechatLoginWithToken} —— 它才真正把令牌放进响应体。
     */
    @PostMapping("/wechat")
    public ApiResponse<UserView> wechatLogin(@Valid @RequestBody WechatLoginRequest request,
                                             @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        LoginResponse result = authService.wechatLogin(request.code(), userAgent);
        return ApiResponse.ok(result.user());
    }

    /**
     * 小程序微信登录：code 换令牌，令牌随响应体下发（端侧存 storage + Bearer 回传）。
     *
     * <p>未绑定微信的账号返回 422（引导先用手机号登录再绑定）；openid 已属他人返回 409。
     */
    @PostMapping("/wechat/token")
    public ApiResponse<LoginResponse> wechatLoginWithToken(@Valid @RequestBody WechatLoginRequest request,
                                                           @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(authService.wechatLogin(request.code(), userAgent));
    }

    /** A13 · 登录态下绑定微信（需 Bearer 令牌，不在网关白名单内） */
    @PostMapping("/wechat/bind")
    public ApiResponse<UserView> wechatBind(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.ok(authService.wechatBind(UserContext.requireUserId(), request.code()));
    }

    /**
     * A14 · 解绑当前账号的微信（需登录，**不在网关白名单**）。
     *
     * <p>code 用来证明"操作者持有这个微信"；解绑的是 {@code UserContext} 里的当前账号，
     * 与"别人的微信"无关。
     */
    @PostMapping("/wechat/unbind")
    public ApiResponse<UserView> wechatUnbind(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.ok(authService.wechatUnbind(UserContext.requireUserId(), request.code()));
    }

    /**
     * A14 · 换绑：把当前账号的微信换成 code 对应的新微信（需登录，**不在网关白名单**）。
     *
     * <p>面向"换手机/换微信号"场景；新微信已被他人绑定 → 409。
     */
    @PostMapping("/wechat/rebind")
    public ApiResponse<UserView> wechatRebind(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.ok(authService.wechatRebind(UserContext.requireUserId(), request.code()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshRequest request,
                                    HttpServletRequest servletRequest,
                                    HttpServletResponse response) {
        String token = request != null && request.refreshToken() != null && !request.refreshToken().isBlank()
                ? request.refreshToken()
                : readCookie(servletRequest, COOKIE_REFRESH);
        authService.logout(token);
        clearCookies(response);
        return ApiResponse.ok();
    }

    // ---------- 内部方法 ----------

    private void writeCookies(HttpServletResponse response, LoginResponse result) {
        AuthCookieWriter.write(response, result);
    }

    private void clearCookies(HttpServletResponse response) {
        AuthCookieWriter.clear(response);
    }

    private String readCookie(HttpServletRequest request, String name) {
        return AuthCookieWriter.read(request, name);
    }
}
