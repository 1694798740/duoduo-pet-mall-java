package com.duoduo.social.support.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 客服消息（用户 ↔ 商家/自营客服）。
 *
 * <p>与私信表刻意不共用：私信有「非互关限一条」「互关才能续聊」那套社交规则，
 * 客服没有也不该有。放在同一张表里，任何一次查询忘了加排除条件，
 * 客服会话就会重新出现在私信列表 —— 这类回归很难在测试里被穷举。
 *
 * <p>{@code bizType=PRODUCT} 时 {@code bizId} 是商品 spuId，用于在会话里渲染商品卡；
 * 卡片标题/图片不落库，读取时去商品域现取（与私信、分享圈同一纪律）。
 */
@TableName("t_support_message")
public class SupportMessageEntity {

    @TableId
    private Long id;

    private Long sessionId;

    /** 发送方：用户本人 / 商家账号（自营场景即官方客服账号） */
    private Long senderId;
    /** USER / SELLER / SYSTEM */
    private String senderRole;

    private String content;
    /** TEXT / PRODUCT */
    private String bizType;
    private Long bizId;

    /** 媒体地址（IMAGE / VIDEO / EMOJI）：服务端上传接口生成，不接受任意外链 */
    private String mediaUrl;
    /** 视频封面（可为空，前端用 <video preload=metadata> 兜底） */
    private String thumbUrl;
    private Integer width;
    private Integer height;
    private Integer durationMs;
    /** 卡片快照（商品/订单）：商品下架后仍有内容可展示 */
    private String metaJson;

    /** 接收方已读时间；NULL = 未读 */
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getSenderRole() {
        return senderRole;
    }

    public void setSenderRole(String senderRole) {
        this.senderRole = senderRole;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
