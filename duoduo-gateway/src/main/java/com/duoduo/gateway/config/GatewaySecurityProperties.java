package com.duoduo.gateway.config;

import com.duoduo.common.security.ClientEntry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关安全与 JWT 配置。
 * 密钥通过环境变量注入（DUODUO_JWT_SECRET），默认值仅供本地开发，
 * 生产必须由配置中心下发且不得进入代码库。
 */
@Configuration
@ConfigurationProperties(prefix = "duoduo.security")
public class GatewaySecurityProperties {

    /** HMAC 密钥，长度必须 ≥ 32 字节（256 bit） */
    private String jwtSecret = "duoduo-local-dev-secret-please-change-in-production-32b";

    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshTokenTtl = Duration.ofDays(7);

    /** 免认证路径（Ant 风格），其余路径必须携带合法令牌 */
    private List<String> whitelist = List.of(
            "/api/auth/login",
            // A14 修复 · 移动端（小程序）令牌下发通道：小程序没有 Cookie 语义，
            // 令牌必须随响应体返回。功能与 /api/auth/login 完全等价（同一 AuthService），
            // 只是响应体从「扁平 UserView」换成「UserView + accessToken/refreshToken」，
            // 因此信任级别相同、同样免登。必须**逐条精确列出**（Ant 匹配下
            // /api/auth/login 不会覆盖 /api/auth/login/token）。
            "/api/auth/login/token",
            "/api/auth/register",
            "/api/auth/sms/**",
            "/api/auth/refresh",
            // 移动端刷新：refreshToken 由端侧请求体传入，此时 access token 已过期，
            // 因此与 /api/auth/refresh 同级免登。
            "/api/auth/refresh/token",
            // 微信小程序登录（A13）：wx.login 的 code 换令牌，此时自然没有登录态。
            // A14 新增的 /api/auth/wechat/unbind 与 /api/auth/wechat/rebind **刻意不进白名单**：
            // 解绑/换绑是破坏性动作（解绑后该微信可被他人绑定），必须持有登录令牌才能做；
            // 白名单是精确路径匹配，/api/auth/wechat 不会连带放行它们。
            // 只放行 这**两条精确路径**（浏览器版 + 移动端令牌版）；
            // /api/auth/wechat/bind 是「登录态下绑定微信」，需要 Bearer 令牌，
            // 绝不能进白名单 —— 否则拿到任意令牌前缀即可把别人的微信绑到自己的账号上。
            "/api/auth/wechat",
            "/api/auth/wechat/token",
            "/api/products/**",
            "/api/categories/**",
            "/api/home/**",
            // C2C 闲置浏览：与商品浏览同性质（未登录先逛，才谈得上注册）。
            // **不能写 /api/listings/** ** —— 发布（POST /api/listings）、我的发布、上下架
            // 都挂在这棵子树下，通配会把三条写路径一起放行，等于匿名 UGC 直写入库。
            // 照分享圈（/api/shares/feed + /api/shares/*）的两段式精确列举：
            //   /api/listings/search —— 列表（两段，只读）
            //   /api/listings/*      —— 详情（两段，只读；AntPathMatcher 的 * 不跨 /）
            // 单段的 /api/listings 刻意<b>不在</b>白名单：那是发布端点。
            // 三段的 /api/listings/mine/list 与 /api/listings/{id}/status 也落不进 * ——
            // 前者是隐藏写路径，后者是写操作。
            "/api/listings/search",
            "/api/listings/*",
            "/api/meta/**",
            // 领券中心（A5）：营销落地页，未登录访客也应看到有什么券 —— 这正是它的作用。
            // 只放行 /api/coupons/templates 这一条**精确**只读路径：
            // 白名单按路径匹配、不区分方法，因此绝不能写成 /api/coupons/**——
            // 那会把「领券」「我的券包」「内部锁券/核销/解锁」一起放行，
            // 其中内部接口本该由服务间凭据保护，一次配置就变成线上越权洞。
            "/api/coupons/templates",
            // 商品评价是「公开可见的购物决策信息」：未登录也能看（否则首次访问者无法判断口碑）。
            // 白名单按路径而非方法匹配，因此这里**只放行只读的 /product/** 子路径**：
            // 发表评价 / 我的评价 / 追评都在 /api/reviews 与本路径之外，仍由网关强制登录。
            "/api/reviews/product/**",
            // 卖家信用分（A4）：同样是「购物决策信息」——买家下单前要看卖家靠不靠谱，
            // 与商品评价同性质，未登录访客也应看得到。只放行这条精确路径（读接口，无副作用）；
            // 刷新回写走 /api/reviews/internal/**，不经网关，且由履约域 InternalTokenFilter 校验凭据。
            "/api/reviews/seller/*/credit",
            // 分享圈（第四十三轮）：分享是「带用户评价的商品口碑内容」，与商品评价同性质 ——
            // 未登录也要能逛信息流、能打开好友转发来的分享落地页，强制登录等于把传播链掐断。
            // 只放行这两条**只读**路径（feed = 信息流，/* = 分享详情）；
            // POST /api/shares（发布分享）在路径之外，仍由网关强制登录。
            // 白名单按路径、不区分方法，而 feed 刻意用两段路径与发布路径（单段）分开 ——
            // 绝不能写成 /api/shares 或 /api/shares/**，那会把发布接口一起放行，
            // 匿名 UGC 直写入库；同时也不允许日后在单段路径上挂需登录的读接口。
            // AntPathMatcher 的 * 不跨 /，所以第四十四轮的 /{id}/like、/{id}/favorite
            // 与第四十六轮的 /{id}/repost 三类两段写路径都落不到这里，一律强制登录。
            "/api/shares/feed",
            "/api/shares/*",
            // 话题（第五十六轮）：热门话题榜（/api/shares/topics，一段）已被上面的
            // /api/shares/* 覆盖；话题聚合页是两段（/api/shares/topics/{name}），
            // 单独放行这一条 —— 必须写 /topics/* 而不是 /*/*：
            // 后者会把 /api/shares/{id}/edit、/delete、/report、/comments 四条**写路径**
            // 一起放成匿名可调。这是本文件里最需要逐字核对的一类判断。
            "/api/shares/topics/*",
            // 评论列表（第五十六轮）：GET /api/shares/comments?shareId=（一段）已被 /api/shares/* 覆盖。
            // 发表评论刻意留在两段路径 POST /api/shares/{id}/comments，
            // 点评赞与举报同理（/comments/{id}/like|report，三段）—— 都不落进白名单。
            // 若当初把列表写成 /{id}/comments，要放行它就得加 /api/shares/*/comments，
            // 而那会连带放行匿名 POST 评论。路径形状本身就是这里的防线。
            // AI 导购（A9）：购物咨询本身就是「还没决定买不买」的场景，
            // 未登录访客先问两句再决定注册，是这个入口存在的意义 —— 强制登录等于把导购废掉。
            // 只放行 chat（对话）与 capabilities（前端据此决定按钮是否可点）两条精确只读路径；
            // 白名单按路径、不区分方法，所以绝不能写成 /api/duoduo/**。
            // 「需登录才能做的操作」（下单/领券/加购）不由网关拦截，而是靠导购回复里的文案引导登录——
            // 它本身不写任何业务数据，故放行不构成越权面。
            "/api/duoduo/chat",
            "/api/duoduo/capabilities",
            // 限时秒杀（A14）：活动浏览与领券中心同性质 —— 未登录也要看到「正在抢什么」才有导流意义。
            // 只放行 activities 两级**只读**路径；POST /api/seckill/orders 在路径之外，仍由网关强制登录
            // （白名单按路径、不区分方法，所以绝不能写成 /api/seckill/**）。
            "/api/seckill/activities",
            "/api/seckill/activities/*",
            // 渠道回调来自渠道服务器，没有我们的登录态；安全靠验签 + 金额核对 + CAS 幂等
            "/api/payments/callback/**",
            // 沙箱收银台：扫码设备上没有买家登录态（现实中谁扫码谁付款）
            "/api/payments/sandbox/**",
            // 沙箱收银台页面（服务端渲染，与上一条同理由放行）
            "/sandbox/pay/**",
            // 图片公开读（A15）：<image>/<img> 标签发不出 Authorization 头，必须免登才能显示。
            // 文件名是服务端生成的 UUID（不可枚举、无业务语义），公开读不泄露归属信息。
            // 上传走的 /api/uploads 在白名单之外 —— 白名单按路径、不区分方法，
            // 把上传接口也放进来等于匿名 POST，磁盘就成了公共网盘。
            "/uploads/**",
            "/actuator/**",
            "/health/**"
    );

