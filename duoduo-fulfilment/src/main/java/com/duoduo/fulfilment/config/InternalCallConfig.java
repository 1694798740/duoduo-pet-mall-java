package com.duoduo.fulfilment.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置：给所有 Feign 请求加上内部调用凭据。
 *
 * <p>交易域的内部订单查询接口要求 {@code X-Internal-Token}。统一在拦截器注入，
 * 避免业务代码里手写请求头（一定会有人漏，而漏了是 403 而不是编译错误）。
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
