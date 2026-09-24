package com.duoduo.ai.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置：给所有 Feign 请求加内部调用凭据（与履约域 InternalCallConfig 同一份写法）。
 * 商品域的内部候选接口要求 {@code X-Internal-Token}；统一在拦截器注入，业务代码不手写请求头。
 */
@Configuration
public class FeignInternalConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${duoduo.internal.token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
