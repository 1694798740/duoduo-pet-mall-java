package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 售后单（退款争议的立案与裁定记录）。
 *
 * <p><b>为什么要有这张表</b>：上线至今的退款只有一种形态 ——「卖家一键退」，
 * 买家想退款只能先跟卖家私下沟通。一旦卖家不同意，买家在系统里**无路可走**，
 * 平台也拿不到任何可裁定的凭据（聊了什么、什么时候提的、诉求是什么）。
 * 售后单要解决的正是这个：把「争议」变成一条可查、可留痕、可裁定的记录。
 *
 * <p><b>钱不在这张表里</b>：本表只记录诉求与结论，真正的资金动作由交易域执行
 * （{@code POST /api/orders/internal/{orderNo}/refund}，托管 CAS 保证只退一次）。
 * 这样拆分的好处与资金托管账本一致：账本逻辑脱离渠道与流程独立可测，
 * 售后流程变化不需要碰钱。
 *
 * <p>{@code refundAmount} 由交易域的退款结果回填（不在这里自己算）：
 * 退多少必须由**钱的那一方**说了算，本域算出来的数字即使今天正确，
 * 也会在将来「部分退款 / 扣运费」这类规则出现时变成第二个事实来源。
 *
 * <p>金额一律整数「分」；时间 UTC。主键为雪花 ID，且本项目各模块均为
 * {@code id-type: input}，插入前必须显式赋 id（否则 {@code Column 'id' cannot be null}）。
 */
@TableName("t_after_sale")
public class AfterSaleEntity {

    @TableId
    private Long id;
    /** 由订单号派生的稳定标识（见 {@code OrderRefs}），唯一键的一部分 */
    private Long orderId;
    /** 交易域订单号（运维按单号直查用；本域不生成订单号） */
    private String orderNo;
    /** 买家（= 订单归属人，来自订单事实，不接受请求体传入） */
    private Long buyerId;
    /** 卖家（= 订单的 seller_id，来自订单事实） */
    private Long sellerId;
    /** REFUND_ONLY / RETURN_REFUND */
    private String type;
    /** 买家诉求描述 */
    private String reason;
    /** 举证图片 URL 列表（JSON 数组字符串，只收 http/https 直链） */
    private String images;
    /** 见 {@code AfterSaleStatus} */
    private String status;
    /** 卖家处理说明（AGREE 时记动作、REJECT 时必填原因） */
    private String sellerReply;
    /** 仲裁结论：APPROVE_REFUND / REJECT */
    private String arbitrateResult;
    /** 仲裁说明（必填，留痕的核心） */
    private String arbitrateNote;
    /** 裁定人（运营的 userId）——只用于内部留痕，不对外返回 */
    private Long arbitratedBy;
    /** 实际退款金额（分），由交易域退款结果回填 */
    private Long refundAmount;
    /** 买家寄回的快递公司（第五十八轮 P1-5，仅 RETURN_REFUND 用） */
    private String returnCourier;
    /** 买家寄回的运单号 */
    private String returnTrackingNo;
    /** 买家填写寄回信息的时间 —— 即「已寄回」这一个节点 */
    private LocalDateTime returnShippedAt;
    /** 卖家确认收货的时间 —— 即「已签收」节点，退款在这一刻发生 */
    private LocalDateTime returnReceivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSellerReply() { return sellerReply; }
    public void setSellerReply(String sellerReply) { this.sellerReply = sellerReply; }
    public String getArbitrateResult() { return arbitrateResult; }
    public void setArbitrateResult(String arbitrateResult) { this.arbitrateResult = arbitrateResult; }
    public String getArbitrateNote() { return arbitrateNote; }
    public void setArbitrateNote(String arbitrateNote) { this.arbitrateNote = arbitrateNote; }
    public Long getArbitratedBy() { return arbitratedBy; }
    public void setArbitratedBy(Long arbitratedBy) { this.arbitratedBy = arbitratedBy; }
    public Long getRefundAmount() { return refundAmount; }
    public void setRefundAmount(Long refundAmount) { this.refundAmount = refundAmount; }
    public String getReturnCourier() { return returnCourier; }
    public void setReturnCourier(String returnCourier) { this.returnCourier = returnCourier; }
    public String getReturnTrackingNo() { return returnTrackingNo; }
    public void setReturnTrackingNo(String returnTrackingNo) { this.returnTrackingNo = returnTrackingNo; }
    public LocalDateTime getReturnShippedAt() { return returnShippedAt; }
    public void setReturnShippedAt(LocalDateTime returnShippedAt) { this.returnShippedAt = returnShippedAt; }
    public LocalDateTime getReturnReceivedAt() { return returnReceivedAt; }
    public void setReturnReceivedAt(LocalDateTime returnReceivedAt) { this.returnReceivedAt = returnReceivedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
