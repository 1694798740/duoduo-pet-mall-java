package com.duoduo.trade.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置：给所有 Feign 请求加上内部调用凭据。
 *
 * <p>商品服务的库存写接口（冻结 / 释放 / 扣减）要求 {@code X-Internal-Token}，
 * 这里统一注入，避免在每个业务方法里手写请求头（一定会有人漏）。
 *
 * <p>密钥与商品服务共用同一个配置项名（{@code duoduo.internal.token}），
 * 由环境变量 {@code INTERNAL_TOKEN} 覆盖；容器编排里两个服务注入同一个值。
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${duoduo.internal.token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
