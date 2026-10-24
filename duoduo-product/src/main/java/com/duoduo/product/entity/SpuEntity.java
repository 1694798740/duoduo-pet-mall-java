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
    /** 商品介绍（纯文本，2026-10-24）：与富文本详情分开，详情页「商品介绍」区渲染它 */
    private String intro;
    /** 富文本详情 HTML（2026-10-24）：服务端白名单消毒后落库，端侧按 HTML 渲染 */
    private String detailHtml;
    /** 规格参数 JSON：[{name,value}]（2026-10-24，商家可编辑） */
    private String specParams;
    /** 包装规格 JSON：[{name,value}]（2026-10-24，商家可编辑） */
    private String packaging;
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
    /**
     * 审核来源（E28）：AUTO(按规则自动放行) / MANUAL(人工判定) / SELF(自营免审)；
     * NULL = E28 之前的历史数据。
     *
     * <p>为什么不从 {@link #auditBy} 反推：auditBy 为 null 有三种含义
     * （自营从未审过 / 系统自动放行 / 历史数据），而三者之后要做的事完全不同 ——
     * 自营不用管、自动放行要纳入抽查、历史数据要补标。
     */
    private String auditSource;
    /**
     * 最近一次被驳回的时间（2026-10-24）。
     *
     * <p>为什么需要它：此前「曾被驳回」只从 auditStatus 现读现判，而卖家第一次编辑就会把
     * REJECTED 改成 PENDING —— 第二次编辑时该事实已消失，干净内容会被自动放行，等于
     * 「改两次就绕过审核」。改为持久化标记：人工/抽查驳回时落时间，**人工通过时清空**，
     * 只有清空后内容变更才允许重新走自动放行。
     */
    private LocalDateTime rejectedAt;
    /** 累计被驳回次数（只增）：运营判断「反复违规」的参考，不参与放行判定 */
    private Integer rejectCount;
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

    public String getAuditSource() {
        return auditSource;
    }

    public void setAuditSource(String auditSource) {
        this.auditSource = auditSource;
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

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getDetailHtml() {
        return detailHtml;
    }

    public void setDetailHtml(String detailHtml) {
        this.detailHtml = detailHtml;
    }

    public String getSpecParams() {
        return specParams;
    }

    public void setSpecParams(String specParams) {
        this.specParams = specParams;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public Integer getRejectCount() {
        return rejectCount;
    }

    public void setRejectCount(Integer rejectCount) {
        this.rejectCount = rejectCount;
    }
}
