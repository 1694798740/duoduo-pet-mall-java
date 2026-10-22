package com.duoduo.user.support;

import com.duoduo.common.security.ClientEntry;
import com.duoduo.user.dto.UserDtos.LoginResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;

/**
 * 令牌 Cookie 的读写（浏览器通道专用）。
 *
 * <h2>★ 为什么 Cookie 名要按入口区分（2026-10-21 修复）</h2>
 * 原来三个站点共用 {@code dd_at} / {@code dd_rt}。问题在于：
 * <ul>
 *   <li>Cookie <b>按主机名隔离，但不区分端口</b> ——
 *       {@code localhost:8088}（商城）与 {@code localhost:8091}（商家端）属于同一个主机名，
 *       共用同一个 Cookie 罐；</li>
 *   <li>于是「在商家工作台登录一个商家账号」会<b>覆盖</b>商城里的买家会话，
 *       反之亦然 —— 用户看到的就是「明明是商家登录，个人中心却是买家账号」；</li>
 *   <li>仅靠 nginx 的三份 server_name（merchant.localhost / admin.localhost）不足以兜底：
 *       开发与排障时常直接用 {@code localhost:端口} 访问，串台立刻复现。</li>
 * </ul>
 *
 * <h2>做法</h2>
 * 按请求头 {@code X-Duoduo-Entry}（三份 nginx 各自注入、网关归一化后下发）给 Cookie 名加后缀：
 * <ul>
 *   <li>商城 / 未声明 → {@code dd_at}、{@code dd_rt}（保持向后兼容：老会话继续有效）</li>
 *   <li>商家端 → {@code dd_at_m}、{@code dd_rt_m}</li>
 *   <li>平台后台 → {@code dd_at_a}、{@code dd_rt_a}</li>
 * </ul>
 *
 * <p><b>读取时商家/后台不回退到基础名</b>：否则「商家端没登录」会被商城会话顶替，
 * 那正是这次要消灭的串台。商城/未声明仍允许回退基础名 —— 语义相同，且保住老会话。
 *
 * <p>小程序通道不经过这里：没有 Cookie 语义，令牌走响应体由端侧自存。
 */
public final class AuthCookieWriter {

    /** 访问令牌 Cookie 基础名（商城） */
    public static final String COOKIE_ACCESS = "dd_at";
    /** 刷新令牌 Cookie 基础名（商城） */
    public static final String COOKIE_REFRESH = "dd_rt";

    private static final int REFRESH_MAX_AGE_SECONDS = (int) Duration.ofDays(7).toSeconds();

    private AuthCookieWriter() {
    }

    /** 某入口的访问令牌 Cookie 名 */
    public static String accessName(String entry) {
        return COOKIE_ACCESS + suffix(entry);
    }

    /** 某入口的刷新令牌 Cookie 名 */
    public static String refreshName(String entry) {
        return COOKIE_REFRESH + suffix(entry);
    }

    private static String suffix(String entry) {
        String normalized = ClientEntry.normalize(entry);
        if (ClientEntry.MERCHANT.equals(normalized)) {
            return "_m";
        }
        if (ClientEntry.ADMIN.equals(normalized)) {
            return "_a";
        }
        return "";
    }

    /** 写入一对令牌（登录 / 刷新） */
    public static void write(HttpServletResponse response, LoginResponse result, String entry) {
        writeAccess(response, result.accessToken(), result.expiresInSeconds(), entry);
        writeRefresh(response, result.refreshToken(), entry);
    }

    /**
     * 只覆盖访问令牌（第五十八轮 P1-6：角色变更后即时生效用）。
     *
     * <p>刻意<b>不</b>动刷新令牌：轮换刷新令牌会吊销旧的那一批，
     * 等于把用户其它端（手机 / 另一台电脑）一起踢下线 ——
     * 为「让角色立刻生效」付出「全端掉线」的代价不值得，
     * 而访问令牌本来就短时效，覆盖它没有任何副作用。
     */
    public static void writeAccess(HttpServletResponse response, String accessToken,
                                   long expiresInSeconds, String entry) {
        Cookie access = new Cookie(accessName(entry), accessToken);
        access.setHttpOnly(true);
        access.setPath("/");
        access.setMaxAge((int) Math.max(1, expiresInSeconds));
        response.addCookie(access);
    }

    public static void writeRefresh(HttpServletResponse response, String refreshToken, String entry) {
        Cookie refresh = new Cookie(refreshName(entry), refreshToken);
        refresh.setHttpOnly(true);
        refresh.setPath("/");
        refresh.setMaxAge(REFRESH_MAX_AGE_SECONDS);
        response.addCookie(refresh);
    }

    /**
     * 登出：只清本入口的 Cookie。
     *
     * <p>不能顺手清基础名：同一个 host 下（localhost:8088 / :8091）那会把<b>商城会话也登出</b>，
     * 正是这次要修的串台问题之一。商城入口本身的名字就是基础名，行为不变。
     */
    public static void clear(HttpServletResponse response, String entry) {
        expire(response, accessName(entry));
        expire(response, refreshName(entry));
    }

    /**
     * 读取令牌 Cookie：先取本入口的名字；商城 / 未声明再回退基础名。
     *
     * <p>商家与后台<b>不回退</b> —— 否则「商家站点没登录」会被商城会话顶上去。
     */
    public static String read(HttpServletRequest request, String baseName, String entry) {
        String normalized = ClientEntry.normalize(entry);
        String scoped = baseName + suffix(normalized);
        String value = cookieValue(request, scoped);
        if (value != null) {
            return value;
        }
        if (normalized == null || ClientEntry.MALL.equals(normalized)) {
            return cookieValue(request, baseName);
        }
        return null;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
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
