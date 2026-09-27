package com.duoduo.social.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${duoduo.internal.token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
