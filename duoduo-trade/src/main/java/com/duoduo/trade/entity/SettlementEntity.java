package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 资金托管单（C2C 担保交易的账本）。
 *
 * <p>为什么把资金单独建账本，而不是在订单上记两个金额字段：
 * <ul>
 *   <li><b>钱与订单是两条不同的事实</b>：订单会改状态，但钱在哪里（在平台托管 / 已给卖家 / 已退买家）
 *       是另一条独立的时间线。混在一起，出问题时无法回答「这笔钱现在到底在谁手里」；</li>
 *   <li><b>一单一条</b>：{@code uk_settle_order} 保证同一订单不会出现两条托管记录，
 *       也就不会出现「同一笔钱被放款两次」；</li>
 *   <li><b>状态迁移用 CAS</b>（见 {@link com.duoduo.trade.mapper.SettlementMapper}）：
 *       只有「托管中」的才能放款或退款，重复调用天然无副作用，不需要分布式锁。</li>
 * </ul>
 *
 * <p>状态机：{@code CUSTODY}（托管中）→ {@code RELEASED}（已放款给卖家）
 * 或 {@code REFUNDED}（已退回买家）。两条边都不可逆，也不可互换。
 *
 * <p>{@code platformFee} 单独成列而不是「结算时再算」：
 * 费率会随运营策略调整，而历史单据必须保留**结算当刻**的口径，
 * 否则对账时会出现「按今天的费率算昨天的单，怎么都对不平」。
 *
 * <p>金额一律整数「分」；时间 UTC。
 */
@TableName("t_settlement")
public class SettlementEntity {

    public static final String STATUS_CUSTODY = "CUSTODY";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    @TableId
    private Long id;
    private Long orderId;
    private Long sellerId;
    /** 托管金额 = 买家实付 */
    private Long amount;
    /** 平台佣金（从托管金额里扣，余下打给卖家） */
    private Long platformFee;
    private String status;
    /** 计划放款时间（当前实现为「确认收货」即放款，与 releasedAt 相等；接入结算周期后表示 T+N 的打款日） */
    private LocalDateTime releaseAt;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getPlatformFee() { return platformFee; }
    public void setPlatformFee(Long platformFee) { this.platformFee = platformFee; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getReleaseAt() { return releaseAt; }
    public void setReleaseAt(LocalDateTime releaseAt) { this.releaseAt = releaseAt; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
