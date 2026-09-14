package com.duoduo.common.web;

import com.duoduo.common.api.RequestIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求 ID 过滤器：为每个请求建立链路 ID，写入响应头与日志 MDC。
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} 保证它先于其他过滤器执行，
 * 后续所有日志（含异常）都带得上 requestId。
 *
 * <p>本类**不加 @Component**：它位于 {@code com.duoduo.common.web}，
 * 不在业务服务的扫描范围内，需由 {@link WebAutoConfiguration} 显式登记为 Bean。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && incoming.matches("[A-Za-z0-9._-]{6,64}"))
                ? incoming
                : RequestIdHolder.generate();

        RequestIdHolder.set(requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            RequestIdHolder.clear();
        }
    }
}
