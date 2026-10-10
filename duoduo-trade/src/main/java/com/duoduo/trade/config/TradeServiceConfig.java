package com.duoduo.trade.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.tenant.TenantLineInterceptors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 交易服务基础设施 Bean。
 */
@Configuration
public class TradeServiceConfig {

    /**
     * 分页插件。不写死 DbType，由 MyBatis-Plus 依据连接自动识别，
     * 使本地 H2 与生产 MySQL 共用同一份代码。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // E20 · 租户条件自动追加（交易域的三张租户表）。★ 必须在分页插件之前 add
        // （多租户 → 分页；顺序反了会让分页 count 语句漏掉租户条件，列表与总数不一致且不报错）。
        TenantLineInterceptors.forTables("t_order", "t_seller_debt", "t_settlement")
                .forEach(interceptor::addInnerInterceptor);
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 雪花 ID 生成器。
     *
     * <p>各服务必须使用<b>不同的 worker-id</b>，否则跨服务会生成重复 ID。
     * 当前分配：用户 1 / 商品 2 / 交易 3。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${duoduo.id.datacenter-id:1}") long datacenterId,
            @Value("${duoduo.id.worker-id:3}") long workerId) {
        return new SnowflakeIdGenerator(datacenterId, workerId);
    }
}
