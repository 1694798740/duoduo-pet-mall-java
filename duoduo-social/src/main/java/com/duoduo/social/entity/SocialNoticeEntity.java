package com.duoduo.social.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知中心 · 定向通知与全站公告（第四十七轮建表，第五十轮启用页面）。
 *
 * <p>一行 {@code userId = 收件人} 为定向通知；一行 {@code userId = 0} 为全站公告
 * （公共行，一行多读者）。两种通知的行上都没有已读列 —— 已读统一记在
 * {@link SocialNoticeReadEntity} 集合表（(noticeId, userId) 复合主键，撞键即已读），
 * 接口层把两种来源统一成同一个视图，前端无感。
 */
@TableName("t_social_notice")
public class SocialNoticeEntity {

    @TableId
    private Long id;

    /** 收件人；0 = 全站公告（公共行） */
    private Long userId;

    /** SYSTEM 系统消息 / ANNOUNCEMENT 全站公告 / FRIEND 好友消息 / MERCHANT 商家消息 / ORDER 订单交易 */
    private String category;

    private String title;
    private String content;

    /** 业务类型（ORDER_PLACED / ORDER_PAID / …），v1 种子公告为空 */
    private String bizType;

    /** 业务单号（订单号 / 售后单号 / 分享单；雪花 ID 字符串口径，T-39），v1 种子公告为空 */
    private String bizId;

    /** 点击落地页 path（小程序页面路径），可空 */
    private String jumpPage;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getJumpPage() {
        return jumpPage;
    }

    public void setJumpPage(String jumpPage) {
        this.jumpPage = jumpPage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
