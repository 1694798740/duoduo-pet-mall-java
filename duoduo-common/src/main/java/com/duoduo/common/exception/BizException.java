package com.duoduo.common.exception;

import com.duoduo.common.enums.ErrorCode;

/**
 * 业务异常。
 * <p>服务层只抛本异常（或子类），由 Web 层的全局异常处理器统一转换为 ApiResponse，
 * 业务代码不关心 HTTP 状态码。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // ---------- 常用快捷构造 ----------

    public static BizException validation(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }

    public static BizException businessRule(String message) {
        return new BizException(ErrorCode.BUSINESS_RULE, message);
    }

    public static BizException notFound(String resource) {
        return new BizException(ErrorCode.NOT_FOUND, resource + "不存在");
    }

    public static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }

    /**
     * 频率受限（HTTP 429）。
     *
     * <p>刻意与 {@link #businessRule(String)}（422）区分开：422 是「这次请求的内容不对，
     * 改一改就能成功」，429 是「内容没问题，但发得太快，等一会儿再试」——
     * 两者的用户动作完全不同，前端据此决定是提示修改还是倒计时重试。
     */
    public static BizException rateLimited(String message) {
        return new BizException(ErrorCode.RATE_LIMITED, message);
    }
}
