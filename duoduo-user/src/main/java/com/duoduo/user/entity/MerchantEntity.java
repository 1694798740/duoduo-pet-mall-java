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
    /**
     * 店铺资质最早到期日（E15，派生冗余）。
     *
     * <p>值 = 本店铺所有带有效期的材料里最早的那个 {@code expire_at}，由
     * {@code MerchantOnboardingService#refreshLicenseExpireAt} 在「保存材料 / 提交 / 续期」
     * 时重算写入。<b>NULL = 无到期概念</b>（一份带有效期的材料都没有，或全是长期有效）——
     * 与「已过期」是完全不同的两件事：前者永不提醒、永不自动停业。
     */
    private LocalDateTime licenseExpireAt;
    /**
     * 已提醒到的档位（E15）：{@code T30} / {@code T7} / {@code EXPIRED}，NULL = 本周期未提醒。
     *
     * <p>提醒规则是「新档位比已记档位更深才发」——记档位而不是布尔值，
     * 才能做到「30 天提醒一次、7 天再提醒一次，同一档位反复扫到不重复打扰」。
     * 续期通过时清空，进入下一个生命周期。
     */
    private String expireNotifyStage;
    /** 租户隔离档位（P3 预留，当前恒 STANDARD） */
    private String isolationLevel;
    /** 最近一次驳回原因（结构化，E10） */
    private String rejectReason;
    /** 停业原因（E15，SUSPENDED 时有值）；与申请驳回原因 rejectReason 分列 */
    private String suspendedReason;
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

    public LocalDateTime getLicenseExpireAt() {
        return licenseExpireAt;
    }

    public void setLicenseExpireAt(LocalDateTime licenseExpireAt) {
        this.licenseExpireAt = licenseExpireAt;
    }

    public String getExpireNotifyStage() {
        return expireNotifyStage;
    }

    public void setExpireNotifyStage(String expireNotifyStage) {
        this.expireNotifyStage = expireNotifyStage;
    }

    public String getSuspendedReason() {
        return suspendedReason;
    }

    public void setSuspendedReason(String suspendedReason) {
        this.suspendedReason = suspendedReason;
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
