package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 第三方商家租户（E9）。
 *
 * <p>平台自营<b>不在本表</b>：自营不是入驻来的，其租户标识保持既有口径
 * {@code seller_id = 0}（语义权威见 {@code duoduo-common} 的 SellerOwnership）。
 * 本表只承载"走入驻流程进来的商家"，状态机（DRAFT→SUBMITTED→REVIEWING→
 * APPROVED/REJECTED→SUSPENDED）的流转与校验在 E10 实现，本实体只承载数据形状。
 */
@TableName("t_merchant")
public class MerchantEntity {

    @TableId
    private Long id;
    /** 店铺名（审核通过即开店名；唯一） */
    private String name;
    /** 店主用户 id（唯一：一个用户至多开一家店，v1 口径） */
    private Long ownerUserId;
    /** DRAFT / SUBMITTED / REVIEWING / APPROVED / REJECTED / SUSPENDED（E10 状态机） */
    private String status;
    /** 租户隔离档位（P3 预留，当前恒 STANDARD） */
    private String isolationLevel;
    /** 最近一次驳回原因（结构化，E10） */
    private String rejectReason;
    private LocalDateTime appliedAt;
    private LocalDateTime reviewedAt;
    /** 最近一次审核人（运营 user id） */
    private Long reviewedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIsolationLevel() {
        return isolationLevel;
    }

    public void setIsolationLevel(String isolationLevel) {
        this.isolationLevel = isolationLevel;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
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
