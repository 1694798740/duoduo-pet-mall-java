package com.duoduo.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 关注关系（第四十七轮）。
 *
 * <p>一行 = 「follower 关注了 followee」。<b>互相关注 = 两个方向各存在一行</b>，
 * 一条 COUNT 即可判定 —— 不引入「关系状态」列：状态列要双向同步，是 bug 温床。
 *
 * <p>无自增主键：复合主键 {@code (follower_id, followee_id)} 即唯一约束，
 * 重复关注撞键、按「已关注」幂等；取关 = 删行（删 0 行也是幂等）。
 * 因此本实体不声明 {@code @TableId}，全部操作走条件构造器。
 */
@TableName("t_social_follow")
public class SocialFollowEntity {

    private Long followerId;
    private Long followeeId;
    private LocalDateTime createdAt;

    public Long getFollowerId() {
        return followerId;
    }

    public void setFollowerId(Long followerId) {
        this.followerId = followerId;
    }

    public Long getFolloweeId() {
        return followeeId;
    }

    public void setFolloweeId(Long followeeId) {
        this.followeeId = followeeId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
