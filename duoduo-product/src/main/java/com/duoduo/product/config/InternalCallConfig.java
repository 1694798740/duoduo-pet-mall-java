package com.duoduo.product.config;

import com.duoduo.common.web.internal.InternalCallAuth;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置（E14）：给商品域发出的所有 Feign 请求加上内部调用凭据。
 *
 * <p>用户域的成员查询端点在 {@code /api/users/internal/**} 下，由用户域的
 * {@code InternalTokenFilter} 校验。这里统一注入，避免每个业务方法手写请求头（一定会有人漏）。
 *
 * <p>与交易域 {@code InternalCallConfig} 完全同构：不再是「把共享密钥原样放进请求头」，
 * 而是签发「调用方身份 + 时间戳 + 一次性 nonce」的 HMAC 签名（{@link InternalCallAuth}）。
 * <b>每次调用重新签发</b> —— 签名带一次性 nonce，复用同一个请求头会被服务端判为重放。
 *
 * <p>密钥与其它服务共用同一个配置项（{@code duoduo.internal.token}，环境变量
 * {@code INTERNAL_TOKEN} 覆盖），容器编排一行都不用改。
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(InternalCallAuth internalCallAuth) {
        // 只签 METHOD、不签路径：Feign 的 template.path() 取不到 @FeignClient(path=...) 那段前缀，
        // 签进去就是两侧算出的串不同，表现为全站内部调用 403（交易域已踩过，注释见其配置类）。
        return template -> template.header(InternalCallAuth.HEADER,
                internalCallAuth.sign(template.method()));
    }
}
