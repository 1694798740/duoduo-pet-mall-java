package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币冻结单（R-P1）：下单冻结所用币，一单一冻结（{@code uk_coin_freeze_order}）。
 *
 * <p>状态机：FROZEN → DEDUCTED（支付成功，终态）/ FROZEN → RELEASED（取消或超时关单，
 * 余额退回）。冻结在**下单时**就真实扣减余额（先到期先扣批次），
 * 支付成功只是把冻结单推进到 DEDUCTED，不再动余额 ——
 * 这样「币已被这单占用」从下单那一刻起就是事实，不会出现
 * 「同一笔币被两张订单同时算作可用余额」的窗口。
 */
@TableName("t_coin_freeze")
public class CoinFreezeEntity {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_DEDUCTED = "DEDUCTED";
    public static final String STATUS_RELEASED = "RELEASED";

    @TableId
    private Long id;
    private Long userId;
    private String orderNo;
    private Long coins;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getCoins() { return coins; }
    public void setCoins(Long coins) { this.coins = coins; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