    // ---------------- E8 · 入口边界（按入口裁剪可访问路径） ----------------

    /**
     * 是否启用「按入口裁剪可访问路径」。默认开启；配置为 false 可整体退回（排障或回滚用）。
     *
     * <p>留这个开关的原因很直接：这是在**认证链路上**新增的一道判断，
     * 一旦判定写错就是把正常用户挡在门外。有一个「一行配置退回原行为」的兜底，
     * 出问题时的处置时间从"改代码 + 重新发版"降到"改配置 + 重启"。
     */
    private boolean entryScopeEnabled = true;

    // ---------------- E17 · 权限版本校验（角色变更即时生效） ----------------

    /**
     * 是否启用「权限版本校验」。
     *
     * <p>与 {@link #entryScopeEnabled} 同理留一个一行配置退回原行为的开关：
     * 这是在**认证链路上**新增的一道判断，判定写错的后果是"改完角色的人被挡在门外"，
     * 或者在 Redis 异常时表现怪异。有开关，处置时间就从"改代码 + 发版"降到"改配置 + 重启"。
     *
     * <p>关掉之后**不会**让权限变更失去保护：授权仍然由角色 + 权限点（E16）判定，
     * 只是变更的生效时间退回"等令牌自然过期（≤15 分钟）"。
     */
    private boolean permVersionEnabled = true;

