package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 购物车条目。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不存商品快照</b>：购物车展示的是「当前价格」，价格变了必须实时反映，
 *       与订单明细刻意相反（订单必须冻结下单时的价格）。因此这里只存 skuId + 数量，展示时回源商品服务。</li>
 *   <li><b>spuId 冗余一份</b>：按 SPU 分组展示（同一商品的不同规格合并成一张卡）时不必再查商品服务。</li>
 *   <li><b>(userId, skuId) 唯一</b>：并发加购靠唯一索引兜底，而不是先查后插 ——
 *       先查后插在并发下会插出两行，唯一索引能把这种竞态变成一次可重试的 DuplicateKey。</li>
 * </ul>
 */
@TableName("t_cart_item")
public class CartItemEntity {

    @TableId
    private Long id;
    private Long userId;
    private Long spuId;
    private Long skuId;
    private Integer quantity;
    /** 1 = 已勾选参与结算，0 = 未勾选 */
    private Integer selected;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getSelected() {
        return selected;
    }

    public void setSelected(Integer selected) {
        this.selected = selected;
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
