package com.duoduo.trade.dto;

/**
 * 交易域 · 资金托管 DTO。
 *
 * <p>金额一律整数「分」；时间一律 ISO8601 UTC 字符串。
 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    /**
     * 资金托管视图。
     *
     * <p>{@code sellerAmount} = {@code amount} − {@code platformFee}，
     * 由服务端算好给出，而不是让前端做减法 ——
     * 前端做减法意味着「佣金口径」在很多个页面各实现一遍，
     * 以后调整费率或加阶梯规则时必然出现某些页面没有同步更新。
     */
    public record SettlementView(
            String orderNo,
            Long sellerId,
            /** 托管金额（= 买家实付） */
            long amount,
            /** 平台佣金 */
            long platformFee,
            /** 卖家可得 = amount − platformFee */
            long sellerAmount,
            String status,
            /** 状态中文文案，由服务端给出 */
            String statusLabel,
            String releaseAt,
            String releasedAt,
            String createdAt
    ) {
    }
}
