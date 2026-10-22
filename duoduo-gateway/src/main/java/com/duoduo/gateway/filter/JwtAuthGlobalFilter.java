package com.duoduo.gateway.filter;

import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.security.ClientEntry;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.gateway.config.GatewaySecurityProperties;
import com.duoduo.gateway.support.PermVersionChecker;
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
 *   <li><b>权限版本（E17）</b>：令牌里的 {@code pv} 与 Redis 里的当前版本不一致 → 401，
 *       让"角色已改、旧令牌还在用"的窗口从 15 分钟收敛到<b>下一个请求</b>。
 *       老令牌（无 pv）不校验；Redis 查不到放行 —— 判据与取舍见
 *       {@link com.duoduo.common.security.perm.PermVersion} 与 {@code PermVersionChecker}</li>
 *   <li><b>租户标识（E20）</b>：令牌里的 {@code tid} → 请求头 {@code X-Tenant-Id}，
 *       供各服务的数据层追加行级过滤条件。**没有声明就不写这个头**（而不是写 0），
 *       下游据此"不追加条件" —— 与 ent/pv 同样的向后兼容姿势，且降级方向恰好等于 E20 之前的行为</li>
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
     * E20 · 租户标识（店铺主体 userId），由令牌里的 {@code tid} 转写。
     *
     * <p>★ 刻意**引用 duoduo-common 里的常量**，而不是在这里再写一遍字面量：
     * 这个头由网关写、由各服务的数据层拦截器读，两边各写一份的话，
     * 任何一次拼写不一致（{@code X-Tenant-Id} vs {@code X-TenantID}）**都不会报错** ——
     * 只会表现为"隔离上线了但没生效"。凡跨进程边界的键名，全项目只允许有一个定义。
     * （既有的四个头历史各自写在两边，属于 E8 之前的状态；新头一律走共享常量。）
     */
    public static final String HEADER_TENANT_ID = com.duoduo.common.tenant.TenantTables.HEADER;

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

    /**
     * E21 · 受 IP 白名单约束的路径族。
     *
     * <p>只收窄 {@code /api/admin/**}：平台后台是"一个人能影响全站"的地方，
     * 而商家端 / 买家端的用户遍布各地，做 IP 限制只会把正常用户挡在门外。
     * 这与 {@code entryRules} 里那条 {@code /api/admin/**} 是**同一个边界、不同的收窄维度**
     * （一个按入口、一个按来源地址），两者独立生效、互不替代。
     */
    private static final String ADMIN_PATH_PATTERN = "/api/admin/**";

    private final JwtTokenService jwtTokenService;
    private final GatewaySecurityProperties properties;
    private final PermVersionChecker permVersionChecker;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthGlobalFilter(JwtTokenService jwtTokenService,
                               GatewaySecurityProperties properties,
                               PermVersionChecker permVersionChecker) {
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
        this.permVersionChecker = permVersionChecker;
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

        // 入口标识必须在取令牌之前解析：三个站点各用一套 Cookie 名
        // （dd_at / dd_at_m / dd_at_a）。Cookie 不区分端口，共用名字会让
        // localhost:8088 与 localhost:8091 互相覆盖登录态（2026-10-21 修复）。
        String requestEntry = ClientEntry.normalize(request.getHeaders().getFirst(ClientEntry.HEADER));
        String token = resolveToken(request, requestEntry);
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

        // 入口标识已在取令牌前归一化（见上方）：只认三个合法字面量，其余当作未声明。

        // 无论是否白名单，都先剥离客户端可能伪造的身份头
        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_PHONE);
                    headers.remove(HEADER_USER_ROLE);
                    headers.remove(HEADER_REAL_NAME);
                    // E20：客户端可能自己编一个 X-Tenant-Id 冒充别的店铺，必须先剥离，
                    // 再由令牌里的 tid 写入可信值（与 X-Real-IP 同一条纪律）。
                    headers.remove(HEADER_TENANT_ID);
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

        /*
         * E21 · 平台后台 IP 白名单（默认关闭）。
         *
         * 三个刻意的选择：
         *   · **只看 TCP 对端**（peerAddress），不看 X-Forwarded-For —— 后者是调用方自己能写的。
         *     这与下一行写入 X-Real-IP 用的是同一个事实来源，不引入第二份判断口径。
         *   · **默认关闭**：打开它是"收紧"，而收紧方向配错的后果是"所有人进不了后台"。
         *     必须由运维显式打开并配好名单，而不是靠默认值。
         *   · **开关打开但名单为空 = 拒绝**（失败关闭）。把空名单当放行，
         *     会让一次漏配静默地什么都不拦。
         */
        if (!isAdminIpAllowed(path, peer)) {
            log.warn("网关拒绝不在白名单内的后台访问 requestId={} path={} peer={}", requestId, path, peer);
            return adminIpDenied(exchange);
        }

        if (payload.valid()) {
            builder.header(HEADER_USER_ID, String.valueOf(payload.userId()))
                    .header(HEADER_USER_ROLE, payload.role() == null ? "USER" : payload.role())
                    .header(HEADER_REAL_NAME, String.valueOf(payload.realNameVerified()));
            if (payload.phone() != null) {
                builder.header(HEADER_USER_PHONE, payload.phone());
            }
            /*
             * E20 · 租户标识：令牌里声明的 tid 在此转成下游可信的头。
             *
             * 三个刻意的选择：
             *   · **取令牌里的，不取请求头里的** —— 与 ent 同一条理由：令牌是签名的。
             *   · **tid 为空时不写这个头**（而不是写 "0"）：空 = "不属于任何店铺"
             *     （买家 / 运营 / 管理员 / 老令牌），下游据此**不追加**租户条件；
             *     写 0 会被下游理解为"平台自营"，那是**权限最宽的桶** ——
             *     买家所有查询都会被加上 seller_id = 0，结果不是"看得更多"而是"什么也看不到"，
             *     一次静默的功能故障，且看起来像隔离生效了。
             *   · 与 X-Real-IP 一样，**先剥离同名字段再写入**（见 builder 前面的 headers.remove）。
             */
            if (payload.tenantId() != null) {
                builder.header(HEADER_TENANT_ID, String.valueOf(payload.tenantId()));
            }
        }

        if (request.getHeaders().getFirst(HEADER_REQUEST_ID) == null) {
            builder.header(HEADER_REQUEST_ID, requestId);
        }

        ServerHttpRequest mutated = builder.build();

        /*
         * E17 · 权限版本校验：令牌里带的版本 vs 该用户当前版本，不一致即 401。
         *
         * 触发条件是"令牌**声明了**版本"（pv 非空）。老令牌没有这个声明 → 不校验，
         * 让它活到自然过期 —— 与 ent 的向后兼容同一姿势：上线时线上有活跃会话，
         * 不能因为"没有声明"就把人踢下线。这也让本判断在兼容窗口内是**零成本**的
         * （连 Redis 都不读）。
         *
         * 三种情形分别怎么处理，是这段代码的全部内容，逐条写下来免得日后被"简化"掉：
         *   · 查不到当前版本（Redis 不可用 / 键过期 / 值不是数字）→ **放行**。
         *     一次缓存抖动不该等于"全站已登录用户集体掉线"；代价只是退回 E17 之前的行为。
         *   · 查到了且相等 → 放行。
         *   · 查到了且不等 → 401（不是 403）。为什么是 401：**身份本身过期了**，
         *     而不是"你是谁我清楚但这事不该你做"。前端对 401 的处理正是"刷新令牌后重试"，
         *     刷新会从库里现读最新角色，于是这次刷新就把人带到新权限上，无需重新登录。
         *     若写成 403，前端只会报"没有权限"而不会去刷新 —— 用户就得自己重登。
         */
        if (shouldCheckPermVersion(payload)) {
            Mono<Boolean> allowed = permVersionChecker.currentVersion(payload.userId())
                    .map(current -> !current.isPresent() || current.get().equals(payload.permVersion()))
                    // 契约上实现不会返回空 Mono；这里仍给一个兜底值，避免"实现改了、请求挂住"：
                    // 注意**不能**用 switchIfEmpty(chain.filter(...)) —— chain.filter 返回的
                    // Mono<Void> 本身就不 emit 任何元素，会被 switchIfEmpty 当成"源是空的"从而**再执行一次**，
                    // 变成下游收到两次请求。
                    .defaultIfEmpty(true);

            return allowed.flatMap(ok -> {
                if (!ok) {
                    log.warn("网关拒绝权限版本过期的令牌 requestId={} userId={} tokenVersion={} path={}",
                            requestId, payload.userId(), payload.permVersion(), path);
                    return permVersionStale(exchange);
                }
                return chain.filter(exchange.mutate().request(mutated).build());
            });
        }

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 是否需要对这枚令牌做版本校验。
     *
     * <p>三道门缺一不可：令牌有效、令牌声明了 pv、开关打开。
     * 把判定抽成一个方法而不是内联，是为了让单测能直接钉住"什么时候**不**校验"——
     * 在校验逻辑上，"少校验"和"校验错"是同样严重的缺陷，但只有前者难以从外部察觉。
     */
    private boolean shouldCheckPermVersion(JwtTokenService.TokenPayload payload) {
        return payload.valid()
                && payload.permVersion() != null
                && properties.isPermVersionEnabled();
    }

    /**
     * 令牌来源：Authorization: Bearer 优先，其次 httpOnly Cookie。
     *
     * <p>Cookie 名按入口区分（{@code dd_at} / {@code dd_at_m} / {@code dd_at_a}）：
     * Cookie 按主机名隔离但<b>不区分端口</b>，商城（:8088）与商家端（:8091）若共用
     * {@code dd_at}，登录一端会把另一端顶掉 —— 表现为「商家登录后个人中心是买家」。
     *
     * <p>商家 / 后台**不回退**基础名：否则那边没登录时会拿商城会话顶上，正是要消灭的串台。
     * 商城与未声明入口（小程序带 Bearer，不走这里）保留回退，兼容改造前签发的会话。
     */
    private String resolveToken(ServerHttpRequest request, String entry) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String value = authorization.substring(7).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        String scoped = cookieValue(request, cookieName(entry));
        if (scoped != null) {
            return scoped;
        }
        if (entry == null || ClientEntry.MALL.equals(entry)) {
            return cookieValue(request, COOKIE_ACCESS);
        }
        return null;
    }

    private String cookieName(String entry) {
        if (ClientEntry.MERCHANT.equals(entry)) {
            return COOKIE_ACCESS + "_m";
        }
        if (ClientEntry.ADMIN.equals(entry)) {
            return COOKIE_ACCESS + "_a";
        }
        return COOKIE_ACCESS;
    }

    private String cookieValue(ServerHttpRequest request, String name) {
        var cookie = request.getCookies().getFirst(name);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
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
     * E21 · 这次请求的来源地址能否访问平台后台。
     *
     * <p>判定顺序刻意如此：**先看开关、再看路径、最后才比对名单** ——
     * 于是"未启用"与"非后台路径"都在前两步就返回，日常请求不产生任何额外开销；
     * 只有真正命中后台路径且开关打开时才去逐个比对规则。
     */
    private boolean isAdminIpAllowed(String path, String peer) {
        if (!properties.isAdminIpWhitelistEnabled()) {
            return true;
        }
        if (!pathMatcher.match(ADMIN_PATH_PATTERN, path)) {
            return true;
        }
        List<String> whitelist = properties.getAdminIpWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            // 开关打开但名单为空 → 拒绝。理由见 GatewaySecurityProperties#adminIpWhitelist
            return false;
        }
        if (peer == null) {
            // 取不到对端地址（连接已断开等）→ 同样拒绝：宁可误拒一次，
            // 也不放一个来源不明的请求进后台。
            return false;
        }
        for (String rule : whitelist) {
            if (matchesIp(rule, peer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规则与地址是否匹配。支持精确地址、IPv4 CIDR、以及 {@code 192.168.1.*} 形式的前缀通配。
     *
     * <p><b>刻意不用 {@code InetAddress.getByName}</b>：那个方法对"不是 IP 的字符串"会去做 DNS 解析。
     * 在认证链路上引入计划外的网络查询有两个坏处：延迟受外部 DNS 影响，
     * 且一个被污染的配置值能把网关变成 DNS 探测跳板。这里只认字面量 ——
     * IPv4 按四段数字解析，其余形态一律退化为字符串精确比对。
     */
    private static boolean matchesIp(String rule, String ip) {
        if (rule == null || rule.isBlank() || ip == null) {
            return false;
        }
        String pattern = rule.trim();
        if (pattern.equals(ip)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return ip.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        int slash = pattern.indexOf('/');
        if (slash < 0) {
            return false;
        }
        return matchesIpv4Cidr(pattern.substring(0, slash), pattern.substring(slash + 1), ip);
    }

    private static boolean matchesIpv4Cidr(String network, String prefixRaw, String ip) {
        byte[] net = ipv4Bytes(network);
        byte[] addr = ipv4Bytes(ip);
        if (net == null || addr == null) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(prefixRaw);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (net[i] != addr[i]) {
                return false;
            }
        }
        int remainingBits = prefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
    }

    /** 只解析 IPv4 字面量（四段、每段 0-255）；不是这个形状就返回 null，绝不触发 DNS 查询 */
    private static byte[] ipv4Bytes(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty()) {
                return null;
            }
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
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

    /**
     * E17 · 权限版本过期（令牌合法、但它是旧权限的快照）→ 401，但 code 单独区分。
     *
     * <p>为什么 HTTP 状态必须是 401 而不能是 403：401 才是"你的身份凭证需要换一张"，
     * 前端据此自动刷新并重放请求；403 是"你是谁我清楚，但这事你不该做"，
     * 前端不会刷新 —— 结果就是用户得手动重新登录，正好丢掉 E17 想要的"无需重登"。
     *
     * <p>为什么还要一个独立的 code：401 的文案默认是"登录已过期"，容易被误当成
     * "账号有问题"；带 {@code PERM_VERSION_STALE} 之后，排障时一眼能分清
     * "权限被改了"和"令牌真过期了"——这两件事的处置完全不同。
     */
    private Mono<Void> permVersionStale(ServerWebExchange exchange) {
        return reject(exchange, ErrorCode.UNAUTHORIZED, "PERM_VERSION_STALE",
                "账号权限已变更，请刷新登录态后重试");
    }

    /** E8 · 入口不匹配（令牌合法、但这一端不该访问这个路径族）→ 403 而不是 401 */
    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return reject(exchange, ErrorCode.FORBIDDEN, "FORBIDDEN", message);
    }

    /**
     * E21 · 来源地址不在后台白名单内 → 403。
     *
     * <p>为什么是 403 而不是 401：调用方的身份凭证可能完全合法，
     * 只是"从哪个网络过来"不符合要求。写成 401 会让前端触发刷新令牌的逻辑，
     * 刷一百次也不会变（问题不在令牌上），只是把一次清晰的拒绝变成一串无意义的刷新请求。
     *
     * <p>独立 code {@code ADMIN_IP_DENIED}：排障时要能一眼分清
     * "你没权限"与"你这个网络位置进不来" —— 后者的处置是找运维加白名单，完全不是一回事。
     */
    private Mono<Void> adminIpDenied(ServerWebExchange exchange) {
        return reject(exchange, ErrorCode.FORBIDDEN, "ADMIN_IP_DENIED",
                "当前网络位置不在后台访问白名单内");
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
