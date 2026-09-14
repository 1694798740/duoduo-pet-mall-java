package com.duoduo.gateway.filter;

import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.gateway.config.GatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 前置校验过滤器。
 *
 * <p>设计要点：
 * <ul>
 *   <li>在校验通过后把身份写入内部请求头，业务服务无需重复解析令牌；
 *       同时**剥离客户端自带的同名请求头**，防止伪造身份越权</li>
 *   <li>白名单路径（登录、注册、商品浏览等）直接放行，但同样剥离伪造头</li>
 *   <li>校验失败直接返回 401，不把无效流量转发给后端（保护下游）</li>
 *   <li>令牌来源优先 Authorization: Bearer，其次 httpOnly Cookie，避免依赖 URL 传参</li>
 * </ul>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_PHONE = "X-User-Phone";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_REAL_NAME = "X-Real-Name-Verified";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final String COOKIE_ACCESS = "dd_at";

    private final JwtTokenService jwtTokenService;
    private final GatewaySecurityProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthGlobalFilter(JwtTokenService jwtTokenService, GatewaySecurityProperties properties) {
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String requestId = RequestIdHolder.getOrCreate();

        // 预检请求直接放行
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        String token = resolveToken(request);
        JwtTokenService.TokenPayload payload = jwtTokenService.parse(token, JwtTokenService.TYPE_ACCESS);

        boolean whitelisted = isWhitelisted(path);

        if (!payload.valid() && !whitelisted) {
            log.warn("网关拒绝未认证请求 requestId={} path={}", requestId, path);
            return unauthorized(exchange, "身份未认证或登录已过期");
        }

        // 无论是否白名单，都先剥离客户端可能伪造的身份头
        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_PHONE);
                    headers.remove(HEADER_USER_ROLE);
                    headers.remove(HEADER_REAL_NAME);
                });

        if (payload.valid()) {
            builder.header(HEADER_USER_ID, String.valueOf(payload.userId()))
                    .header(HEADER_USER_ROLE, payload.role() == null ? "USER" : payload.role())
                    .header(HEADER_REAL_NAME, String.valueOf(payload.realNameVerified()));
            if (payload.phone() != null) {
                builder.header(HEADER_USER_PHONE, payload.phone());
            }
        }

        if (request.getHeaders().getFirst(HEADER_REQUEST_ID) == null) {
            builder.header(HEADER_REQUEST_ID, requestId);
        }

        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    /** 令牌来源：Authorization: Bearer 优先，其次 httpOnly Cookie */
    private String resolveToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String value = authorization.substring(7).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (request.getCookies().containsKey(COOKIE_ACCESS)) {
            var cookie = request.getCookies().getFirst(COOKIE_ACCESS);
            if (cookie != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isWhitelisted(String path) {
        List<String> whitelist = properties.getWhitelist();
        if (whitelist == null) {
            return false;
        }
        return whitelist.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(ErrorCode.UNAUTHORIZED.httpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HEADER_REQUEST_ID, RequestIdHolder.getOrCreate());

        String body = """
                {"code":"UNAUTHORIZED","message":"%s","data":null,"requestId":"%s","timestamp":%d}
                """.formatted(message, RequestIdHolder.getOrCreate(), System.currentTimeMillis());

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 早于路由转发执行，保证无效流量不落到后端
        return -100;
    }
}
