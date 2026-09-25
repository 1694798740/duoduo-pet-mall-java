package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 秒杀 SKU（活动 × 商品规格）。
 *
 * <p>秒杀价（分）与原价都落快照 —— 活动开始后改商品价不影响已配置的秒杀口径。
 * 配额（total_stock / available_stock）是秒杀**自己的货**，与商品服务的库存独立：
 * 下单不冻结商品库存，关单 / 取消只回补这里的 available_stock。
 *
 * <p>防超卖的正确性兜底就在这张表上：
 * {@code UPDATE t_seckill_sku SET available_stock = available_stock - 1
 * WHERE id = ? AND available_stock > 0}，单条 UPDATE 的行锁天然原子，
 * 影响行数为 0 即「已抢完」。Redis 预扣只是**挡在 DB 之前的限流闸**，
 * 不承担正确性（它可能丢、可能被 LRU 逐出、可能降级停用）。
 */
@TableName("t_seckill_sku")
public class SeckillSkuEntity {

    @TableId
    private Long id;
    private Long activityId;
    private Long skuId;
    private Long seckillPrice;
    private Long originalPrice;
    private Integer totalStock;
    private Integer availableStock;
    /** 单人限购件数（同一活动同一 SKU） */
    private Integer perLimit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getSeckillPrice() { return seckillPrice; }
    public void setSeckillPrice(Long seckillPrice) { this.seckillPrice = seckillPrice; }
    public Long getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(Long originalPrice) { this.originalPrice = originalPrice; }
    public Integer getTotalStock() { return totalStock; }
    public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }
    public Integer getAvailableStock() { return availableStock; }
    public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
    public Integer getPerLimit() { return perLimit; }
    public void setPerLimit(Integer perLimit) { this.perLimit = perLimit; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
