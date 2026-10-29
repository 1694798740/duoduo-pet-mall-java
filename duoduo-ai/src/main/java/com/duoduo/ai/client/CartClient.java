package com.duoduo.ai.client;

import com.duoduo.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 购物车写接口（AI 加购工具用）。
 *
 * <p>身份头由服务端从登录态注入，绝不从模型参数取 userId；
 * 写操作在 Agent 层已做二次确认，这里只负责真正落购物车。
 */
@FeignClient(name = "duoduo-trade", path = "/api/cart",
        configuration = AiFeignConfig.class)
public interface CartClient {

    @PostMapping("/items")
    ApiResponse<JsonNode> add(@RequestHeader("X-User-Id") Long userId,
                              @RequestHeader(value = "X-User-Role", required = false) String role,
                              @RequestHeader(value = "X-User-Phone", required = false) String phone,
                              @RequestBody CartAddRequest request);

    record CartAddRequest(Long skuId, Integer quantity) {
    }
}
