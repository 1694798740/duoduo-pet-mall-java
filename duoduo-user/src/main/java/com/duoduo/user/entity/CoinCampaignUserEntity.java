package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 多多币活动每人累计（R-P1 第三轮）：每人上限的事实来源。
 *
 * <p>为什么单独一张表而不是从流水聚合：每人上限要在**发币前**判定（否则超发已成事实），
 * 而流水是发币后的结果。把「已获加成」物化在这里，判定与累加可以在一行上原子完成
 * （{@code awarded + extra <= per_user_cap} 写进 UPDATE 的 WHERE）。
 */
@TableName("t_coin_campaign_user")
public class CoinCampaignUserEntity {

    @TableId
    private Long id;
    private Long campaignId;
    private Long userId;
    /** 该用户在本活动已获加成币数 */
    private Long awarded;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAwarded() { return awarded; }
    public void setAwarded(Long awarded) { this.awarded = awarded; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
