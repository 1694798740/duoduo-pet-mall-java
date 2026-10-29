package com.duoduo.ai.client;

import com.duoduo.ai.client.dto.ButlerOrderDtos.OrderPage;
import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 交易域客户端（AI 全能管家 · 阶段二）：只读「我的订单」。
 *
 * <p>与同域的 {@link CartClient} 并存：Feign 要求每个客户端有唯一的 {@code contextId}，
 * 否则同名服务会抢同一个 bean 名导致启动失败。
 *
 * <p>下单、支付、取消、退款一概不接：AI 只负责把用户带到正确页面（navigate_to_page），
 * 真正的交易动作必须在交易域自己的页面里、由用户亲手完成。
 */
@FeignClient(name = "duoduo-trade", contextId = "butlerOrderClient",
        configuration = AiFeignConfig.class)
public interface OrderClient {

    /**
     * 我的订单列表。
     *
     * @param status  PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CLOSED / REFUNDED；null = 全部
     * @param keyword 订单号或商品标题（服务端包含匹配）
     */
    @GetMapping("/api/orders")
    ApiResponse<OrderPage> list(@RequestHeader("X-User-Id") Long userId,
                                @RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "page", defaultValue = "1") int page,
                                @RequestParam(value = "pageSize", defaultValue = "20") int pageSize);
}
