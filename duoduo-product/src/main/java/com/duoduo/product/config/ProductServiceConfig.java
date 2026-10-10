package com.duoduo.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.tenant.TenantLineInterceptors;
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
        // E20 · 租户条件自动追加（商品域的三张租户表）。
        //
        // ★ 必须在分页插件**之前** add —— MP 的官方顺序是「多租户 → 分页」。
        //   顺序反了不会报错，只会让分页的 count 语句漏掉租户条件，
        //   表现是「列表被过滤了、总数没有被过滤」，一个纯数字的错误。
        //
        // 表名必须逐个写出来：`TenantLineInterceptors` 不做自动发现，
        // 拼错的表名会在**启动时**直接抛异常（而不是运行期静默不过滤）。
        TenantLineInterceptors.forTables("t_spu", "t_listing", "t_audit_sample")
                .forEach(interceptor::addInnerInterceptor);

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
