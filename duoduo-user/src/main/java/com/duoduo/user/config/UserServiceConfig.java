package com.duoduo.user.config;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

/**
 * 用户服务基础设施 Bean。
 *
 * <p>密钥与 ID 节点的取值来自配置（环境变量 / 配置中心），
 * 启动时即校验强度 —— 配置错误要快速失败，而不是等到第一个请求才炸。
 */
@Configuration
public class UserServiceConfig {

    @Bean
    public JwtTokenService jwtTokenService(
            @Value("${duoduo.security.jwt-secret}") String secret,
            @Value("${duoduo.security.access-token-ttl:15m}") Duration accessTtl,
            @Value("${duoduo.security.refresh-token-ttl:7d}") Duration refreshTtl) {
        return new JwtTokenService(secret, accessTtl, refreshTtl);
    }

    /**
     * 雪花 ID 生成器。集群部署时 datacenterId / workerId 必须由配置中心按实例分配，
     * 组合唯一是 ID 不重复的前提。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${duoduo.id.datacenter-id:1}") long datacenterId,
            @Value("${duoduo.id.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(datacenterId, workerId);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
