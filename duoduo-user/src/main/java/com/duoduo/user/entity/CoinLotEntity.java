package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币批次（R-P1）：每笔获得的币为一批，扣减按批次「先到期先扣」。
 *
 * <p>为什么必须有批次而不是只有一个总数：币有 12 个月有效期，
 * 「花掉的是哪一批、哪一批还剩多少、退款要退回哪一批」——这些没有批次就全答不了。
 * 退款退回原批次（原批次已过期则补发 30 天新批次）的规则，直接依赖扣币时的批次明细。
 */
@TableName("t_coin_lot")
public class CoinLotEntity {

    @TableId
    private Long id;
    private Long userId;
    /** 来源：ORDER_EARN / ORDER_REFUND / EXPIRE_COMPENSATE / ADJUST */
    private String bizType;
    private String bizNo;
    private Long amount;
    private Long remaining;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getRemaining() { return remaining; }
    public void setRemaining(Long remaining) { this.remaining = remaining; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
