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
    /**
     * 请求体格式不支持（415）。
     *
     * <p>典型触发：客户端发 POST 却带 {@code application/x-www-form-urlencoded}
     * （curl、部分 HTTP 库在无 body 时的默认行为），而接口要的是 JSON。
     * 这<b>是客户端的问题</b>，此前落到兜底分支变成 500，
     * 既污染了 5xx 告警，又把「你发错了格式」说成了「我坏了」。
     */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求体格式，请使用 application/json"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "身份未认证或登录已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行该操作"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "资源状态冲突"),

    BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY, "业务规则不满足"),
    STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "库存不足"),
    ORDER_STATUS_ILLEGAL(HttpStatus.UNPROCESSABLE_ENTITY, "订单状态不允许该操作"),
    /** 支付渠道回调验签失败：对渠道而言等同于「调用方身份不可信」，故用 401 而非 422 */
    PAYMENT_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "支付回调签名校验未通过"),
    PAYMENT_CHANNEL_UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的支付渠道"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "支付回调金额与支付单不一致"),

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
