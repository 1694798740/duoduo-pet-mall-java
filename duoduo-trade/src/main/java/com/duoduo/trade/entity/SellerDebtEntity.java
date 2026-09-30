package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 卖家欠款（第五十八轮 · B-01）：平台垫付退款后，向卖家追偿的那笔账。
 *
 * <h2>为什么必须单独建这张表
 * 钱一旦放款给卖家，托管单就是 {@code RELEASED} 终态 —— 账上「买家的钱」已经不存在了。
 * 此时买家仍应拿到退款，只能是<b>平台先垫付</b>：钱由平台出，债从「买家 ↔ 卖家」
 * 变成「平台 ↔ 卖家」。不记这笔债，它就会只存在于运营的口头记录里：
 * 结算时无从抵扣、对账时对不上差额、也无法回答「这个卖家还欠平台多少」。
 *
 * <h2>幂等
 * 唯一键 {@code uk_seller_debt_order(order_id)}：**一个订单最多垫付一次**。
 * 应用层用 {@code INSERT IGNORE} 撞唯一键，由数据库裁决，而不是「先查有没有再插」——
 * 后者在并发下两次查询都看到「没有」，于是插出两条、垫付两次。
 *
 * <h2>金额口径
 * {@code amount} = 平台垫付给买家的金额 = 订单实付 {@code pay_amount}。
 * 与退款同口径：**不含券面额**（券已核销为终态，见 {@code OrderService#doRefund} 的说明）。
 * 卖家该还的是「买家实付的这部分钱」，不是商品原价。
 */
@TableName("t_seller_debt")
public class SellerDebtEntity {

    public static final String STATUS_OUTSTANDING = "OUTSTANDING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_WRITTEN_OFF = "WRITTEN_OFF";

    @TableId
    private Long id;
    private Long sellerId;
    private Long orderId;
    /** 冗余订单号：对账与人工查询时不必再 join 订单表 */
    private String orderNo;
    /** 欠款金额（分） */
    private Long amount;
    private String status;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static String labelOf(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_OUTSTANDING -> "未收回";
            case STATUS_SETTLED -> "已收回";
            case STATUS_WRITTEN_OFF -> "已核销";
            default -> status;
        };
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
