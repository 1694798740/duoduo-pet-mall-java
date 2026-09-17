package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 库存冻结台账。
 *
 * <p>真实库存在商品服务，本表只记「本订单冻结了哪些 SKU、多少件、什么状态」。
 * 它是**补偿与释放的依据**：取消订单、超时关单时按这张表逐条释放。
 *
 * <p>{@code (skuId, orderId)} 唯一：保证同一订单对同一 SKU 只冻结一次，
 * 重复下单不会重复冻结。
 *
 * <p>状态机：{@code FROZEN}（下单已冻结）→ {@code DEDUCTED}（支付成功已扣减）
 * 或 {@code RELEASED}（取消/超时已释放）。
 */
@TableName("t_stock_freeze")
public class StockFreezeEntity {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_DEDUCTED = "DEDUCTED";
    public static final String STATUS_RELEASED = "RELEASED";

    @TableId
    private Long id;
    private Long skuId;
    private Long orderId;
    private Integer quantity;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
