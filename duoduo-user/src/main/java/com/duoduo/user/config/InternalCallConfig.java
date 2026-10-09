package com.duoduo.user.config;

import com.duoduo.common.web.internal.InternalCallAuth;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置（E15）：给用户域发出的所有 Feign 请求加上内部调用凭据。
 *
 * <p>用户域此前只<b>提供</b>内部端点（{@code /api/users/internal/**}），这是第一次<b>调用</b>
 * 别人的内部端点（社交域的通知写入、商品域的批量下架）。凭据口径与交易域 / 履约域 /
 * 商品域完全一致：{@link InternalCallAuth} 签发「调用方身份 + 时间戳 + 一次性 nonce」的
 * HMAC 签名，密钥按调用方身份派生，根密钥仍是同一个 {@code INTERNAL_TOKEN}。
 *
 * <p>统一在拦截器注入而不是在业务代码里手写请求头：漏写是 403 而不是编译错误，
 * 而且只在真机上才暴露 —— 这类错误一定会有人犯。
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(InternalCallAuth internalCallAuth) {
        // 每次调用都重新签发：签名里带一次性 nonce，复用同一个请求头会被服务端判为重放。
        // 必须传 template.method()（方法名），服务端校验的就是「方法」这一项。
        return template -> template.header(InternalCallAuth.HEADER,
                internalCallAuth.sign(template.method()));
    }
}
