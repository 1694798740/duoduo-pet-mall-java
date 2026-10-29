package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币流水（R-P1）：唯一权威账本。
 *
 * <p>幂等的关键：{@code uk_coin_ledger_biz (user_id, biz_type, biz_no, direction)} 唯一索引。
 * 所有发币 / 扣币动作先撞这个键 —— 撞上说明这笔已经记过账，直接按已处理返回，
 * 支付回调、退款回调、定时任务重试都不会重复发币或扣币。
 * {@code amount} 一律为正；ADJUST（人工调整）允许负数表示调减，
 * 调整方向由 biz_type=ADMIN_ADJUST + 金额符号共同表达。
 */
@TableName("t_coin_ledger")
public class CoinLedgerEntity {

    public static final String D_EARN = "EARN";
    public static final String D_SPEND = "SPEND";
    public static final String D_REFUND = "REFUND";
    public static final String D_EXPIRE = "EXPIRE";
    public static final String D_CLAWBACK = "CLAWBACK";
    public static final String D_ADJUST = "ADJUST";

    @TableId
    private Long id;
    private Long userId;
    private String direction;
    private Long amount;
    private Long balanceAfter;
    private String bizType;
    private String bizNo;
    private String remark;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
