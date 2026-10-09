package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审核 SLA 事后抽查任务（E28，用户库侧）。
 *
 * <p>结构与商品库的同名表**完全一致**（由 {@code docs/sql/incremental/29-e28-audit-sla.sql}
 * 与全量脚本保证），差异只在 {@code bizType} 的取值：本库只写 {@code MERCHANT}，
 * 商品库只写 {@code SPU}，所以两边的数据天然不重叠。
 *
 * <p>为什么不共用一个库：服务边界是「每个服务只连自己的库」。为了几个字段引入一次
 * 跨服务调用，会把「抽不抽查」变成「取决于对方服务此刻是否健康」——
 * 抽查是内部治理动作，不该依赖外部可用性。
 */
@TableName("t_audit_sample")
public class AuditSampleEntity {

    @TableId
    private Long id;
    /** SPU(商品) / MERCHANT(入驻申请) —— 用户库只写 MERCHANT */
    private String bizType;
    private Long bizId;
    /** 归属店铺主体 user id */
    private Long sellerId;
    /** 抽样日期（UTC）。与 bizId 组成幂等键 */
    private LocalDate sampleDate;
    /** 抽中理由（AuditSlaPolicy.Reason 的名字） */
    private String reason;
    /** PENDING / PASSED / REJECTED */
    private String status;
    private String reviewNote;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public LocalDate getSampleDate() {
        return sampleDate;
    }

    public void setSampleDate(LocalDate sampleDate) {
        this.sampleDate = sampleDate;
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

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
