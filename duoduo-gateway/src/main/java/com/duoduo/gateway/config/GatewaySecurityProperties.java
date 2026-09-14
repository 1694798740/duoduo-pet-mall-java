package com.duoduo.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 网关安全与 JWT 配置。
 * 密钥通过环境变量注入（DUODUO_JWT_SECRET），默认值仅供本地开发，
 * 生产必须由配置中心下发且不得进入代码库。
 */
@Configuration
@ConfigurationProperties(prefix = "duoduo.security")
public class GatewaySecurityProperties {

    /** HMAC 密钥，长度必须 ≥ 32 字节（256 bit） */
    private String jwtSecret = "duoduo-local-dev-secret-please-change-in-production-32b";

    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshTokenTtl = Duration.ofDays(7);

    /** 免认证路径（Ant 风格），其余路径必须携带合法令牌 */
    private List<String> whitelist = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/sms/**",
            "/api/auth/refresh",
            "/api/products/**",
            "/api/categories/**",
            "/api/home/**",
            "/api/listings/**",
            "/api/meta/**",
            "/api/coupons/**",
            "/api/duoduo/chat",
            "/api/duoduo/capabilities",
            "/actuator/**",
            "/health/**"
    );

    @Bean
    public com.duoduo.common.security.JwtTokenService jwtTokenService() {
        return new com.duoduo.common.security.JwtTokenService(jwtSecret, accessTokenTtl, refreshTokenTtl);
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }
}
