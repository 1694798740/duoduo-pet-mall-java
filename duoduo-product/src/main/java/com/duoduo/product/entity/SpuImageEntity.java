package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品图片（A-11）。
 *
 * <p>一行一张图：类型、排序、审核态。与 {@code t_spu.main_image} /
 * {@code t_spu.images}（CSV）双写，CSV 继续服务现有读路径；
 * 本表是排序与单图审核的权威来源。
 *
 * <p>审核态跟随商品审核态，不单独维护「部分图片被拒」——
 * 商品层不可见的中间状态只会让运营和商家对不上话。
 */
@TableName("t_spu_image")
public class SpuImageEntity {

    public static final String TYPE_MAIN = "MAIN";
    public static final String TYPE_GALLERY = "GALLERY";

    public static final String AUDIT_PENDING = "PENDING";
    public static final String AUDIT_APPROVED = "APPROVED";
    public static final String AUDIT_REJECTED = "REJECTED";

    @TableId
    private Long id;
    private Long spuId;
    private String url;
    private String imageType;
    /** 同类型内升序；主图固定 0，副图从 1 连续编号 */
    private Integer sortNo;
    private String auditStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getImageType() {
        return imageType;
    }

    public void setImageType(String imageType) {
        this.imageType = imageType;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus) {
        this.auditStatus = auditStatus;
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
