package com.duoduo.user.config;

import com.duoduo.user.support.AuthCookieWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把「Cookie 安全策略」从环境变量注入到 {@link AuthCookieWriter}（2026-10-28）。
 *
 * <p>为什么不在每次写 Cookie 时读配置：Cookie 写入是静态工具方法，
 * 调用点分散在登录 / 刷新 / 角色变更 / 登出；每次注入 Environment 既啰嗦又容易漏。
 * 启动时配置一次，运行期只读 volatile 字段。
 *
 * <p>生产必须 {@code COOKIE_SECURE=true}；本地 HTTP 下保持 false。
 */
@Component
public class AuthCookieSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthCookieSecurityConfig.class);

    public AuthCookieSecurityConfig(
            @Value("${duoduo.auth.cookie-secure:false}") boolean secure,
            @Value("${duoduo.auth.cookie-same-site:Lax}") String sameSite) {
        AuthCookieWriter.configure(secure, sameSite);
        log.info("认证 Cookie 策略：secure={}, sameSite={}", AuthCookieWriter.isSecure(), AuthCookieWriter.sameSite());
        if (!secure) {
            log.info("当前为 HTTP 开发模式（Cookie 未加 Secure）；生产环境请设置 COOKIE_SECURE=true");
        }
    }
}
