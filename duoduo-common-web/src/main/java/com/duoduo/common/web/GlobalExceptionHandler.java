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
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
     * 查询参数 / 路径变量的**类型转换失败** → 400，而不是 500。
     *
     * <p>与 {@link #handleUnsupportedMediaType}（415）、{@link #handleMethodNotSupported}（405）是同一类
     * 「框架异常漏配」：{@code MethodArgumentTypeMismatchException} 此前不在任何 {@code @ExceptionHandler}
     * 的覆盖范围内，只能被 {@code Exception} 兜底接住 → 500。
     *
     * <p>后果不只是"码不漂亮"：同一个端点，参数**超范围**返回 400（业务规则校验），
     * 参数**写错类型**（{@code ?days=abc}、{@code /api/orders/xyz}）却返回 500 ——
     * 两者都是"调用方写错了"，调用方却无法据码判断该不该重试；
     * 而 5xx 告警会被这类噪声淹没，真正的服务故障反而看不见。
     *
     * <p>detail 只带**参数名与期望类型**，不带原始值、不带堆栈：
     * 参数名与类型本身就是接口契约的一部分（OpenAPI 里写着），原始值则可能含敏感信息。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expect = ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName();
        String detail = ex.getName() + " 需要 " + expect + " 类型";
        log.warn("参数类型不匹配 requestId={} {}", RequestIdHolder.get(), detail);
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST.name(), detail));
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

    /**
     * 没有匹配到任何处理器的路径 → 404，而不是 500。
     *
     * <p><b>实测触发场景（R59）</b>：给订单号路径加了 {@code {orderNo:\d{21}}} 形状约束后，
     * 一个 24 位的非法订单号不再匹配任何 {@code @GetMapping}，Spring 6.1 会去查静态资源、
     * 查不到就抛 {@code NoResourceFoundException}，被 {@code Exception} 兜底分支接住 → 500。
     * 后果有两层：用户输错一个号码被报成「服务内部错误」；
     * 更重要的一层是 <b>5xx 告警被这类噪声淹没</b> —— 真正的服务故障反而看不见。
     *
     * <p>与 {@link #handleMethodNotSupported} 同类：路径/方法对不上是<b>调用方的问题</b>，
     * 必须回 4xx。这类「框架异常漏配」不会报错、不会告警，只在真实调用时表现为语义错误的状态码。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("路径不存在 requestId={} msg={}", RequestIdHolder.get(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND.name(), ErrorCode.NOT_FOUND.defaultMessage()));
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
