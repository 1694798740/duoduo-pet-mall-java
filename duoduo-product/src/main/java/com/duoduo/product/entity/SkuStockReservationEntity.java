package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单级库存预占台账（B-03）。
 *
 * <p>主键 {@code (order_id, sku_id)} 同时是幂等键：同一订单对同一 SKU 只允许一条预占。
 *
 * <p>状态机：
 * <pre>
 *   FROZEN ──支付成功──→ DEDUCTED
 *      └──取消 / 超时──→ RELEASED
 * </pre>
 *
 * <p>本表解决的是「释放 / 扣减只校验全局冻结量」导致的跨订单误操作：
 * 有了订单归属，释放 A 单时即使 B 单也冻着同一 SKU，也不会把 B 的冻结放掉。
 */
@TableName("t_sku_stock_reservation")
public class SkuStockReservationEntity {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_DEDUCTED = "DEDUCTED";

    private Long orderId;
    private Long skuId;
    private Integer quantity;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