    /** 路径族 → 允许访问该族路径的入口列表。
     *
     * <p><b>刻意用"只列专属族"的方式，而不是给每个入口列白名单</b>：
     * <ul>
     *   <li>本项目的接口面有 100+ 端点、由 7 个服务分担，用"入口白名单"穷举一遍，
     *       漏一条的后果是**该功能直接 403**，而且往往只在某个入口的某条路径上才暴露；</li>
     *   <li>而"跨端专属族"只有两组（后台管理面 {@code /api/admin/**}、商家经营面
     *       {@code /api/seller/**}），它们的归属是**确定且稳定**的 ——
     *       不会因为某个服务新增一个端点就变。</li>
     * </ul>
     * 换言之：这里收窄的是「**别端的专属面**」，而不是「本端能做什么」——
     * 后者由业务服务的角色与归属校验负责，不该在网关复制一份。
     *
     * <p>未列出的路径对所有入口放行（含未声明入口的令牌，见 {@code ClientEntry} 的向后兼容段）。
     */
    private Map<String, List<String>> entryRules = new LinkedHashMap<>(Map.of(
            // 平台运营管理面：只有平台管理后台站点能调。
            // 小程序/T 商城令牌即使带过来，也会在这里被挡下（角色校验是第二道，这里只是收窄）
            "/api/admin/**", List.of(ClientEntry.ADMIN),
            // 商家经营面：只有第三方商家平台站点能调。
            // 注：平台运营「代运营」需要从后台调商家接口 —— 那是 P3 的功能，
            // 届时把 ClientEntry.ADMIN 加进本列表即可（配置项，无需改代码逻辑）
            "/api/seller/**", List.of(ClientEntry.MERCHANT)
    ));

    @Bean
    public com.duoduo.common.security.JwtTokenService jwtTokenService() {
        return new com.duoduo.common.security.JwtTokenService(jwtSecret, accessTokenTtl, refreshTokenTtl);
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public boolean isEntryScopeEnabled() {
        return entryScopeEnabled;
    }

    public void setEntryScopeEnabled(boolean entryScopeEnabled) {
        this.entryScopeEnabled = entryScopeEnabled;
    }

    public Map<String, List<String>> getEntryRules() {
        return entryRules;
    }

    public void setEntryRules(Map<String, List<String>> entryRules) {
        this.entryRules = entryRules;
    }

    public boolean isPermVersionEnabled() {
        return permVersionEnabled;
    }

    public void setPermVersionEnabled(boolean permVersionEnabled) {
        this.permVersionEnabled = permVersionEnabled;
    }
}
