package com.duoduo.common.web;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理：把异常统一转换为 {@link ApiResponse}。
 *
 * <p>安全原则：客户端只看到规范化的错误结构，**绝不返回堆栈或内部细节**；
 * 5xx 的堆栈只写入服务端日志，并通过 requestId 关联。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("业务异常(5xx) requestId={} code={} msg={}", RequestIdHolder.get(), code, ex.getMessage(), ex);
        } else {
            log.warn("业务异常 requestId={} code={} msg={}", RequestIdHolder.get(), code, ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code.name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        return validationFailure(ex.getBindingResult().getFieldErrors());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        return validationFailure(ex.getBindingResult().getFieldErrors());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse(ErrorCode.VALIDATION_ERROR.defaultMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR.name(), detail));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        log.warn("请求体/参数不合法 requestId={} msg={}", RequestIdHolder.get(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST.name(), ErrorCode.BAD_REQUEST.defaultMessage()));
    }

    /**
     * 请求体格式不支持 → 415，而不是 500。
     *
     * <p>「客户端发错了 Content-Type」属于 4xx。落到兜底的 5xx 分支会带来两个后果：
     * 一是 5xx 告警被无关噪声淹没（真正的服务故障反而看不见），
     * 二是调用方拿到的提示是「服务内部错误」，完全指不出问题出在自己身上。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("请求体格式不支持 requestId={} msg={}", RequestIdHolder.get(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.httpStatus())
                .body(ApiResponse.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name(),
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未捕获异常 requestId={}", RequestIdHolder.get(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL.name(), ErrorCode.INTERNAL.defaultMessage()));
    }

    /**
     * 请求方法不支持 → 405，而不是 500。
     *
     * <p>与 {@link #handleUnsupportedMediaType} 是同一类问题、同一套理由：
     * 「路径存在但方法用错」（对只读端点发 POST、或反之）属 4xx，是<b>调用方的问题</b>。
     * 落到兜底分支有两个后果：5xx 告警被无关噪声淹没（真正的服务故障反而看不见），
     * 且调用方拿到「服务内部错误」，完全推不出问题在自己这一侧。
     *
     * <p>之所以此前一直是 500：{@code HttpRequestMethodNotSupportedException} 不在
     * 任何 {@code @ExceptionHandler} 的覆盖范围内，只能被 {@code Exception} 兜底接住。
     * 这类「框架异常漏配」不会报错、不会告警，只会在真实调用时表现为一个语义错误的码，
     * 属于最难主动发现的一类缺陷 —— 是接口冒烟测出来的。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持 requestId={} method={} msg={}",
                RequestIdHolder.get(), ex.getMethod(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.httpStatus())
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED.name(),
                        ErrorCode.METHOD_NOT_ALLOWED.defaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> validationFailure(List<FieldError> errors) {
        String detail = errors.stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_ERROR.defaultMessage());
        log.warn("参数校验失败 requestId={} detail={}", RequestIdHolder.get(), detail);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR.name(), detail));
    }
}
