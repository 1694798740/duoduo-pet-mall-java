package com.duoduo.social.client.dto;

/**
 * 店铺名简称：用户服务 {@code /api/users/internal/store-names} 的响应体副本。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与商品域 {@code StoreBrief} 同一纪律）：
 * 客服会话只需要「这个接待方叫什么」，用户域往后加字段，本域不该跟着重新编译。
 *
 * @param ownerUserId 店主 userId（= 客服会话 {@code t_support_session.seller_id} 的键）
 * @param name        店铺名（t_merchant.name，全局唯一）
 */
public record StoreBrief(
        Long ownerUserId,
        String name
) {
}
