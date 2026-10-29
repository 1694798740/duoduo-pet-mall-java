package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多多币任务进度（R-P1 第三轮）：一人一任务一日一行（{@code uk_coin_task_progress}）。
 *
 * <p>{@code rewarded} 是发奖闸门：0 → 1 用条件 UPDATE 推进，
 * 并发下只可能有一个线程推进成功 —— 于是「同一任务每天只发一次」成为索引级事实，
 * 不依赖应用层先查后写。{@code rewardCoins} 记实际发放数（活动加成时为最终值）。
 */
@TableName("t_coin_task_progress")
public class CoinTaskProgressEntity {

    @TableId
    private Long id;
    private Long userId;
    private String taskCode;
    /** 业务日：日常任务为 Asia/Shanghai 今天；新人任务固定 {@code 1970-01-01} */
    private LocalDate bizDate;
    private Integer progress;
    private Boolean rewarded;
    private Integer rewardCoins;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public Boolean getRewarded() { return rewarded; }
    public void setRewarded(Boolean rewarded) { this.rewarded = rewarded; }
    public Integer getRewardCoins() { return rewardCoins; }
    public void setRewardCoins(Integer rewardCoins) { this.rewardCoins = rewardCoins; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
