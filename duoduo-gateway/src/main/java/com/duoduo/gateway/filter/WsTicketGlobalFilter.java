package com.duoduo.gateway.filter;

import com.duoduo.common.realtime.RealtimeTicketKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket 建连前的票据校验（2026-10-23）。
 *
 * <h2>为什么必须在网关拦，而不能只让社交域拒绝</h2>
 * 实测：社交域在握手阶段返回 401 时，网关**已经把 101 升级响应发给了客户端**，
 * 浏览器侧表现为"连上了，随后立刻断开"（1006）。对客户端来说这是最难判断的一种失败 ——
 * 它无法区分"票据错了"与"网络抖动"，只会不停重连。所以在这里先查一次票据：
 * 无效就直接 401 拒绝，升级根本不会发生。
 *
 * <h2>与社交域的分工</h2>
 * 网关只做**存在性检查**（不消费），社交域在握手时消费（一次性，取走即删）。
 * 拆开的原因：网关在建立下游连接失败时可能重试，如果网关消费掉票据，重试必然失败；
 * 而"存在性检查"是幂等的，重复执行没有副作用。
 *
 * <h2>Redis 不可用时放行</h2>
 * 票据校验依赖 Redis。Redis 抖动时选择放行、由社交域做最终判定（它同样会查 Redis，
 * 查不到就拒绝）—— 结果只会更严格，不会更宽松。这里不让网关成为单点。
 */
@Component
public class WsTicketGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(WsTicketGlobalFilter.class);
    private static final String WS_PREFIX = "/ws/";

    private final StringRedisTemplate redis;

    public WsTicketGlobalFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(WS_PREFIX)) {
            return chain.filter(exchange);
        }
        String ticket = exchange.getRequest().getQueryParams().getFirst("ticket");
        if (ticket == null || ticket.isBlank() || ticket.length() > 64) {
            return reject(exchange);
        }
        try {
            Boolean exists = redis.hasKey(RealtimeTicketKeys.PREFIX + ticket);
            if (!Boolean.TRUE.equals(exists)) {
                return reject(exchange);
            }
        } catch (Exception e) {
            // Redis 不可用：放行给社交域判定（它同样查 Redis，查不到即拒）
            log.warn("票据预检失败（Redis 不可用），交给社交域判定：{}", e.getMessage());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"UNAUTHORIZED\",\"message\":\"实时通道票据无效或已过期\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /** 早于路由过滤器执行；与 JWT 过滤器无顺序依赖（后者已跳过 /ws/**） */
    @Override
    public int getOrder() {
        return -80;
    }
}
