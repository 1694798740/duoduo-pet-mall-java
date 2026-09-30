package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * C2C 闲置商品（个人卖家转卖）。
 *
 * <p>与 {@link SpuEntity} 分开建表的原因：两者生命周期与规则完全不同 ——
 * 闲置是「一物一价、卖完即止」，需要审核、属地（同城自提）、成色；
 * 自营 SPU 是「标准品、有 SKU 与库存」。硬塞进一张表会让一半字段永远为 NULL。
 *
 * <p>{@code auditStatus} 默认 PENDING：发布后必须经人工审核才对外可见，
 * 这是平台对活体与药品类目合规要求的最低保障。
 */
@TableName("t_listing")
public class ListingEntity {

    @TableId
    private Long id;
    private Long sellerId;
    private String title;
    private String description;
    private Long categoryId;
    private Long price;
    private Long originalPrice;
    /** NEW / LIKE_NEW / GOOD / FAIR */
    private String conditionLevel;
    private String city;
    private String images;
    private String species;
    private String breed;
    /** ON_SALE / SOLD / OFF_SHELF */
    private String status;
    /** PENDING / APPROVED / REJECTED */
    private String auditStatus;
    /** 驳回原因（REJECTED 时必填）；审核结论要可追溯，不能只留一个状态码 */
    private String auditReason;
    /** 审核时间 */
    private LocalDateTime auditAt;
    /** 浏览次数：页面每被打开或刷新一次 +1（同一人可累加） */
    private Integer viewCount;
    /** 想要人数：按咨询行为去重统计（同一买家对同一闲置只计 1 次），与 viewCount 是两个口径 */
    private Integer wantCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * 软删时间（第五十八轮 · B-14）：非 NULL = 已删除。
     *
     * <p>为什么不用物理删：闲置成交后会与订单、会话、举报产生引用关系，
     * 物理删会让别人的页面出现「查无此物」；审核与举报留痕也需要「这条内容曾经存在」。
     * 删除只写时间不删行，所有读路径一律带 {@code deleted_at IS NULL}。
     */
    private LocalDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public Long getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(Long originalPrice) {
        this.originalPrice = originalPrice;
    }

    public String getConditionLevel() {
        return conditionLevel;
    }

    public void setConditionLevel(String conditionLevel) {
        this.conditionLevel = conditionLevel;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus) {
        this.auditStatus = auditStatus;
    }

    public String getAuditReason() {
        return auditReason;
    }

    public void setAuditReason(String auditReason) {
        this.auditReason = auditReason;
    }

    public LocalDateTime getAuditAt() {
        return auditAt;
    }

    public void setAuditAt(LocalDateTime auditAt) {
        this.auditAt = auditAt;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public Integer getWantCount() {
        return wantCount;
    }

    public void setWantCount(Integer wantCount) {
        this.wantCount = wantCount;
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

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
