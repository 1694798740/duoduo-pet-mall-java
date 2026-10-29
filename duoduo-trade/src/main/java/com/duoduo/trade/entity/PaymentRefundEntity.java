package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 重复收款自动退款台账（B-04）。
 *
 * <p>同一订单被第二张支付单多收的钱：检出时（{@code markDuplicateCharge}）在**同一事务**里
 * 落一条台账（INIT），由补偿调度经渠道退款接口自动退回买家（原路退回）。
 *
 * <p>为什么是独立台账而不是在支付单上加字段：
 * <ul>
 *   <li>退款是**一次独立的资金动作**，有自己的单号、渠道流水号、执行状态与失败原因，
 *       塞进支付单行会让「多收了一笔」与「退没退出去」两件事混在一行里说不清；</li>
 *   <li>执行可能重试多次（渠道抖动、暂不可用），失败原因会覆盖更新，
 *       这是一段执行历史，不是支付单的属性；</li>
 *   <li>对账与客服都以台账为准：「多收的钱去哪了」这一问，从
 *       {@code t_payment}（CLOSED + 重复支付原因）到 {@code t_payment_refund}（SUCCESS）是一条完整的证据链。</li>
 * </ul>
 *
 * <p>状态机刻意极简：{@code INIT → SUCCESS}，**只有一个成功终态**。
 * 渠道暂不可用、返回处理中、甚至渠道暂不支持退款，一律保持 INIT 由补偿调度重试 ——
 * 与 {@code RESTOCK_PENDING} 的补偿语义一致：把「暂时失败」固化成终态，
 * 等于宣布这笔多收的钱永远退不回去。
 */
@TableName("t_payment_refund")
public class PaymentRefundEntity {

    /** 待执行：检出后落库的初始状态，由补偿调度推向 SUCCESS */
    public static final String STATUS_INIT = "INIT";
    /** 渠道已受理并到终态（原路退回完成） */
    public static final String STATUS_SUCCESS = "SUCCESS";

    @TableId
    private Long id;
    private String refundNo;
    private String payNo;
    private Long orderId;
    private Long userId;
    private String channel;
    private String channelTradeNo;
    private Long amount;
    private String status;
    private String failReason;
    private String channelRefundNo;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getPayNo() { return payNo; }
    public void setPayNo(String payNo) { this.payNo = payNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannelTradeNo() { return channelTradeNo; }
    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getChannelRefundNo() { return channelRefundNo; }
    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
