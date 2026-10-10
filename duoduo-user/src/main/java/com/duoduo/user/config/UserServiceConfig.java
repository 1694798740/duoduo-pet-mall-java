package com.duoduo.user.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.common.web.tenant.TenantLineInterceptors;
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

    /**
     * MyBatis-Plus 分页插件（A7 运营账号列表首次用到）。
     * maxLimit 与交易域同口径（100）：pageSize 是客户端可传的参数，
     * 没有硬上限的「分页」等于把全表导出接口挂了个分页的名字。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // E20 · 租户条件自动追加（用户域的两张租户表）。
        //
        // ★ 本域是**唯一**列名不统一的地方：t_audit_log 用 tenant_id，t_audit_sample 用 seller_id。
        //   MP 的 TenantLineHandler 只能返回一个列名，所以 TenantLineInterceptors 会按列名
        //   自动拆成两个拦截器实例（同一个清单里传两张表即可，不需要这里手工分组）。
        //
        // ★ 必须在分页插件之前 add（多租户 → 分页）。
        TenantLineInterceptors.forTables("t_audit_log", "t_audit_sample")
                .forEach(interceptor::addInnerInterceptor);
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
