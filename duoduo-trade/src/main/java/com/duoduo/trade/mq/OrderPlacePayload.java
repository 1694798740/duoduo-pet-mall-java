package com.duoduo.trade.mq;

import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;

/**
 * 削峰下单消息体（A11）。
 *
 * <p>userId 必须随消息走：消费线程没有网关注入的 {@code X-User-*}，
 * 不带身份的消息到了消费者手里就是一句「不知道谁要下单」的空话。
 *
 * <p>request 原样序列化（含 idempotentKey）：消费者执行的是与同步端点<b>同一份</b>
 * {@code placeOrder(userId, request)} —— 幂等、校验、状态机全靠这一份代码，
 * 消息体里不造第二套字段口径。
 */
public record OrderPlacePayload(Long userId, PlaceOrderRequest request) {
}
