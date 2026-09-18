package com.duoduo.fulfilment.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.duoduo.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 履约服务基础设施 Bean。
 */
@Configuration
public class FulfilmentServiceConfig {

    /** 分页插件：不写死 DbType，H2 与 MySQL 共用同一份代码 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 雪花 ID 生成器。
     *
     * <p>各服务必须使用<b>不同的 worker-id</b>，否则跨服务会生成重复 ID。
     * 当前分配：用户 1 / 商品 2 / 交易 3 / **履约 4**。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${duoduo.id.datacenter-id:1}") long datacenterId,
            @Value("${duoduo.id.worker-id:4}") long workerId) {
        return new SnowflakeIdGenerator(datacenterId, workerId);
    }
}
