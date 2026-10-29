package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 用户域「多多币任务事件」客户端（R-P1 第三轮）。
 *
 * <p>履约域只上报事实（晒单 / 评价过审），奖励规则、日上限与幂等全部在用户域；
 * 凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}。
 * 上报失败不影响发布或评价落库（任务奖励是附加激励）。
 *
 * <p>本模块已有 {@code UserClient} 指向同一个服务名，因此这里必须显式给
 * {@code contextId} —— 否则 Feign 会因「同一 name 两个接口」拒绝启动。
 */
@FeignClient(name = "duoduo-user", contextId = "fulfilmentCoinTaskClient", path = "/api/coins/internal")
public interface CoinTaskClient {

    @PostMapping("/task-event")
    ApiResponse<Object> taskEvent(@RequestBody Map<String, Object> body);
}
