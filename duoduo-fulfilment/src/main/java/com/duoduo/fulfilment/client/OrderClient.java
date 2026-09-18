package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.OrderFact;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 交易域客户端：取订单事实（走 Nacos 服务发现 + 负载均衡，<b>不经网关</b>）。
 *
 * <p>为什么不走网关：网关承担「对外入口」职责（鉴权、限流、CORS）。
 * 服务间调用再绕一圈网关会平白多一跳，且网关故障会放大成全站故障。
 * 内部调用由 {@code X-Internal-Token} 保护（见 {@code InternalCallConfig}），
 * 交易域侧由它自己的 {@code InternalTokenFilter} 校验。
 */
@FeignClient(name = "duoduo-trade", path = "/api/orders")
public interface OrderClient {

    /**
     * 按订单号取订单事实；订单不存在由交易域返回 404。
     *
     * <p>返回的 {@code userId} 是**事实**，归属判断在本服务完成：拿它与登录态比对，
     * 不一致就 403。绝不把「订单属于谁」的判断权交给调用方传参。
     */
    @GetMapping("/internal/{orderNo}/fact")
    ApiResponse<OrderFact> fact(@PathVariable("orderNo") String orderNo);
}
