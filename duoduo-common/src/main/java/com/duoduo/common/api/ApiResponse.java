package com.duoduo.common.api;

import java.time.Instant;

/**
 * 统一响应结构。
 * 成功：{"code":"SUCCESS","message":"ok","data":{...},"requestId":"...","timestamp":...}
 * 失败：{"code":"BUSINESS_RULE","message":"库存不足","data":null,"requestId":"...","timestamp":...}
 *
 * <p>约定：HTTP 状态码同时反映语义（400/401/403/404/409/422/429/500），
 * 客户端既可以看状态码，也可以看 body.code。
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId,
        long timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("SUCCESS", "ok", data, RequestIdHolder.get(), Instant.now().toEpochMilli());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null, RequestIdHolder.get(), Instant.now().toEpochMilli());
    }
}
