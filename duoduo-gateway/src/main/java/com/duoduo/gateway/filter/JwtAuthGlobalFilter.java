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

    /**
     * 可信客户端地址（第五十七轮）：网关写入，供下游做限流键。
     *
     * <p>与 {@code X-Forwarded-For} 的关键区别：后者是**事实上的多跳代理链**，
     * 调用方可以在自己那一跳前面随便加内容；本头由网关**先清除同名字段再写入**，
     * 因此下游可以把它当事实用。凡是要当事实用的头，都必须遵循这个模式。
     */
    public static final String HEADER_X_REAL_IP = "X-Real-IP";
    /** 客户端同样能伪造的代理链头：进网关先清掉，避免下游误用 */
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

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
                    // 第五十七轮：客户端能自己写的 IP 头同样要剥离，再由网关写入可信值。
                    //
                    // 为什么必须在这里做：AI 导购的限流键按「客户端 IP」区分（未登录时），
                    // 而下游服务只能看到「从网关发来的请求」——它无法分辨这个 IP 头是网关写的
                    // 还是调用方随手编的。若网关不清理，对方每次请求换一个假 X-Forwarded-For
                    // 就等于无限额度，而 LLM 配额是按调用量付费的。
                    // 判据很简单：**凡是被下游当作事实使用的头，上游就必须保证它只能由自己写入**。
                    headers.remove(HEADER_X_FORWARDED_FOR);
                    headers.remove(HEADER_X_REAL_IP);
                });

        // 写入本次连接的真实对端地址（不看请求头，只看 TCP 连接）
        String peer = peerAddress(request);
        if (peer != null) {
            builder.header(HEADER_X_REAL_IP, peer);
        }

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

    /**
     * 本次请求的真实对端地址：**只看 TCP 连接，不看任何请求头**。
     *
     * <p>为什么不用 X-Forwarded-For 的第一段：那是调用方提供的数据。
     * 在本项目的部署形态下（网关是最外层入口），{@code getRemoteAddress()} 就是
     * 「谁连上了网关」这个客观事实 —— 客户端无法伪造成别人的地址。
     *
     * <p>将来若在网关前面再加一层负载均衡，这里会变成负载均衡的地址，
     * 届时正确做法是**只信任已知 LB 的 IP**、从它写入的链里取最后一跳，
     * 而不是退回「相信请求头」。这一点写在这里，避免以后有人图省事改回去。
     */
    private static String peerAddress(org.springframework.http.server.reactive.ServerHttpRequest request) {
        var remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
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
