package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.StoreBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域店铺名客户端（2026-10-22 · 订单卡片显示店铺名）。
 *
 * <p>「我的订单」卡片要显示并跳转店铺：第三方 = 店主 userId 查 t_merchant.name；
 * 自营订单 seller_id=0 不查（固定「多多自营」，自营没有 t_merchant 行）。
 * 批量取、失败降级为空 —— 店铺名是展示信息，不能因为它把订单列表打挂。
 */
@FeignClient(name = "duoduo-user", contextId = "tradeStoreNameClient", path = "/api/users")
public interface StoreNameClient {

    @GetMapping("/internal/store-names")
    ApiResponse<List<StoreBrief>> batch(@RequestParam("ownerIds") String ownerIds);
}
