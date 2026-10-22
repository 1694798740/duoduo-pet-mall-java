package com.duoduo.social.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 私信 / 卡片消息（第四十七轮）。
 *
 * <p>会话键 {@code conversationId = "lo:hi"}（双方 userId 数值小的在前）：
 * 双向聊天只需这一个索引就能按会话拉取，不必按 (sender, receiver) UNION 两次查询。
 *
 * <p>{@code senderRole} 是发送时点的角色快照 —— 通知中心据此把私信分流到
 * 「好友消息」或「商家消息」，展示时不必反向查用户域。
 *
 * <p>卡片消息（{@code bizType != TEXT}）的展示字段**不在本表**：
 * 查询时按 {@code bizType + bizId} 去事实源批量现取（商品卡 / 分享卡 / 个人卡），
 * 表里只落服务端拼好的 {@code content} 摘要 —— 与分享圈「快照以服务端事实为准」同一纪律。
 */
@TableName("t_social_message")
public class SocialMessageEntity {

    @TableId
    private Long id;

    private String conversationId;
    private Long senderId;
    private Long receiverId;
    private String senderRole;
    private String bizType;
    private Long bizId;
    private String content;
    private String mediaUrl;
    private String thumbUrl;
    private Integer width;
    private Integer height;
    private Integer durationMs;
    private String metaJson;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getSenderRole() {
        return senderRole;
    }

    public void setSenderRole(String senderRole) {
        this.senderRole = senderRole;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    public void setThumbUrl(String thumbUrl) {
        this.thumbUrl = thumbUrl;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getMetaJson() {
        return metaJson;
    }

    public void setMetaJson(String metaJson) {
        this.metaJson = metaJson;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
