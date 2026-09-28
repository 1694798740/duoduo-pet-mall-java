package com.duoduo.social.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知中心 · 公告已读集合（第四十七轮建表，第五十轮启用）。
 *
 * <p>公告是「一行多读者」（{@code t_social_notice.userId = 0}），无法在行上记录谁读了，
 * 用 (noticeId, userId) 复合主键的集合表记已读 —— 重复插入撞主键即幂等。
 */
@TableName("t_social_notice_read")
public class SocialNoticeReadEntity {

    @TableId
    private Long noticeId;

    private Long userId;

    private LocalDateTime readAt;

    public Long getNoticeId() {
        return noticeId;
    }

    public void setNoticeId(Long noticeId) {
        this.noticeId = noticeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }
}
