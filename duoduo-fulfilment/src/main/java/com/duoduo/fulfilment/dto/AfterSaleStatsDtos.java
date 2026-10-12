package com.duoduo.fulfilment.dto;

import java.util.List;

/**
 * E24 · 售后统计的**服务间契约**（履约域 → 交易域）。
 *
 * <p>刻意与 {@link AfterSaleDtos} 分开：那一份是「给用户看的售后单」，
 * 字段里有买家诉求、举证图、卖家回复；本份是「给运维面板看的聚合量」，
 * 一行一个卖家、不含任何订单或买家信息。
 * 把两者合在一个类里，迟早会有人为了省事把明细字段塞进聚合响应 ——
 * 而聚合接口是**不经网关的内部接口**，多一个字段就多一份越过网关鉴权的暴露面。
 *
 * <p>金额与计数都是原始值（分 / 笔），**不在本域做百分比**：
 * 售后率的分母（已付款订单数）在交易域，本域拿不到也不该去拿。
 * 两个域各自算一半比例，是本项目明确禁止的模式（同 E20「判定点只有一个」）——
 * 一旦分叉，出现的是"面板说 12%、账单按 15% 算"这种谁也说不清谁对的分歧。
 */
public final class AfterSaleStatsDtos {

    private AfterSaleStatsDtos() {
    }

    /**
     * 单个卖家在一个时间窗内的售后聚合。
     *
     * @param sellerId     卖家 userId（= 租户键，E20 口径）
     * @param total        窗口内立案数
     * @param refunded     其中终态为已退款
     * @param openCnt      其中未结案（非 {@code REFUNDED} 且非 {@code CLOSED}）
     * @param adverse      其中运营裁定支持退款（卖家败诉）
     * @param refundAmount 已退金额合计（分）
     */
    public record TenantAfterSaleStat(long sellerId,
                                      long total,
                                      long refunded,
                                      long openCnt,
                                      long adverse,
                                      long refundAmount) {
    }

    /**
     * 批量查询的响应体。
     *
     * <p>带 {@code from} / {@code to} 回显**实际生效的窗口**，而不是让调用方自己记：
     * 服务端会对窗口做规范化（补时分秒、纠偏 to &lt;= from），
     * 调用方拿回显值去解释这批数字，就不会出现"我按 A 窗口理解、服务端按 B 窗口算"。
     * 这是"断言要测服务端输出"（不以客户端理解为准）在这类聚合接口上的落地。
     */
    public record TenantAfterSaleStats(String from, String to, List<TenantAfterSaleStat> rows) {
    }
}
