package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币账户（R-P1）：一个用户一行，余额以本行为唯一权威。
 *
 * <p>余额与「Σ 未过期批次剩余」互为镜像，靠对账任务校验；
 * 扣减用条件 UPDATE（{@code balance >= 待扣}）原子完成，
 * 不依赖应用层「先查再扣」—— 那在并发下必然超扣。
 * {@code version} 保留给不带余额条件的更新（如累计字段落库），配乐观锁防丢更新。
 */
@TableName("t_coin_account")
public class CoinAccountEntity {

    @TableId
    private Long id;
    private Long userId;
    /** 当前余额（币）= Σ 未过期批次剩余；允许为负（CLAWBACK 防套利） */
    private Long balance;
    private Long totalEarned;
    private Long totalSpent;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }
    public Long getTotalEarned() { return totalEarned; }
    public void setTotalEarned(Long totalEarned) { this.totalEarned = totalEarned; }
    public Long getTotalSpent() { return totalSpent; }
    public void setTotalSpent(Long totalSpent) { this.totalSpent = totalSpent; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
