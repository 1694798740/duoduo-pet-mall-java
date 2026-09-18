package com.duoduo.trade.dto;

import com.duoduo.trade.dto.SettlementDtos.SettlementView;
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

    /**
     * 订单事实（服务间只读视图）。
     *
     * <p>与 {@link OrderView} 的关键差别：<b>不含收货人信息</b>。
     * 履约域校验「这单能不能评价」只需要知道「归属谁、是否已完成、买了什么」；
     * 顺手把收货人姓名 / 电话 / 地址也返回，等于把一份隐私数据多复制进一个不需要它的服务 ——
     * 服务间接口应当按「调用方真正需要什么」裁剪字段，而不是图省事返回整份视图。
     */
    public record OrderFactView(
            String orderNo,
            Long userId,
            Long sellerId,
            String status,
            String statusLabel,
            String finishedAt,
            List<OrderItemView> items
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
            /** 资金托管信息（未支付时为 null）；买家详情页据此显示「资金托管中 / 已放款」 */
            SettlementView settlement,
            String createdAt,
            String expireAt,
            String paidAt,
            String shippedAt,
            String finishedAt,
            String closedAt,
            /** 退款时间（钱退回买家的时刻）；未退款为 null */
            String refundedAt,
            String closeReason,
            /**
             * 物流公司与单号（发货后才有值）。
             * 买家端与卖家端共用同一个 OrderView：买家要看「我的货到哪了」，
             * 卖家要看「我这单发的什么」——同一份事实的两个视角，不该各存一份。
             */
            String logisticsCompany,
            String logisticsNo
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

    /**
     * 支付请求（本阶段为模拟渠道，支付即时成功）。
     *
     * <p>{@code idempotentKey} 由客户端生成：真实支付渠道的回调会重复投递，
     * 同一 {@code (channel, idempotentKey)} 只允许成功一次。
     * 重复提交返回首次结果，{@code idempotentReplay=true} 可用于区分。
     */
    public record PayRequest(
            @NotBlank(message = "支付渠道不能为空")
            @Size(max = 20, message = "支付渠道过长") String channel,

            @NotBlank(message = "idempotentKey 不能为空，用于防止重复扣款")
            @Size(max = 64, message = "idempotentKey 过长") String idempotentKey
    ) {
    }

    /**
     * 支付结果。
     *
     * <p>{@code payStatus} 的取值：
     * {@code SUCCESS}（即时渠道已入账）/ {@code PENDING}（沙箱与真实渠道：已预下单，等用户扫码支付）。
     * {@code PENDING} 时前端用 {@code codeUrl} 渲染二维码、用 {@code expireAt} 做倒计时，
     * 并轮询 {@code GET /api/payments/{payNo}} 直到终态。
     */
    public record PayResult(
            String orderNo,
            String payNo,
            String channel,
            long amount,
            String payStatus,
            String orderStatus,
            String orderStatusLabel,
            boolean idempotentReplay,
            /** 二维码内容：即时渠道为 mock:// 串；沙箱为收银台地址；微信 Native 为 weixin:// 串 */
            String codeUrl,
            /** 支付单过期时间（ISO-8601 UTC），过期后二维码失效 */
            String expireAt
    ) {
    }

    /**
     * 超时关单 / 自动确认收货（补偿调度）的执行结果。
     * 运维排障与自测都靠它把「扫了多少、关了多少、放款多少」说清楚。
     */
    public record SweepResult(
            /** 本轮扫描到的超时待付款订单数 */
            int scanned,
            /** 成功关单的订单数 */
            int closed,
            /** 关单后成功释放冻结的条数 */
            int released,
            /** 释放失败、仍留在 FROZEN 台账待下次重试的条数 */
            int releaseFailed,
            /** 本轮因「发货满 N 天」自动确认收货的订单数 */
            int autoCompleted,
            /** 自动确认收货时成功放款给卖家的笔数 */
            int fundsReleased,
            /** 本轮成功入库回补（退款后的货回库）的条数 */
            int restocked,
            /** 入库回补失败、留在 RESTOCK_PENDING 待下轮重试的条数 */
            int restockFailed
    ) {
    }

    /**
     * 退款请求。
     *
     * <p>买家自助退款只允许「已付款且卖家未发货」的订单；
     * 已发货之后要走售后流程（由运营侧入口执行），因为货在买家手里，
     * 系统无从判断货有没有退回来 —— 这一点必须在接口层挡住，
     * 而不是指望前端只显示正确的按钮。
     */
    public record RefundRequest(
            @Size(max = 100, message = "退款原因过长") String reason
    ) {
    }

    /**
     * 退款结果。
     *
     * <p>{@code fundsRefunded} 表示资金是否真的从托管退回买家；
     * {@code restockPending} 表示货的回库是否还需要后台补偿 ——
     * 这两个刻意分开表达：「钱退了、货还没回库」是完全正常的一种中间状态，
     * 用一个布尔量糊在一起，前端就没法给出准确提示。
     */
    public record RefundResult(
            String orderNo,
            String orderStatus,
            String orderStatusLabel,
            long refundAmount,
            boolean fundsRefunded,
            String settlementStatus,
            String settlementStatusLabel,
            boolean restockPending,
            String refundedAt
    ) {
    }
}
