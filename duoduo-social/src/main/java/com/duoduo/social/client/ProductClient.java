package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.client.dto.ProductCardBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域客户端：取商品卡事实（第四十七轮）。
 *
 * <p>两个用途：①「问一问」在服务端定位收件人（SPU 归属卖家，不信前端）；
 * ②商品卡片消息按 bizId 现取展示字段，公开接口贴不出伪造卡片。
 */
@FeignClient(name = "duoduo-product", path = "/api/products")
public interface ProductClient {

    @GetMapping("/internal/cards")
    ApiResponse<List<ProductCardBrief>> cards(@RequestParam("ids") String spuIds);
}
