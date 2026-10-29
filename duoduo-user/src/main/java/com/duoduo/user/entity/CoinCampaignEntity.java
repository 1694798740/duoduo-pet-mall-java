package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 多多币节日活动（R-P1 第三轮）：签到 / 购物返币倍率 + 总预算 + 每人上限。
 *
 * <p><b>两个维度正交</b>：{@code status} 是运营开关，活动是否「正在进行」由
 * start_at / end_at 与当前时间比较得出 —— 与秒杀活动的口径一致，
 * 避免「活动过期了还要手工置为结束」这种无意义的运维负担。
 *
 * <p><b>预算必须原子扣减</b>：{@code budget_used + extra <= budget_total} 写进 UPDATE 的 WHERE，
 * 并发加成也不可能超发；{@code budgetTotal = 0} 表示不限预算。
 * {@code perUserCap = 0} 表示不限每人上限。
 */
@TableName("t_coin_campaign")
public class CoinCampaignEntity {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId
    private Long id;
    private String code;
    private String name;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BigDecimal signinMultiplier;
    private BigDecimal earnMultiplier;
    private Long budgetTotal;
    private Long budgetUsed;
    private Long perUserCap;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public BigDecimal getSigninMultiplier() { return signinMultiplier; }
    public void setSigninMultiplier(BigDecimal signinMultiplier) { this.signinMultiplier = signinMultiplier; }
    public BigDecimal getEarnMultiplier() { return earnMultiplier; }
    public void setEarnMultiplier(BigDecimal earnMultiplier) { this.earnMultiplier = earnMultiplier; }
    public Long getBudgetTotal() { return budgetTotal; }
    public void setBudgetTotal(Long budgetTotal) { this.budgetTotal = budgetTotal; }
    public Long getBudgetUsed() { return budgetUsed; }
    public void setBudgetUsed(Long budgetUsed) { this.budgetUsed = budgetUsed; }
    public Long getPerUserCap() { return perUserCap; }
    public void setPerUserCap(Long perUserCap) { this.perUserCap = perUserCap; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
