package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 入驻资质材料（E10）。每类一份、重传覆盖（uk_merchant_type）。
 */
@TableName("t_merchant_material")
public class MerchantMaterialEntity {

    @TableId
    private Long id;
    private Long merchantId;
    /** MerchantMaterialType.name() */
    private String materialType;
    private String fileUrl;
    /**
     * 证件有效期至（E15，UTC，含当日有效）。
     *
     * <p><b>NULL = 长期有效</b>（存量数据与「经营承诺书」这类本就没有到期日的材料）——
     * 与「已过期」不是一回事。判定口径见 {@code MerchantOnboardingService}：
     * 只有非 NULL 的到期日才参与「店铺最早到期日」的取值。
     */
    private LocalDateTime expireAt;
    private String originalName;
    private Long uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMaterialType() {
        return materialType;
    }

    public void setMaterialType(String materialType) {
        this.materialType = materialType;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
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
