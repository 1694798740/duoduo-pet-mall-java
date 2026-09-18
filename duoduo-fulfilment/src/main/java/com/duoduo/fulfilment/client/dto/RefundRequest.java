package com.duoduo.fulfilment.client.dto;

/**
 * 交易域「按裁定结果退款」的请求体（与 {@code com.duoduo.trade.dto.OrderDtos.RefundRequest} 对齐）。
 *
 * <p>只带一个原因：退多少、退给谁、钱从哪来，都是交易域从自己的订单与托管单里读出来的事实，
 * 调用方无从也不该指定金额 —— 否则「售后单说退 1 分、订单实际退 100 元」这种账目分歧
 * 就只是时间问题。
 */
public record RefundRequest(String reason) {
}
