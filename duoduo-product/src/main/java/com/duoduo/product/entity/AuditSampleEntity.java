package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审核 SLA 事后抽查任务（E28）。
 *
 * <p>抽查是「默认放行 + 事后复核」的**事后那一半**：商品已经被自动放行、正在前台卖，
 * 复核任务只是把它标记出来给运营看一眼，<b>不改变前台可见性</b> ——
 * 这就是为什么它必须是独立的一张表，而不是 t_spu 上的一个标志位：
 * 「正在被卖」与「正在被复核」是两件同时成立的事，压进一行会丢掉其中一个。
 *
 * <p>{@code uk_biz_date}（bizType + bizId + sampleDate）是幂等的来源：
 * 调度重跑、手动触发、多实例兜底都不会给同一件商品在同一天建出第二条任务。
 */
@TableName("t_audit_sample")
public class AuditSampleEntity {

    @TableId
    private Long id;
    /** SPU(商品) / MERCHANT(入驻申请) —— 商品库只写 SPU */
    private String bizType;
    private Long bizId;
    /** 归属店铺主体 user id（0 = 平台自营，不应被抽中） */
    private Long sellerId;
    /** 抽样日期（UTC）。与 bizId 组成幂等键 —— 用 DATE 而不是时间戳，否则重跑就会建出新行 */
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
