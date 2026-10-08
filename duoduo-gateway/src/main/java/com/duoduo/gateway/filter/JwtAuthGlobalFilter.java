package com.duoduo.gateway.filter;

import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.security.ClientEntry;
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
import java.util.Map;

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
 *   <li><b>入口边界（E8）</b>：令牌里的 {@code ent} 声明了它是从哪个站点签发的，
 *       命中"跨端专属路径族"（{@code /api/admin/**}、{@code /api/seller/**}）时按入口放行/拒绝，
 *       不匹配返回 403。<b>没有 ent 声明的老令牌不受限制</b>（见 {@link ClientEntry}）——
 *       这是纵深防御，不是角色校验的替代品，边界写在 ClientEntry 的类注释里</li>
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

        /*
         * E8 · 入口边界：令牌里的 ent 说"我是从哪个站点签发的"，这里按它裁剪跨端专属路径。
         *
         * 三点刻意设计：
         *   · 判据取**令牌里的** ent，不取请求头 —— 令牌是签名的，请求头谁都能写；
         *     请求头的入口只用于"签发时告诉用户服务该写哪个 ent"，不参与放行判断。
         *   · ent 为 null（老令牌 / 小程序直连）时**不限制**，避免加字段即踢人下线。
         *   · 白名单路径同样受限：登录、注册这些入口本身就需要三端都能调，
         *     而它们本来就不在 entryRules 的两族里，所以自然不受影响。
         */
        if (payload.valid() && !isEntryAllowed(payload.entry(), path)) {
            log.warn("网关拒绝跨入口访问 requestId={} path={} entry={} role={}",
                    requestId, path, payload.entry(), payload.role());
            return forbidden(exchange, "当前登录入口无权访问该接口");
        }

        // 归一化入口标识：**只认三个合法字面量**，其余一律当作未声明（不让任意值往下游传）
        String requestEntry = ClientEntry.normalize(request.getHeaders().getFirst(ClientEntry.HEADER));

        // 无论是否白名单，都先剥离客户端可能伪造的身份头
        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_PHONE);
                    headers.remove(HEADER_USER_ROLE);
                    headers.remove(HEADER_REAL_NAME);
                    // 入口标识同样先剥离再写入归一化值：下游（用户服务签发令牌时）读的是这个头，
                    // 若不剥离，调用方可以塞任意字符串下去
                    headers.remove(ClientEntry.HEADER);
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

        // 写入归一化后的入口标识（仅在三站点之一登录时存在；直连网关时不会写入）
        if (requestEntry != null) {
            builder.header(ClientEntry.HEADER, requestEntry);
        }

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
     * E8 · 入口是否允许访问该路径。
     *
     * <p>规则是「只对**跨端专属路径族**收紧」：命中某条规则就按该规则的入口列表放行/拒绝，
     * 没有命中任何规则即放行。这样新增接口不需要同步维护这里的清单 ——
     * 只有"某族路径只属于某一端"这类**稳定事实**才需要写进配置。
     *
     * @param entry 令牌里的入口声明；{@code null} 表示未声明（老令牌 / 小程序直连）→ 不受限
     */
    private boolean isEntryAllowed(String entry, String path) {
        if (entry == null || !properties.isEntryScopeEnabled()) {
            return true;
        }
        Map<String, List<String>> rules = properties.getEntryRules();
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> rule : rules.entrySet()) {
            if (pathMatcher.match(rule.getKey(), path)) {
                return rule.getValue() != null && rule.getValue().contains(entry);
            }
        }
        return true;
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
        return reject(exchange, ErrorCode.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /** E8 · 入口不匹配（令牌合法、但这一端不该访问这个路径族）→ 403 而不是 401 */
    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return reject(exchange, ErrorCode.FORBIDDEN, "FORBIDDEN", message);
    }

    /**
     * 统一的拒绝响应。
     *
     * <p>401 与 403 的语义在这里必须分清：**401 = 你还没证明自己是谁**（前端据此走刷新/跳登录），
     * **403 = 你是谁我清楚，但这件事不该你做**（前端不该跳登录，跳了也没用）。
     * 把入口不匹配写成 401 会让前端陷入"刷新令牌 → 仍然 401 → 再刷新"的死循环。
     */
    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code, String codeName, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(code.httpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HEADER_REQUEST_ID, RequestIdHolder.getOrCreate());

        String body = """
                {"code":"%s","message":"%s","data":null,"requestId":"%s","timestamp":%d}
                """.formatted(codeName, message, RequestIdHolder.getOrCreate(), System.currentTimeMillis());

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 早于路由转发执行，保证无效流量不落到后端
        return -100;
    }
}
