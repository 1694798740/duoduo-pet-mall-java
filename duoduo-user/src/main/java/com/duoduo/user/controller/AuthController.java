package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.user.dto.UserDtos.LoginRequest;
import com.duoduo.user.dto.UserDtos.LoginResponse;
import com.duoduo.user.dto.UserDtos.RefreshRequest;
import com.duoduo.user.dto.UserDtos.RegisterRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.service.AuthService;
import jakarta.servlet.http.Cookie;
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
 * 令牌同时以 httpOnly Cookie 返回（浏览器用）与响应体返回（服务端调用方用），
 * 前端不允许把令牌写入 localStorage。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String COOKIE_ACCESS = "dd_at";
    private static final String COOKIE_REFRESH = "dd_rt";

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
        Cookie access = new Cookie(COOKIE_ACCESS, result.accessToken());
        access.setHttpOnly(true);
        access.setPath("/");
        access.setMaxAge((int) result.expiresInSeconds());
        response.addCookie(access);

        Cookie refresh = new Cookie(COOKIE_REFRESH, result.refreshToken());
        refresh.setHttpOnly(true);
        refresh.setPath("/");
        refresh.setMaxAge((int) java.time.Duration.ofDays(7).toSeconds());
        response.addCookie(refresh);
    }

    private void clearCookies(HttpServletResponse response) {
        Cookie access = new Cookie(COOKIE_ACCESS, "");
        access.setPath("/");
        access.setMaxAge(0);
        response.addCookie(access);

        Cookie refresh = new Cookie(COOKIE_REFRESH, "");
        refresh.setPath("/");
        refresh.setMaxAge(0);
        response.addCookie(refresh);
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
