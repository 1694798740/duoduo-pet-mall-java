package com.duoduo.product.client.dto;

/**
 * 店铺名简称：用户服务 {@code /api/users/internal/store-names} 的响应体副本。
 *
 * <p>跨服务 DTO 刻意<b>复制而不是共享</b>（与本域 {@link MerchantLicenseInfo} 同一纪律）：
 * 商品域只需要「这个卖家叫什么」，用户域往后给视图加字段，商品域不该跟着重新编译。
 *
 * @param ownerUserId 店主 userId（= 商品归属 {@code t_spu.seller_id} 的键）
 * @param name        店铺名（t_merchant.name，全局唯一）
 */
public record StoreBrief(
        Long ownerUserId,
        String name
) {
}
