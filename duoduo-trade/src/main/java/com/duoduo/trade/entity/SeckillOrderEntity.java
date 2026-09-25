package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 秒杀下单台账：订单与活动配额的关联 + 单人限购的判重依据。
 *
 * <p>为什么不在 t_order 上加活动字段：秒杀是营销动作，不是订单的固有属性，
 * 混进核心表会让正常下单路径永远背着一个可空的活动字段；
 * 单独一张台账表，回补配额按 order_no 反查，限购按 (user_id, activity_id) 计数，
 * 职责干净且不碰核心表结构。
 *
 * <p>不设 status 列：这张表只回答「这单来自哪个活动、扣了谁家的配额」，
 * 订单本身的生老病死由 t_order 的状态机负责 —— 回补的判据直接查订单是否 CLOSED，
 * 同一事实只存一份，才不会有对不上的第三状态。
 */
@TableName("t_seckill_order")
public class SeckillOrderEntity {

    @TableId
    private Long id;
    private Long userId;
    private Long activityId;
    private Long seckillSkuId;
    private Long skuId;
    private String orderNo;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getSeckillSkuId() { return seckillSkuId; }
    public void setSeckillSkuId(Long seckillSkuId) { this.seckillSkuId = seckillSkuId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
