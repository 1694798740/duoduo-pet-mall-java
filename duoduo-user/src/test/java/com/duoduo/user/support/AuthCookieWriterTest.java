package com.duoduo.user.support;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cookie 安全属性单测（2026-10-28）。
 *
 * <p>覆盖三件事：HTTPS 下发 Secure + SameSite；SameSite=None 缺 Secure 时退回 Lax；
 * 登出与登录使用同一组属性（否则浏览器会留下同名旧 Cookie，表现为「退出后又登录了」）。
 */
class AuthCookieWriterTest {

    @AfterEach
    void resetPolicy() {
        // 静态配置会被其它测试共享，必须还原成开发默认值
        AuthCookieWriter.configure(false, "Lax");
    }

    @Test
    void productionPolicyAddsSecureAndSameSite() {
        AuthCookieWriter.configure(true, "Strict");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthCookieWriter.writeAccess(response, "access-token", 900, null);

        Cookie cookie = response.getCookie("dd_at");
        assertNotNull(cookie);
        assertTrue(cookie.getSecure(), "HTTPS 生产必须带 Secure");
        assertTrue(cookie.isHttpOnly(), "令牌不能被 JS 读取");
        assertEquals("/", cookie.getPath());
        assertEquals("Strict", cookie.getAttribute("SameSite"));
    }

    @Test
    void noneWithoutSecureFallsBackToLax() {
        AuthCookieWriter.configure(false, "None");
        assertEquals("Lax", AuthCookieWriter.sameSite(), "None 缺 Secure 会被浏览器丢弃，必须退回 Lax");
    }

    @Test
    void clearUsesSameAttributesAsWrite() {
        AuthCookieWriter.configure(true, "Lax");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthCookieWriter.clear(response, null);

        Cookie cleared = response.getCookie("dd_at");
        assertNotNull(cleared);
        assertEquals(0, cleared.getMaxAge(), "清 Cookie 必须把 MaxAge 置 0");
        assertTrue(cleared.getSecure(), "删除属性必须与写入一致，否则删不掉");
        assertEquals("Lax", cleared.getAttribute("SameSite"));
    }
}
