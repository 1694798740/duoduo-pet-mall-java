package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.client.dto.MerchantLicenseInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户域店铺资质客户端（E15）：上架商品前确认「这家店现在能不能卖东西」。
 *
 * <p>{@code contextId} 必须与 {@link MembershipClient} 区分：两者都指向
 * {@code duoduo-user}，Feign 以 {@code name} 为注册键，不区分 {@code contextId}
 * 会互相覆盖、启动即炸（{@code BeanDefinitionOverrideException}）——
 * 本域已有两个指向 user 的接口，每个都要显式声明。
 *
 * <p>调用点是<b>上架动作</b>（{@code changeStatus} 目标态 ON_SALE），不是每次读写商品。
 * 做成「每次都查」在功能上更严密，但会让商品列表、编辑、详情全部依赖用户域可用性 ——
 * 那是最典型的把一次跨域抖动放大成整站不可用的做法。
 */
@FeignClient(name = "duoduo-user", contextId = "productMerchantLicenseClient",
        path = "/api/users")
public interface MerchantLicenseClient {

    /**
     * 按店主 userId 查店铺资质。
     *
     * <p>返回体 {@code data} 为 {@code null} 表示该用户没有入驻申请
     * （平台自营的商品归属、或从未申请过）—— 调用方按「不阻塞」处理。
     */
    @GetMapping("/internal/merchant-license")
    ApiResponse<MerchantLicenseInfo> of(@RequestParam("ownerUserId") Long ownerUserId);
}
