package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 用户域「多多币任务事件」客户端（R-P1 第三轮）。
 *
 * <p>商品域只负责上报事实（收藏），奖励规则、日上限与幂等全部在用户域；
 * 凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}。
 * 上报失败不影响收藏本身（任务奖励是附加激励）。
 */
@FeignClient(name = "duoduo-user", contextId = "productCoinTaskClient", path = "/api/coins/internal")
public interface CoinTaskClient {

    @PostMapping("/task-event")
    ApiResponse<Object> taskEvent(@RequestBody Map<String, Object> body);
}
