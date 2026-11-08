package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单明细（下单时刻的**快照**）。
 *
 * <p>与购物车条目刻意相反：购物车展示「当前价格」，订单必须冻结「下单时的价格与标题」。
 * 否则商品改价改名后，历史订单的金额与展示都会跟着变，对不上账、也说不清。
 *
 * <p>因此这里冗余保存 {@code title} / {@code specText} / {@code mainImage} / {@code price}，
 * 商品服务后续的任何变更都不影响已生成的订单。
 */
@TableName("t_order_item")
public class OrderItemEntity {

    @TableId
    private Long id;
    private Long orderId;
    /**
     * 闲置 id（B-11 · C2C 闲置担保交易）：闲置订单专有，此时 spuId / skuId 为 NULL
     * —— 闲置是「一物一价」的个人转卖，不在 SKU 体系里，金额与标题直接落本行快照。
     * 商城订单该列为 NULL，与 spuId/skuId 互斥。
     */
    private Long listingId;
    private Long spuId;
    private Long skuId;
    private String title;
    private String specText;
    private String mainImage;
    private Long price;
    private Integer quantity;
    private Long amount;
    /** NONE / APPLIED / REFUNDED */
    private String refundStatus;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getListingId() { return listingId; }
    public void setListingId(Long listingId) { this.listingId = listingId; }
    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSpecText() { return specText; }
    public void setSpecText(String specText) { this.specText = specText; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
