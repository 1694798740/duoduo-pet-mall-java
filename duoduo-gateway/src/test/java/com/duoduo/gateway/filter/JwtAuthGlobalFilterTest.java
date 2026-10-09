package com.duoduo.gateway.filter;

import com.duoduo.common.security.JwtTokenService;
import com.duoduo.gateway.config.GatewaySecurityProperties;
import com.duoduo.gateway.support.PermVersionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关鉴权过滤器单测（第五十八轮 · P2-10，网关域第一批单测）。
 *
 * <h2>为什么给「白名单」写测试</h2>
 * 网关白名单是<b>按路径匹配、不区分 HTTP 方法</b>的：把一条写路径的形状误加成通配，
 * 等于把「匿名直写入库」放出去，而这类改动在编译期与运行日志里都不报错，
 * 只表现为「接口突然谁都能调」。这里把<b>应当放行</b>与<b>应当拦下</b>的路径逐一钉死：
 * 将来谁把 {@code /api/shares/*} 改成 {@code /api/shares/**}，
 * 「三段路径不放行」这一条立刻转红。
 *
 * <h2>为什么同时断言「伪造身份头被剥离」</h2>
 * 下游把 {@code X-User-Id / X-Real-IP} 当作事实使用（限流键、用户身份）。
 * 只要网关漏剥一种，调用方就能自己编造身份或无限额度 ——
 * 这是「下游无法分辨谁写的头」的结构性风险，只能在上游守住。
 */
class JwtAuthGlobalFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtTokenService jwt =
            new JwtTokenService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
    private final GatewaySecurityProperties properties = new GatewaySecurityProperties();

    /**
     * E17 用的假"当前权限版本"。默认"未知"（= 与 Redis 里没有这个键同义）。
     *
     * <p>用假件而不是起一个 Redis：本组要钉的是<b>判定</b>（一致/不一致/查不到分别怎么处理），
     * 不是"能不能连上 Redis"。把连接细节混进来，测试会变成"环境对了才绿"。
     */
    private final Map<Long, Long> currentVersions = new HashMap<>();

    private final PermVersionChecker checker = userId ->
            Mono.just(Optional.ofNullable(currentVersions.get(userId)));

    private final JwtAuthGlobalFilter filter = new JwtAuthGlobalFilter(jwt, properties, checker);

    /** 跑一次过滤器：返回是否走到了下游，以及下游看到的请求头 */
    private Result run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        AtomicBoolean passed = new AtomicBoolean(false);
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            passed.set(true);
            seen.set(ex);
            return Mono.empty();
        }).block();
        return new Result(passed.get(), seen.get(), exchange);
    }

    private record Result(boolean passed, ServerWebExchange downstream, MockServerWebExchange original) {
    }

    private static MockServerHttpRequest.BaseBuilder<?> get(String path) {
        return MockServerHttpRequest.get(path);
    }

    @Test
    void 公开读路径免登放行() {
        assertThat(run(get("/api/products/123")).passed()).isTrue();
        assertThat(run(get("/api/products/search?keyword=猫")).passed()).isTrue();
        assertThat(run(get("/api/listings/search")).passed()).isTrue();
        assertThat(run(get("/api/listings/710000000000000001")).passed()).isTrue();
        assertThat(run(get("/api/shares/feed")).passed()).isTrue();
        assertThat(run(get("/api/home/banners")).passed()).isTrue();
    }

    @Test
    void 写路径不在白名单_匿名必须被拦() {
        // 发布闲置（单段）：白名单刻意只列了 /api/listings/search 与 /api/listings/*
        Result publish = run(MockServerHttpRequest.post("/api/listings"));
        assertThat(publish.passed()).isFalse();
        assertThat(publish.original().getResponse().getStatusCode().value()).isEqualTo(401);

        // 我的发布 / 上下架（三段）
        assertThat(run(get("/api/listings/mine/list")).passed()).isFalse();
        assertThat(run(MockServerHttpRequest.method(HttpMethod.PATCH, "/api/listings/123/status")).passed())
                .isFalse();
    }

    @Test
    void 三段路径不会被两段通配连带放行() {
        // 分享评论：列表走 ?shareId= 查询参数，发表是 POST /api/shares/{id}/comments。
        // 若白名单写成 /api/shares/**，发表评论就成了匿名可调 —— 这条断言守的就是这个形状。
        assertThat(run(MockServerHttpRequest.post("/api/shares/123/comments")).passed()).isFalse();
        assertThat(run(MockServerHttpRequest.post("/api/shares/123/like")).passed()).isFalse();
    }

    @Test
    void 微信绑定必须带登录态_不进白名单() {
        assertThat(run(MockServerHttpRequest.post("/api/auth/wechat/bind")).passed()).isFalse();
        assertThat(run(MockServerHttpRequest.post("/api/auth/wechat/unbind")).passed()).isFalse();
        // 登录本身放行
        assertThat(run(MockServerHttpRequest.post("/api/auth/wechat")).passed()).isTrue();
    }

    @Test
    void 伪造的身份头与IP头一律剥离() {
        Result result = run(get("/api/products/123")
                .header(JwtAuthGlobalFilter.HEADER_USER_ID, "710000000000000001")
                .header(JwtAuthGlobalFilter.HEADER_USER_ROLE, "ADMIN")
                .header(JwtAuthGlobalFilter.HEADER_X_FORWARDED_FOR, "1.2.3.4"));

        assertThat(result.passed()).isTrue();
        // 未登录却自称 ADMIN：下游绝不能看到这个头
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_USER_ID)).isNull();
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_USER_ROLE)).isNull();
        // 伪造的代理链头必须被清掉（AI 限流键依赖它）
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_X_FORWARDED_FOR)).isNull();
    }

    @Test
    void 合法令牌时写入可信身份头() {
        String token = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13900000001", "SELLER", true));

        Result result = run(get("/api/orders").header("Authorization", "Bearer " + token));

        assertThat(result.passed()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_USER_ID)).isEqualTo("710000000000000001");
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_USER_ROLE)).isEqualTo("SELLER");
    }

    @Test
    void 伪造令牌即使落在白名单外也进不去() {
        Result result = run(get("/api/orders").header("Authorization", "Bearer not-a-real-token"));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void 预检请求直接放行() {
        assertThat(run(MockServerHttpRequest.options("/api/orders")).passed()).isTrue();
    }

    // ================= E8 · 入口边界 =================

    @Test
    void 商城入口的令牌调后台管理面被拒为403() {
        // mall 站点签发的**运营账号**令牌：ent=mall。就算角色真是 OPERATOR
        // （服务端角色校验本会放行），/api/admin/** 属后台专属路径族，
        // 网关在它之前就挡下 —— 403 而不是 401：身份清楚，只是这一端不该调它
        String token = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13684295254", "OPERATOR", true),
                "mall");

        Result result = run(get("/api/admin/users").header("Authorization", "Bearer " + token));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void 商城入口的令牌调商家经营面被拒为403() {
        String token = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13900000001", "USER", true), "mall");

        Result result = run(get("/api/seller/orders").header("Authorization", "Bearer " + token));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void 各自入口的令牌访问本端专属路径放行() {
        String adminToken = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13684295254", "OPERATOR", true), "admin");
        String merchantToken = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000002L, "13900000002", "SELLER", true), "merchant");

        assertThat(run(get("/api/admin/users").header("Authorization", "Bearer " + adminToken)).passed()).isTrue();
        assertThat(run(get("/api/seller/orders").header("Authorization", "Bearer " + merchantToken)).passed()).isTrue();
        // 非专属路径（普通业务接口）对所有入口开放
        assertThat(run(get("/api/orders").header("Authorization", "Bearer " + merchantToken)).passed()).isTrue();
    }

    @Test
    void 未声明入口的令牌不受入口限制_向后兼容() {
        // 老令牌（加 ent 字段之前签发的）与小程序直连网关签发的令牌都没有 ent。
        // 若把"无声明"一律当非法，等于上线当天把所有在线用户与小程序一起踢下线。
        String legacyToken = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13684295254", "OPERATOR", true));

        assertThat(run(get("/api/admin/users").header("Authorization", "Bearer " + legacyToken)).passed())
                .isTrue();
    }

    @Test
    void 未声明入口的令牌仍要过认证这一关() {
        // "不受入口限制"只指入口维度 —— 令牌无效照样 401，别把向后兼容理解成免检
        Result result = run(get("/api/admin/users").header("Authorization", "Bearer not-a-real-token"));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void 客户端伪造的入口头会被剥离而非透传() {
        // 网关剥离后只回写三个合法字面量之一；胡乱编的值到不了下游
        Result result = run(get("/api/products/123")
                .header(com.duoduo.common.security.ClientEntry.HEADER, "cookie: admin=1; evil"));

        assertThat(result.passed()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders()
                .getFirst(com.duoduo.common.security.ClientEntry.HEADER)).isNull();
    }

    @Test
    void 合法入口头归一化后透传_大小写不符一律视为未声明() {
        // 大写 MALL 不在合法字面量里 → 归一化为 null → 不透传。
        // 刻意不做"宽容解析"：入口标识必须是封闭集合，任何"尽量理解意图"的解析
        // 都会让这个集合慢慢变成开放集合
        Result upper = run(get("/api/products/123").header(com.duoduo.common.security.ClientEntry.HEADER, "MALL"));
        assertThat(upper.downstream().getRequest().getHeaders()
                .getFirst(com.duoduo.common.security.ClientEntry.HEADER)).isNull();

        Result ok = run(get("/api/products/123").header(com.duoduo.common.security.ClientEntry.HEADER, "mall"));
        assertThat(ok.downstream().getRequest().getHeaders()
                .getFirst(com.duoduo.common.security.ClientEntry.HEADER)).isEqualTo("mall");
    }

    @Test
    void 关闭入口裁剪开关后跨端访问放行() {
        GatewaySecurityProperties props = new GatewaySecurityProperties();
        props.setEntryScopeEnabled(false);
        JwtAuthGlobalFilter relaxed = new JwtAuthGlobalFilter(jwt, props, checker);

        String token = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(710000000000000001L, "13900000001", "USER", true), "mall");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                get("/api/admin/users").header("Authorization", "Bearer " + token).build());
        AtomicBoolean passed = new AtomicBoolean(false);
        relaxed.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed.get()).isTrue();
    }

    @Test
    void 刷新令牌携带入口且老令牌继承为空() {
        // refresh 令牌也带 ent：刷新一次就把入口洗掉 = 字段形同虚设
        String refresh = jwt.createRefreshToken(710000000000000001L, "jti-entry-1", "admin");
        var payload = jwt.parse(refresh, JwtTokenService.TYPE_REFRESH);
        assertThat(payload.valid()).isTrue();
        assertThat(payload.entry()).isEqualTo("admin");

        // 老刷新令牌（无 ent）→ 继承为 null（不受限，向后兼容）
        var legacy = jwt.parse(jwt.createRefreshToken(710000000000000001L, "jti-entry-2"), JwtTokenService.TYPE_REFRESH);
        assertThat(legacy.valid()).isTrue();
        assertThat(legacy.entry()).isNull();
    }

    // ================= E17 · 权限版本（角色变更即时生效） =================

    private static final long SHOP_USER = 710000000000000009L;

    /** 造一枚带权限版本的令牌（真实签发路径走的就是这个重载） */
    private String tokenWithVersion(String role, long permVersion) {
        return jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(SHOP_USER, "13900000009", role, true), null, permVersion);
    }

    @Test
    void 令牌版本与当前版本一致时放行() {
        currentVersions.put(SHOP_USER, 3L);

        Result result = run(get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void 令牌版本落后时拒绝为401而不是403() {
        // 场景：运营刚把某人的角色改了 → 库里版本变成 4，对方手上的令牌还是 3。
        // 期望 401 而不是 403：403 的意思是"你是谁我清楚、这事不该你做"，
        // 前端不会去刷新令牌；而这里需要的是"换一张身份凭证"（刷新后就是新角色）。
        // 写成 403 的直接后果是用户必须手动重新登录 —— 正好丢掉 E17 要的"无需重登"。
        currentVersions.put(SHOP_USER, 4L);

        Result result = run(get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(401);
        // code 单独区分：401 的默认文案是"登录已过期"，容易被当成账号有问题；
        // 排障时需要一眼分清"权限被改了"和"令牌真过期了"
        assertThat(result.original().getResponse().getBodyAsString().block())
                .contains("PERM_VERSION_STALE");
    }

    @Test
    void 令牌版本高于当前版本同样拒绝() {
        // 反向也不能放过：副本落后（写失败 / 被人删了）时会出现"令牌比当前新"。
        // 若只判"令牌更旧"才算过期，这种情况就会被静默放行 ——
        // 而它同样是"版本对不上"，判据必须是**相等才放行**，不是"新就不管"
        currentVersions.put(SHOP_USER, 2L);

        Result result = run(get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)));

        assertThat(result.passed()).isFalse();
        assertThat(result.original().getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void 查不到当前版本时放行_可用性优先() {
        // 与 Redis 里没有这个键 / Redis 整个不可用同一条路。
        // 若这里改成拒绝，一次缓存抖动就等于"全站已登录用户集体掉线"；
        // 放行的代价只是退回 E17 之前的行为（旧令牌最多再多活 15 分钟）
        assertThat(run(get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)))
                .passed()).isTrue();
    }

    @Test
    void 老令牌不带版本时不校验_兼容窗口() {
        // E17 上线前签发的令牌（或刻意用不声明版本的签发口）没有 pv。
        // 不能因为"没有声明"就一律拒绝 —— 那等于上线当天把所有在线用户踢下线。
        currentVersions.put(SHOP_USER, 99L); // 就算当前版本差得很远，无声明也不参与判定

        String legacy = jwt.createAccessToken(
                new com.duoduo.common.context.LoginUser(SHOP_USER, "13900000009", "OPERATOR", true));

        assertThat(run(get("/api/orders").header("Authorization", "Bearer " + legacy)).passed()).isTrue();
        assertThat(jwt.parse(legacy, JwtTokenService.TYPE_ACCESS).permVersion()).isNull();
    }

    @Test
    void 关闭版本校验开关后放行() {
        GatewaySecurityProperties props = new GatewaySecurityProperties();
        props.setPermVersionEnabled(false);
        JwtAuthGlobalFilter relaxed = new JwtAuthGlobalFilter(jwt, props, checker);

        currentVersions.put(SHOP_USER, 4L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)).build());
        AtomicBoolean passed = new AtomicBoolean(false);
        relaxed.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        // 关掉后行为退回"等令牌自然过期"，但角色判权仍在（E16 的权限点不受影响）
        assertThat(passed.get()).isTrue();
    }

    @Test
    void 版本校验不改变原有放行路径的请求头_不重复执行下游() {
        // 版本校验是异步分支，很容易写出"先 chain.filter 一次、再在 flatMap 里又走一次"
        // 的双执行缺陷（第二次会因为 body 已被消费而报错，或者被下游当成两次请求）。
        // 这里用计数器直接钉住"下游只被调用一次"
        currentVersions.put(SHOP_USER, 3L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                get("/api/orders").header("Authorization", "Bearer " + tokenWithVersion("OPERATOR", 3)).build());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            calls.incrementAndGet();
            seen.set(ex);
            return Mono.empty();
        }).block();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get().getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.HEADER_USER_ROLE)).isEqualTo("OPERATOR");
    }
}
