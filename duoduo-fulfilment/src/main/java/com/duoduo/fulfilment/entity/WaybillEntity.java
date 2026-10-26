package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 电子面单（B-05）。
 *
 * <p>一个订单最多一张有效面单：{@code uk_waybill_order} 是幂等键 ——
 * 卖家连点两次「获取电子面单」必须拿到同一个运单号，而不是两个。
 *
 * <p>{@code mode} 区分模拟与真实渠道：本地没有快递鸟/菜鸟凭据时走模拟，
 * 轨迹形态、接口与前端完全一致；接真实渠道时只替换 Provider。
 */
@TableName("t_waybill")
public class WaybillEntity {

    @TableId
    private Long id;
    private Long orderId;
    private String orderNo;
    private Long sellerId;
    private String courierCode;
    private String courierName;
    private String waybillNo;
    /** SIMULATED / EXTERNAL */
    private String mode;
    /** CREATED / PICKED_UP / IN_TRANSIT / OUT_FOR_DELIVERY / SIGNED */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getCourierCode() { return courierCode; }
    public void setCourierCode(String courierCode) { this.courierCode = courierCode; }
    public String getCourierName() { return courierName; }
    public void setCourierName(String courierName) { this.courierName = courierName; }
    public String getWaybillNo() { return waybillNo; }
    public void setWaybillNo(String waybillNo) { this.waybillNo = waybillNo; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
