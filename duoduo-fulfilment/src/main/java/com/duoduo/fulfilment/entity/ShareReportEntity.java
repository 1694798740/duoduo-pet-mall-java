package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分享 / 评论举报（第五十六轮）。
 *
 * <p>{@code targetType} 把 SHARE 与 COMMENT 两种举报对象合成一张表 ——
 * 处理流程完全一致（看内容 → 判定 → 成立则下架），拆两张表等于把同一套运营逻辑写两遍。
 *
 * <p><b>去重靠唯一键</b>：{@code (target_type, target_id, reporter_id)} 唯一，
 * 应用层「先查有没有、没有就插」在并发下会双写（R54 去重表的同一教训）。
 */
@TableName("t_share_report")
public class ShareReportEntity {

    @TableId
    private Long id;

    /** SHARE | COMMENT */
    private String targetType;

    private Long targetId;

    private Long reporterId;

    private String reason;

    /** PENDING | ACCEPTED（成立，内容已下架）| REJECTED（不成立） */
    private String status;

    private Long handledBy;

    private LocalDateTime handledAt;

    private String handleNote;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public void setReporterId(Long reporterId) {
        this.reporterId = reporterId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public void setHandledBy(Long handledBy) {
        this.handledBy = handledBy;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public String getHandleNote() {
        return handleNote;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
