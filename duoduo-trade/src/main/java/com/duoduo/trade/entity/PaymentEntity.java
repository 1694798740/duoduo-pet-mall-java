package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 支付单。
 *
 * <p>把支付单独建表而不是在订单上摞几个字段，理由：
 * <ul>
 *   <li>一个订单可能多次尝试支付（失败重试、换渠道），订单表放不下这段历史；</li>
 *   <li><b>幂等的落点</b>：{@code (channel, idempotent_key)} 唯一索引让「同一渠道同一幂等键的回调」
 *       只可能成功一次 —— 支付回调天然会重复投递（渠道重试、网络抖动），
 *       靠唯一索引兜底比「先查再插」可靠；</li>
 *   <li>对账时需要按渠道、按渠道流水号核对，独立成表更好查。</li>
 * </ul>
 */
@TableName("t_payment")
public class PaymentEntity {

    public static final String STATUS_INIT = "INIT";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId
    private Long id;
    private String payNo;
    private Long orderId;
    private Long userId;
    /** MOCK / ALIPAY / WECHAT —— 本阶段只实现 MOCK（模拟渠道） */
    private String channel;
    private Long amount;
    private String status;
    private String idempotentKey;
    private String channelTradeNo;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPayNo() { return payNo; }
    public void setPayNo(String payNo) { this.payNo = payNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotentKey() { return idempotentKey; }
    public void setIdempotentKey(String idempotentKey) { this.idempotentKey = idempotentKey; }
    public String getChannelTradeNo() { return channelTradeNo; }
    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
