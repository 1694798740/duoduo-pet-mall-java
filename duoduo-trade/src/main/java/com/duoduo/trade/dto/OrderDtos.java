package com.duoduo.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 交易域 · 订单 DTO 集合。
 *
 * <p>约定：金额一律整数「分」；时间一律 ISO8601 UTC 字符串（如 {@code 2026-09-17T05:00:00Z}）。
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * 下单请求。
     *
     * <p><b>没有 userId 字段</b> —— 身份只从网关透传的 {@code X-User-*} 取。
     *
     * <p>{@code idempotentKey} 由客户端生成（建议 UUID），用于「重复提交只下一个单」：
     * 同一用户用同一个键再次提交会直接返回**首次**产生的订单，而不是再下一单。
     */
    public record PlaceOrderRequest(
            @NotBlank(message = "收货人不能为空")
            @Size(max = 50, message = "收货人姓名过长") String receiverName,

            @NotBlank(message = "收货手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货手机号格式不正确") String receiverPhone,

            @NotBlank(message = "收货地址不能为空")
            @Size(max = 300, message = "收货地址过长") String receiverAddress,

            @Size(max = 200, message = "备注过长") String remark,

            @NotBlank(message = "idempotentKey 不能为空，用于防止重复下单")
            @Size(max = 64, message = "idempotentKey 过长") String idempotentKey
    ) {
    }

    /** 订单明细（下单时的快照，商品后续改价改名都不影响这里） */
    public record OrderItemView(
            Long skuId,
            Long spuId,
            String title,
            String specText,
            String mainImage,
            long price,
            int quantity,
            long amount
    ) {
    }

    public record OrderView(
            String orderNo,
            Long sellerId,
            String orderType,
            String status,
            /** 状态中文文案，由服务端给出，前端不必维护映射表 */
            String statusLabel,
            long totalAmount,
            long freightAmount,
            long discountAmount,
            long payAmount,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String remark,
            List<OrderItemView> items,
            String createdAt,
            String expireAt,
            String closedAt,
            String closeReason
    ) {
    }

    /**
     * 下单结果。
     * {@code idempotentReplay=true} 表示本次请求命中了幂等键，返回的是已有订单（没有新建）。
     */
    public record PlaceOrderResult(
            List<OrderView> orders,
            int orderCount,
            long totalPayAmount,
            boolean idempotentReplay
    ) {
    }

    /** 取消订单请求 */
    public record CancelRequest(
            @Size(max = 100, message = "取消原因过长") String reason
    ) {
    }
}
