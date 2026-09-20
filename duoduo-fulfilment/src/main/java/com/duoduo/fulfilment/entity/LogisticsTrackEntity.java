package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 物流轨迹节点（A2）。
 *
 * <p><b>这张表解决什么</b>：在此之前，一次发货只留下两样东西 ——
 * 订单上的「物流公司 + 单号」两个字段，和一条状态日志。
 * 买家想知道「包裹到哪了」只能自己去快递官网查单号；卖家被问起来也只能自己去查。
 * 轨迹表把「这条单号下发生过哪些节点」变成平台里可查的记录，
 * 买卖双方的订单详情页都能直接看到时间线。
 *
 * <p><b>为什么单独建表而不是在订单上加字段</b>：一次运输会产生多条节点，
 * 且数量事先不可知；塞进订单表只能存成 JSON 大字段，
 * 于是「查某条节点」和「按类型统计」都变成字符串匹配 —— 这是把结构化数据写成了日志。
 *
 * <p><b>幂等靠唯一索引</b>（{@code uk_track_order_node_time} = 订单号 + 节点码 + 发生时间）：
 * 物流推送的常见形态是「失败重推 / 补推 / 定时全量对齐」，
 * 同一条节点被推两次是完全正常的事，不能算是异常。
 * 靠唯一索引把重复**静默吃掉**（撞键即跳过），而不是先查再插 ——
 * 后者在并发重推下必然漏，而漏的表现是轨迹里出现两条一模一样的记录。
 *
 * <p><b>时间取「发生时间」而不是「入库时间」</b>：推送可能延迟或补推，
 * 入库时间只能反映我们什么时候收到，反映不了包裹什么时候到。排序、展示一律用 {@code occurredAt}。
 * 全部按 UTC 存（INV-12），出参再转 ISO-8601 字符串。
 */
@TableName("t_logistics_track")
public class LogisticsTrackEntity {

    @TableId
    private Long id;
    /** 由订单号派生的稳定标识（见 {@code OrderRefs}），与评价 / 售后两域同口径 */
    private Long orderId;
    /** 交易域订单号（本域不生成订单号） */
    private String orderNo;
    /** 节点码，取值见 {@code LogisticsNode}（白名单，推送方不可自创） */
    private String nodeCode;
    /** 节点描述原文（推送方给的细节，如「【上海分拨中心】已收入」） */
    private String nodeText;
    /** 节点**发生**时间（UTC）；唯一键的一部分，也是排序依据 */
    private LocalDateTime occurredAt;
    /**
     * 原始报文（JSON 字符串）：上报来源、物流公司、单号，以及推送方给的原始字段。
     *
     * <p>只入库、<b>不对外返回</b>。留它是为了两件事：一是轨迹被质疑时能回溯「当初收到的是什么」，
     * 二是将来对快递公司报文时不用改表结构。
     */
    private String raw;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
    public String getNodeText() { return nodeText; }
    public void setNodeText(String nodeText) { this.nodeText = nodeText; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
