package com.duoduo.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 多多宠物商店 · API 网关。
 *
 * <p>职责边界（只做接入层的事，不写业务）：
 * <ol>
 *   <li>路由转发到各业务服务</li>
 *   <li>JWT 前置校验：校验不过直接 401，不把无效流量打给后端</li>
 *   <li>身份透传：把校验后的 userId/role 写入内部请求头，业务服务不再重复解析令牌</li>
 *   <li>链路 ID 透传，保证一个请求的日志可以跨服务串联</li>
 * </ol>
 * 限流 / 熔断（Sentinel）与灰度发布在 P3 阶段接入。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
