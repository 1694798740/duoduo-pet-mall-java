package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.client.dto.ListingCardBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域闲置客户端（第五十三轮）：取闲置卡事实。
 *
 * <p>两个用途：①闲置咨询（LISTING）在服务端定位收件人（闲置归属卖家，不信前端）；
 * ②闲置卡片消息按 bizId 现取展示字段，公开接口贴不出伪造卡片。
 * 与 {@link ProductClient}（商城商品）分开声明的原因：两者的基础路径不同
 * （{@code /api/listings} vs {@code /api/products}），一个 Feign 接口一个基础路径。
 * contextId 必填：与 ProductClient 同 name，不区分 contextId 会注册同名
 * {@code FeignClientSpecification} 导致启动失败（第五十三轮实测）。
 */
@FeignClient(name = "duoduo-product", contextId = "listingCard", path = "/api/listings")
public interface ListingClient {

    @GetMapping("/internal/cards")
    ApiResponse<List<ListingCardBrief>> cards(@RequestParam("ids") String listingIds);
}
