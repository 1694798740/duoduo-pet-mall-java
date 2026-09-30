package com.duoduo.social.config;

import com.duoduo.common.web.internal.InternalCallAuth;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置：给所有 Feign 请求加上内部调用凭据。
 *
 * <p>用户域 / 商品域 / 履约域的内部接口（briefs / roles / internal cards / 分享单）
 * 都要求 {@code X-Internal-Token}。统一在拦截器注入，避免业务代码里手写请求头
 * （漏了是 403 而不是编译错误 —— 而下游「取不到资料就降级」的容错会把 403
 * 吞成空列表，表现为业务层 404「用户不存在」，极难排查）。
 *
 * <p>密钥与其它服务共用同一个环境变量 {@code INTERNAL_TOKEN}；
 * 容器编排里各服务注入同一个值，靠「服务名 + 内网」而非「每服务一密钥」来控制面。
 * <p>第五十八轮（P0-3）：这里不再把密钥原样放进请求头 —— {@link InternalCallAuth}
 * 改为签发「调用方身份 + 时间戳 + 一次性 nonce」的 HMAC 签名，密钥按调用方身份派生，
 * 想单独轮换时用 {@code duoduo.internal.caller-secrets} 给某个调用方指定显式密钥。
 * 根密钥仍是同一个 {@code INTERNAL_TOKEN} 环境变量，容器编排一行都不用改。
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(InternalCallAuth internalCallAuth) {
        // 每次调用都重新签发：签名里带一次性 nonce，复用同一个请求头会被判为重放。
        // 这里用 template.path()（解析后的路径、不含查询串），与服务端
        // request.getRequestURI() 口径一致 —— 两侧都必须取「方法 + 路径」。
        return template -> template.header(InternalCallAuth.HEADER,
                internalCallAuth.sign(template.method(), template.path()));
    }
}
