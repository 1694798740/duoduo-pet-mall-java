package com.duoduo.trade.client.dto;

/**
 * 店铺名简称：用户服务 {@code /api/users/internal/store-names} 的响应体副本。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与商品域/社交域副本同一纪律）：
 * 订单卡片只需要「这单是哪家店」，用户域往后给视图加字段，交易域不该跟着重新编译。
 *
 * @param ownerUserId 店主 userId（= 订单 {@code t_order.seller_id} 的键）
 * @param name        店铺名（t_merchant.name，全局唯一）
 */
public record StoreBrief(
        Long ownerUserId,
        String name
) {
}
