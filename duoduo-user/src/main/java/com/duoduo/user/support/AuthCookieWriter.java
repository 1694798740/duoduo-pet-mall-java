package com.duoduo.user.support;

import com.duoduo.user.dto.UserDtos.LoginResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;

/**
 * 令牌 Cookie 的读写（浏览器通道专用）。
 *
 * <p>抽出来是为了让「角色升级后重签访问令牌」与「登录 / 刷新」共用同一份 Cookie 口径：
 * 访问令牌的属性（httpOnly、path=/、有效期）只要出现两份写法，就会在某一处悄悄漂移 ——
 * 而漂移的表现是「有的地方能续期、有的地方 401」，排查起来极费时。
 *
 * <p>小程序通道不经过这里：小程序没有 Cookie 语义，令牌走响应体由端侧自存（见 AuthController 注释）。
 */
public final class AuthCookieWriter {

    /** 访问令牌 Cookie 名 */
    public static final String COOKIE_ACCESS = "dd_at";
    /** 刷新令牌 Cookie 名 */
    public static final String COOKIE_REFRESH = "dd_rt";

    private static final int REFRESH_MAX_AGE_SECONDS = (int) Duration.ofDays(7).toSeconds();

    private AuthCookieWriter() {
    }

    /** 写入一对令牌（登录 / 刷新） */
    public static void write(HttpServletResponse response, LoginResponse result) {
        writeAccess(response, result.accessToken(), result.expiresInSeconds());
        writeRefresh(response, result.refreshToken());
    }

    /**
     * 只覆盖访问令牌（第五十八轮 P1-6：角色变更后即时生效用）。
     *
     * <p>刻意<b>不</b>动刷新令牌：轮换刷新令牌会吊销旧的那一批，
     * 等于把用户其它端（手机 / 另一台电脑）一起踢下线 ——
     * 为「让角色立刻生效」付出「全端掉线」的代价不值得，
     * 而访问令牌本来就短时效，覆盖它没有任何副作用。
     */
    public static void writeAccess(HttpServletResponse response, String accessToken, long expiresInSeconds) {
        Cookie access = new Cookie(COOKIE_ACCESS, accessToken);
        access.setHttpOnly(true);
        access.setPath("/");
        access.setMaxAge((int) Math.max(1, expiresInSeconds));
        response.addCookie(access);
    }

    public static void writeRefresh(HttpServletResponse response, String refreshToken) {
        Cookie refresh = new Cookie(COOKIE_REFRESH, refreshToken);
        refresh.setHttpOnly(true);
        refresh.setPath("/");
        refresh.setMaxAge(REFRESH_MAX_AGE_SECONDS);
        response.addCookie(refresh);
    }

    /** 登出：把两个 Cookie 立即置空 */
    public static void clear(HttpServletResponse response) {
        expire(response, COOKIE_ACCESS);
        expire(response, COOKIE_REFRESH);
    }

    public static String read(HttpServletRequest request, String name) {
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

    private static void expire(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
