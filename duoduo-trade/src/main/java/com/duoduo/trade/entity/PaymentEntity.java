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

    /** 已创建、尚未向渠道预下单 */
    public static final String STATUS_INIT = "INIT";
    /** 已预下单，等待用户支付（沙箱与真实渠道的常态） */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    /** 超时未支付，由补偿调度置入 */
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 订单被取消 / 关闭，支付单随之作废 */
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
    /** 渠道返回的**二维码内容**（沙箱为收银台地址，微信 Native 为 weixin:// 串），由前端渲染成二维码 */
    private String codeUrl;
    private String failReason;
    /** 支付单过期时间；超时未支付由补偿调度置为 EXPIRED */
    private LocalDateTime expireAt;
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
    public String getCodeUrl() { return codeUrl; }
    public void setCodeUrl(String codeUrl) { this.codeUrl = codeUrl; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
