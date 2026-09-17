package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单状态流转日志。
 *
 * <p>每一次状态变化都追加一行，**只增不改**。它的价值在出问题时才体现：
 * 「这单为什么被关了」「是谁在什么时候取消的」都能直接查出来，
 * 而不是只能靠猜。对账、客诉、风控都依赖这份流水。
 */
@TableName("t_order_status_log")
public class OrderStatusLogEntity {

    @TableId
    private Long id;
    private Long orderId;
    /** 创建订单时为 null */
    private String fromStatus;
    private String toStatus;
    /** USER / SELLER / SYSTEM */
    private String operatorType;
    private Long operatorId;
    private String reason;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getOperatorType() { return operatorType; }
    public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
