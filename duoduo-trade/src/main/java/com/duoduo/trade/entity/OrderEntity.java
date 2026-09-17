package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单（已按卖家拆单：一个订单只属于一个卖家）。
 *
 * <p>金额字段全部为整数「分」，时间全部 UTC。
 *
 * <p>{@code idempotentKey} 是**下单幂等键**：同一用户用同一个键重复提交，
 * 只会产生一个订单（靠唯一索引 {@code uk_order_user_idem} 兜底），
 * 这是「用户手抖点了两次提交」与「网络超时后客户端重试」的标准解法，
 * 比在前端做按钮禁用可靠得多（前端禁用挡不住重试与多端并发）。
 */
@TableName("t_order")
public class OrderEntity {

    @TableId
    private Long id;
    private String orderNo;
    private Long userId;
    private Long sellerId;
    /** NORMAL = 自营商品订单；C2C = 闲置担保交易订单 */
    private String orderType;
    /** PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / REFUNDED / CLOSED（见 OrderStatus） */
    private String status;
    private Long totalAmount;
    private Long freightAmount;
    private Long discountAmount;
    private Long payAmount;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private String remark;
    private String idempotentKey;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime closedAt;
    /**
     * 退款时间（钱退回买家的时刻）。与 closedAt 刻意分开：
     * 退款不代表「订单被关闭」，资金动作必须有独立时间戳，
     * 否则对账时无法区分「没付过款」和「付了又退了」。
     */
    private LocalDateTime refundedAt;
    private String closeReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Long totalAmount) { this.totalAmount = totalAmount; }
    public Long getFreightAmount() { return freightAmount; }
    public void setFreightAmount(Long freightAmount) { this.freightAmount = freightAmount; }
    public Long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(Long discountAmount) { this.discountAmount = discountAmount; }
    public Long getPayAmount() { return payAmount; }
    public void setPayAmount(Long payAmount) { this.payAmount = payAmount; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getIdempotentKey() { return idempotentKey; }
    public void setIdempotentKey(String idempotentKey) { this.idempotentKey = idempotentKey; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
