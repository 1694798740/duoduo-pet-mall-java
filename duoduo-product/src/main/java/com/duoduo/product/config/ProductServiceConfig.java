package com.duoduo.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.duoduo.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品服务基础设施 Bean。
 */
@Configuration
public class ProductServiceConfig {

    /**
     * 分页插件。
     * 不写死 DbType，而是由 MyBatis-Plus 依据连接自动识别（H2 与 MySQL 都支持 LIMIT 语法），
     * 这样本地 H2 与生产 MySQL 共用同一份代码。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${duoduo.id.datacenter-id:1}") long datacenterId,
            @Value("${duoduo.id.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(datacenterId, workerId);
    }
}
