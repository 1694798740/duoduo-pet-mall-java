package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.AfterSaleStatsCallDtos.TenantAfterSaleStats;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 履约域客户端：租户售后聚合（E24，供租户健康度面板）。
 *
 * <p>走 Nacos 服务发现 + 负载均衡，**不经网关** —— 与同项目里其余服务间调用一致：
 * 网关承担的是对外入口职责，服务间再绕一圈只是多一跳、多一次鉴权解析。
 * 内部凭据由 {@code InternalCallConfig} 统一注入（签名 + 时效 + 一次性 nonce），
 * 履约域的 {@code InternalTokenFilter} 会校验 {@code /api/aftersales/internal/} 前缀。
 *
 * <p>{@code contextId} 显式给：交易域已有多个指向 {@code duoduo-user} / {@code duoduo-fulfilment}
 * 的 Feign 客户端，缺省 contextId 会让同名 Bean 冲突（Spring Cloud OpenFeign 的既有约束，
 * 项目里 {@code userAddressClient} / {@code membershipClient} 都是这么写的）。
 */
@FeignClient(name = "duoduo-fulfilment", contextId = "tenantAfterSaleStatsClient",
        path = "/api/aftersales/internal")
public interface AfterSaleStatsClient {

    /**
     * 窗口内每个卖家的售后聚合。
     *
     * <p>参数用字符串而不是 {@code LocalDateTime}：Feign 对 Java 时间类型的编码
     * 依赖两端注册的转换器与格式约定，本项目已统一为「ISO-8601 字符串，UTC，不带偏移」，
     * 直接传字符串让两端行为完全确定（同类做法见 {@code ProductClient} 用逗号串代替 List）。
     *
     * @param from 起点（UTC，{@code yyyy-MM-ddTHH:mm:ss}，含）
     * @param to   终点（UTC，开区间）
     */
    @GetMapping("/tenant-stats")
    ApiResponse<TenantAfterSaleStats> tenantStats(@RequestParam("from") String from,
                                                  @RequestParam("to") String to);
}
