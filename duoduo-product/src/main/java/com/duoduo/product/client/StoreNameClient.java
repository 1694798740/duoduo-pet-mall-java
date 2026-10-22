package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.client.dto.StoreBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域店铺名客户端：批量按店主 userId 取 {@code t_merchant.name}。
 *
 * <p>为什么商品域要跨域取名：购物车要「按店铺分组、显示真实店铺名」，
 * 商品卡要显示「自有店名」而不是千篇一律的「品牌入驻」—— 而店铺主数据
 * （{@code t_merchant}）在用户域，商品域没有也不该有店铺表副本。
 *
 * <p>{@code contextId} 必须独立：本域已有多个指向 {@code duoduo-user} 的 Feign 客户端，
 * 不区分 contextId 会互相覆盖、启动即炸（见 {@link MerchantLicenseClient} 注释）。
 */
@FeignClient(name = "duoduo-user", contextId = "productStoreNameClient",
        path = "/api/users")
public interface StoreNameClient {

    /**
     * 批量查店铺名。键 = 店主 userId（与 {@code t_spu.seller_id} 同一个键）。
     * 查不到的 id 不返回 —— 调用方按「未知店铺」降级；自营（sellerId=0）由调用方
     * 直接显示「多多自营」，不走本接口。
     */
    @GetMapping("/internal/store-names")
    ApiResponse<List<StoreBrief>> batch(@RequestParam("ownerIds") String ownerIds);
}
