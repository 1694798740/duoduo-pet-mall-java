package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分享话题标签（第五十六轮）。
 *
 * <p>{@code name} 唯一 —— 并发建同名话题靠唯一键兜住（应用层「先查再插」会双写）。
 * 服务层遇到撞键时**回查已有行并复用**，不报错：两个用户同时首发同一个话题是正常行为，
 * 不该有人失败。
 *
 * <p>{@code shareCount} 是冗余计数（事实源是 {@code t_share_topic_rel}），
 * 热门话题榜与聚合页头部直接读它。
 */
@TableName("t_share_topic")
public class ShareTopicEntity {

    @TableId
    private Long id;

    /** 话题名（不含 # 号） */
    private String name;

    private Integer shareCount;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getShareCount() {
        return shareCount;
    }

    public void setShareCount(Integer shareCount) {
        this.shareCount = shareCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
