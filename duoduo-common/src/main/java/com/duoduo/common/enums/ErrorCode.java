package com.duoduo.common.enums;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 * 每个错误码绑定一个 HTTP 状态码，保证「HTTP 语义」与「业务语义」一致，客户端无需额外映射表。
 *
 * <p>与 PRD 的对应关系：VALIDATION 对应边界校验（400）、BUSINESS_RULE 对应状态机与库存等业务约束（422）、
 * FORBIDDEN 对应权限矩阵（403）、RATE_LIMITED 对应限流（429）。
 */
public enum ErrorCode {

    SUCCESS(HttpStatus.OK, "成功"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求不合法"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不合法"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "身份未认证或登录已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行该操作"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "资源状态冲突"),

    BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY, "业务规则不满足"),
    STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "库存不足"),
    ORDER_STATUS_ILLEGAL(HttpStatus.UNPROCESSABLE_ENTITY, "订单状态不允许该操作"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    DEGRADED(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务不可用，已降级"),

    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后重试");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int httpStatusValue() {
        return httpStatus.value();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
