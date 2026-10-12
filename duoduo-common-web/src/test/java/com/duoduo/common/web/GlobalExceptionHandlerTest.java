package com.duoduo.common.web;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理的「状态码语义」单测（第五十八轮 · R59 补充）。
 *
 * <p>为什么值得单独测：{@code @ExceptionHandler(Exception.class)} 这条兜底分支会把
 * <b>一切未显式声明的框架异常</b>都变成 500。框架每升级一次就可能多一种新异常
 * （Spring 6.1 的 {@link NoResourceFoundException} 就是这样进来的），
 * 而它漏配的表现只是「某个 4xx 场景回成了 5xx」—— 不报错、不告警，
 * 只在真实调用（或冒烟）时暴露，属于最难主动发现的一类缺陷。
 *
 * <p>本轮实测触发：订单号路径加 {@code {orderNo:\d{21}}} 形状约束后，
 * 24 位非法订单号不再匹配任何映射 → Spring 去查静态资源 → 抛
 * {@code NoResourceFoundException} → 被兜底成 500（冒烟「不存在的订单返回 404」转红）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 未匹配到任何处理器的路径回404而不是500() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleNoResource(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/orders/999"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }

    @Test
    void 方法不支持回405而不是500() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleMethodNotSupported(
                        new org.springframework.web.HttpRequestMethodNotSupportedException("POST"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
    }

    /** 只用来造一个 {@code MethodParameter}（{@code MethodArgumentTypeMismatchException} 的必填参数）。 */
    @SuppressWarnings("unused")
    private static void dummyEndpoint(Integer days) {
    }

    @Test
    void 参数类型不匹配回400而不是500() throws Exception {
        java.lang.reflect.Method method = GlobalExceptionHandlerTest.class
                .getDeclaredMethod("dummyEndpoint", Integer.class);
        org.springframework.core.MethodParameter param =
                new org.springframework.core.MethodParameter(method, 0);

        ResponseEntity<ApiResponse<Void>> res = handler.handleTypeMismatch(
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", Integer.class, "days", param, null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.name());
        // detail 要指得出是哪个参数（否则调用方拿到 400 也不知道改什么）
        assertThat(res.getBody().message()).contains("days").contains("Integer");
        // ★ 但**不得回显原始值**：它可能含敏感信息，而参数名+期望类型已足够定位
        assertThat(res.getBody().message()).doesNotContain("abc");
    }
}
