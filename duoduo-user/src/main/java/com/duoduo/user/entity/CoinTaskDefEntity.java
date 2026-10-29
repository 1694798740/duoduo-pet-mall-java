package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币任务定义（R-P1 第三轮 · 站内任务）。
 *
 * <p>任务口径分两类：
 * <ul>
 *   <li>{@link #TASK_TYPE_DAILY} —— 每日刷新，进度按 Asia/Shanghai 自然日滚动；</li>
 *   <li>{@link #TASK_TYPE_NEWCOMER} —— 新人一次性，进度落在哨兵日 1970-01-01 上，永久有效。</li>
 * </ul>
 *
 * <p>{@code rewardCoins} 是「达成一次发多少币」，{@code dailyCap} 是进度上限
 * （0 表示按 {@code targetCount} 封顶）。种子里的浏览任务 target=3、reward=6，
 * 正是「每个 +2、日上限 6」的等价写法 —— 上限写进任务定义，服务层不再硬编码。
 */
@TableName("t_coin_task_def")
public class CoinTaskDefEntity {

    public static final String TASK_TYPE_DAILY = "DAILY";
    public static final String TASK_TYPE_NEWCOMER = "NEWCOMER";

    @TableId
    private Long id;
    private String code;
    private String name;
    private String taskType;
    private String actionType;
    private Integer targetCount;
    private Integer rewardCoins;
    private Integer dailyCap;
    private Boolean enabled;
    private Integer sortNo;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public Integer getTargetCount() { return targetCount; }
    public void setTargetCount(Integer targetCount) { this.targetCount = targetCount; }
    public Integer getRewardCoins() { return rewardCoins; }
    public void setRewardCoins(Integer rewardCoins) { this.rewardCoins = rewardCoins; }
    public Integer getDailyCap() { return dailyCap; }
    public void setDailyCap(Integer dailyCap) { this.dailyCap = dailyCap; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
