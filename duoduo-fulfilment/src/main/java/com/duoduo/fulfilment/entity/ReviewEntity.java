package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 评价（一张表同时承载首次评价与追评）。
 *
 * <p>追评只有一次机会，因此用两个列（{@code appendContent} / {@code appendAt}）而不是
 * 另建一张表：建表容易，但「什么样算一条评价」会立刻变成两处定义，
 * 列表接口、聚合口径、审核状态都要各写两遍。真正需要多条追评时再拆，
 * 那时数据形态已经稳定，迁移成本远低于现在。
 *
 * <p>时间语义（全库统一 UTC）：
 * <ul>
 *   <li>{@code createdAt} —— 首次评价时间，也是追评窗口的起算点；</li>
 *   <li>{@code appendAt} —— 追评时间，未追评为 null。</li>
 * </ul>
 */
@TableName("t_review")
public class ReviewEntity {

    @TableId
    private Long id;

    /** 订单主键（来自交易域，本域不生成）；与 user_id + spu_id 组成唯一键 */
    private Long orderId;
    /** 订单号：运维按单号直查用，比主键更好沟通 */
    private String orderNo;
    private Long userId;
    private Long sellerId;
    /** 评价对象：SPU 维度聚合展示 */
    private Long spuId;
    private Long skuId;
    /** 商品快照：商品后续改名换图不影响历史评价 */
    private String productTitle;
    private String productImage;
    /** 评分 1-5 */
    private Integer rating;
    private String content;
    /** 图片 URL 列表的 JSON 数组字符串（最多 N 张，服务层校验） */
    private String images;
    /** 匿名标记：只影响展示，user_id 始终保留（信用分与风控需要真实主体） */
    private Integer anonymous;
    private String appendContent;
    private LocalDateTime appendAt;
    /**
     * 商家回复（A-16）：一条评价最多一次。
     *
     * <p>回复是公开内容（展示在商品评价区），但不改变评价本身的评分与审核态；
     * 是否已回复只看 {@code replyContent} 是否有值，不另建回复表。
     */
    private String replyContent;
    /** 商家回复时间（UTC） */
    private LocalDateTime replyAt;
    /** 回复的商家账号 userId（与 sellerId 同口径；平台自营由运营代回复） */
    private Long repliedBy;
    /** PENDING / APPROVED / REJECTED（H-02 起后台可人工下架 / 恢复；A-15 起提交先过机审） */
    private String auditStatus;
    /** 审核结论说明：下架必填，恢复时清空；机审转人工时写命中原因 */
    private String auditReason;
    /** 最近一次审核操作的运营 userId 快照（用户改名不影响历史可读；机审为 null） */
    private Long auditedBy;
    /** 最近一次审核时间（UTC） */
    private LocalDateTime auditedAt;
    /** AUTO 机审自动 / MANUAL 人工 / null 为 A-15 之前的历史数据 */
    private String auditSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public String getProductTitle() {
        return productTitle;
    }

    public void setProductTitle(String productTitle) {
        this.productTitle = productTitle;
    }

    public String getProductImage() {
        return productImage;
    }

    public void setProductImage(String productImage) {
        this.productImage = productImage;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public Integer getAnonymous() {
        return anonymous;
    }

    public void setAnonymous(Integer anonymous) {
        this.anonymous = anonymous;
    }

    public String getAppendContent() {
        return appendContent;
    }

    public void setAppendContent(String appendContent) {
        this.appendContent = appendContent;
    }

    public LocalDateTime getAppendAt() {
        return appendAt;
    }

    public void setAppendAt(LocalDateTime appendAt) {
        this.appendAt = appendAt;
    }

    public String getReplyContent() {
        return replyContent;
    }

    public void setReplyContent(String replyContent) {
        this.replyContent = replyContent;
    }

    public LocalDateTime getReplyAt() {
        return replyAt;
    }

    public void setReplyAt(LocalDateTime replyAt) {
        this.replyAt = replyAt;
    }

    public Long getRepliedBy() {
        return repliedBy;
    }

    public void setRepliedBy(Long repliedBy) {
        this.repliedBy = repliedBy;
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

    public Long getAuditedBy() {
        return auditedBy;
    }

    public void setAuditedBy(Long auditedBy) {
        this.auditedBy = auditedBy;
    }

    public LocalDateTime getAuditedAt() {
        return auditedAt;
    }

    public void setAuditedAt(LocalDateTime auditedAt) {
        this.auditedAt = auditedAt;
    }

    public String getAuditSource() {
        return auditSource;
    }

    public void setAuditSource(String auditSource) {
        this.auditSource = auditSource;
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
