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
            // 微信小程序登录（A13）：wx.login 的 code 换令牌，此时自然没有登录态。
            // 只放行 /api/auth/wechat 这一条；/api/auth/wechat/bind 是「登录态下绑定
            // 微信」，需要 Bearer 令牌，绝不能进白名单 —— 否则拿到任意令牌前缀即可
            // 把别人的微信绑到自己的账号上。
            "/api/auth/wechat",
            "/api/products/**",
            "/api/categories/**",
            "/api/home/**",
            "/api/listings/**",
            "/api/meta/**",
            // 领券中心（A5）：营销落地页，未登录访客也应看到有什么券 —— 这正是它的作用。
            // 只放行 /api/coupons/templates 这一条**精确**只读路径：
            // 白名单按路径匹配、不区分方法，因此绝不能写成 /api/coupons/**——
            // 那会把「领券」「我的券包」「内部锁券/核销/解锁」一起放行，
            // 其中内部接口本该由服务间凭据保护，一次配置就变成线上越权洞。
            "/api/coupons/templates",
            // 商品评价是「公开可见的购物决策信息」：未登录也能看（否则首次访问者无法判断口碑）。
            // 白名单按路径而非方法匹配，因此这里**只放行只读的 /product/** 子路径**：
            // 发表评价 / 我的评价 / 追评都在 /api/reviews 与本路径之外，仍由网关强制登录。
            "/api/reviews/product/**",
            // 卖家信用分（A4）：同样是「购物决策信息」——买家下单前要看卖家靠不靠谱，
            // 与商品评价同性质，未登录访客也应看得到。只放行这条精确路径（读接口，无副作用）；
            // 刷新回写走 /api/reviews/internal/**，不经网关，且由履约域 InternalTokenFilter 校验凭据。
            "/api/reviews/seller/*/credit",
            // AI 导购（A9）：购物咨询本身就是「还没决定买不买」的场景，
            // 未登录访客先问两句再决定注册，是这个入口存在的意义 —— 强制登录等于把导购废掉。
            // 只放行 chat（对话）与 capabilities（前端据此决定按钮是否可点）两条精确只读路径；
            // 白名单按路径、不区分方法，所以绝不能写成 /api/duoduo/**。
            // 「需登录才能做的操作」（下单/领券/加购）不由网关拦截，而是靠导购回复里的文案引导登录——
            // 它本身不写任何业务数据，故放行不构成越权面。
            "/api/duoduo/chat",
            "/api/duoduo/capabilities",
            // 限时秒杀（A14）：活动浏览与领券中心同性质 —— 未登录也要看到「正在抢什么」才有导流意义。
            // 只放行 activities 两级**只读**路径；POST /api/seckill/orders 在路径之外，仍由网关强制登录
            // （白名单按路径、不区分方法，所以绝不能写成 /api/seckill/**）。
            "/api/seckill/activities",
            "/api/seckill/activities/*",
            // 渠道回调来自渠道服务器，没有我们的登录态；安全靠验签 + 金额核对 + CAS 幂等
            "/api/payments/callback/**",
            // 沙箱收银台：扫码设备上没有买家登录态（现实中谁扫码谁付款）
            "/api/payments/sandbox/**",
            // 沙箱收银台页面（服务端渲染，与上一条同理由放行）
            "/sandbox/pay/**",
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
