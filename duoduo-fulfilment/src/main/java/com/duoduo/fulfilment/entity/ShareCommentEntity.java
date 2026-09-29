package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分享评论（第五十六轮）。
 *
 * <p><b>二级结构</b>：{@code parentCommentId} 为 NULL = 一级评论；非 NULL = 对某条
 * 一级评论的回复。刻意只做二级 —— 二级以上要处理「回复谁的回复」的折叠与 @ 链路，
 * 属热度榜那一类复杂度，v1 不做（与设计稿的范围控制一致）。
 *
 * <p><b>计数冗余</b>：{@code likeCount} 是冗余列，事实源是 {@code t_share_comment_like}；
 * 信息流与评论区都是高频读路径，不逐行 COUNT 子表。
 *
 * <p><b>软删</b>：{@code deletedAt} 供**运营处理举报后下架评论**使用（不物理删，
 * 既留痕也保住回复链的完整性）。用户侧无法删除自己的评论（本轮未开放）。
 */
@TableName("t_share_comment")
public class ShareCommentEntity {

    @TableId
    private Long id;

    private Long shareId;

    private Long userId;

    /** NULL = 一级评论；非 NULL = 对某条一级评论的回复 */
    private Long parentCommentId;

    private String content;

    /** 点赞数（冗余列，事实源 t_share_comment_like） */
    private Integer likeCount;

    /** 软删时间；非 NULL = 已下架（运营处理举报用） */
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShareId() {
        return shareId;
    }

    public void setShareId(Long shareId) {
        this.shareId = shareId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(Long parentCommentId) {
        this.parentCommentId = parentCommentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
