package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SPU（Standard Product Unit）—— 标准化商品单元，代表「一个商品」。
 *
 * <p>{@code minPrice / maxPrice} 是 SKU 价格的冗余副本，目的是让列表页与价格排序
 * 只查一张表。冗余字段的一致性由写入方（商品管理后台）在同一事务内保证。
 */
@TableName("t_spu")
public class SpuEntity {

    @TableId
    private Long id;
    private String title;
    private String subtitle;
    private Long categoryId;
    private Long brandId;
    /** self=自营 / merchant=第三方商家 */
    private String supplyType;
    private String mainImage;
    /** 逗号分隔的图片列表 */
    private String images;
    private String detail;
    /** ON_SALE / OFF_SHELF */
    private String status;
    /**
     * 审核维度（E14）：PENDING / APPROVED / REJECTED。
     *
     * <p>与 {@link #status} <b>正交</b>：status 是交易维度（在售 / 下架，卖家可控），
     * 本字段是合规维度（平台判定）。公开可见性要求<b>两者同时满足</b>
     * （{@code status=ON_SALE 且 audit_status=APPROVED}），
     * 与 C2C 闲置（t_listing）同一套口径 —— 全站的商品审核只有一种解释。
     *
     * <p>平台自营商品（{@code supplyType='self'}）恒为 APPROVED（免审）；
     * 第三方商家新建或内容发生重大变更时由服务端显式落 PENDING。
     */
    private String auditStatus;
    /** 最近一次驳回原因（仅 REJECTED 有值；通过时清空） */
    private String auditReason;
    private LocalDateTime auditAt;
    /** 最近一次审核人（运营 / 管理员 user id），留痕 */
    private Long auditBy;
    private Integer salesCount;
    private BigDecimal rating;
    private Integer ratingCount;
    private Long minPrice;
    private Long maxPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 卖家归属（t_user.id；0 = 平台自营）。
     *
     * <p>为什么放在 SPU 而不是 SKU：库存与价格在 SKU，但「这货是谁的」是商品级事实 ——
     * 同一商品的 SKU 不会属于两个卖家（那是平台撮合的 C2C，走 t_listing）。
     * 下单拆单时交易服务从这里读 sellerId，商家端按它过滤「我的商品」。
     * 与 t_order.seller_id 是同一事实的两份记录，改动必须两边同步（见交易侧夹具）。
     */
    private Long sellerId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public String getSupplyType() {
        return supplyType;
    }

    public void setSupplyType(String supplyType) {
        this.supplyType = supplyType;
    }

    public String getMainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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

    public Long getAuditBy() {
        return auditBy;
    }

    public void setAuditBy(Long auditBy) {
        this.auditBy = auditBy;
    }

    public Integer getSalesCount() {
        return salesCount;
    }

    public void setSalesCount(Integer salesCount) {
        this.salesCount = salesCount;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(Integer ratingCount) {
        this.ratingCount = ratingCount;
    }

    public Long getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(Long minPrice) {
        this.minPrice = minPrice;
    }

    public Long getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(Long maxPrice) {
        this.maxPrice = maxPrice;
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

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }
}
