package com.duoduo.common.security;

import com.duoduo.common.context.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 令牌服务：签发 / 校验 access 与 refresh 令牌。
 *
 * <p>设计要点：
 * <ul>
 *   <li>access 令牌短时效（默认 15 分钟），只承载身份与角色，不承载可变业务状态</li>
 *   <li>refresh 令牌长时效（默认 7 天），携带 jti，服务端（Redis）保存并可吊销 → 支持强制下线</li>
 *   <li>两者 type 字段不同，避免 refresh 令牌被当作 access 令牌直接访问业务接口</li>
 *   <li>密钥长度必须 ≥ 256 bit（HS256 要求），启动时校验，配置错误立即失败</li>
 *   <li><b>入口标识 ent（E8）</b>：记录令牌是从哪个站点签发的，网关据此按入口裁剪可访问路径。
 *       <b>老令牌没有该声明 → 解析为 null → 不受入口限制</b>（见 {@link ClientEntry} 的"向后兼容"段：
 *       加字段时线上有活跃会话，不能因为"没有声明"就把人踢下线）</li>
 *   <li><b>权限版本 pv（E17）</b>：签发时的权限快照编号，网关据此判定"这枚令牌是不是旧权限"，
 *       让角色变更<b>下一个请求即生效</b>（而不是等 15 分钟自然过期）。
 *       口径（键名 / TTL / 放行规则）见 {@link com.duoduo.common.security.perm.PermVersion}；
 *       同样地，<b>没有该声明 = 老令牌 = 不校验</b></li>
 *   <li><b>租户标识 tid（E20）</b>：这枚令牌代表哪个店铺（店铺主体 userId），
 *       网关据此写入 {@code X-Tenant-Id}，各服务的数据层据此追加行级过滤条件。
 *       口径与"为什么放令牌里"见 {@link #CLAIM_TENANT}；<b>刷新令牌不带它</b>（换新令牌时重算）</li>
 *   <li><b>两步验证已满足 tfv（E21）</b>：<b>只写刷新令牌</b>，用于拦住"启用 2FA 之前签发的
 *       刷新令牌"。口径见 {@link #CLAIM_TWO_FACTOR_SATISFIED}</li>
 * </ul>
 *
 * <p>网关与服务端共用本类，保证签名逻辑只有一份实现。
 */
public class JwtTokenService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_PHONE = "phone";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_REAL_NAME = "rn";
    /** E8 · 入口标识（ClientEntry 的三个字面量之一） */
    private static final String CLAIM_ENTRY = "ent";
    /**
     * E17 · 权限版本：签发时的权限快照编号，网关据此判断"这枚令牌是不是旧权限"。
     *
     * <p>没写这个声明 = 老令牌（E17 上线之前签发的，或调用方刻意不声明）→
     * 网关<b>不做版本校验</b>，让它活到自然过期。与 {@code ent} 的向后兼容同一姿势：
     * 加字段时线上有活跃会话，不能因为"没有声明"就把人踢下线。
     */
    private static final String CLAIM_PERM_VERSION = "pv";
    /**
     * E20 · 租户标识：这枚令牌**代表哪个店铺**（= 店铺主体 userId，见
     * {@link com.duoduo.common.tenant.TenantContext}）。
     *
     * <p>为什么放在令牌里而不是让下游自己查：租户是"行级可见性"的过滤键，
     * 它必须是**服务端可信、请求内稳定**的值。让每个服务各自解析（Feign 查成员表）
     * 会把一个本可以只算一次的东西算五六次，且任何一处写错都表现为"某条链路没隔离"。
     * 放进签名令牌后，网关转发、下游读取，全链路同一份事实。
     *
     * <p><b>没有该声明 = 老令牌 / 不属于任何店铺（买家、运营、管理员）→ 不追加租户条件。</b>
     * 与 {@code ent} / {@code pv} 同一姿势：加字段时线上有活跃会话，
     * 不能因为"没有声明"就把人踢下线；而"不追加条件"恰好等于 E20 之前的行为，是安全的降级方向。
     *
     * <p><b>刷新令牌刻意不带 tid</b>（与 {@code pv} 同理，但与 {@code ent} 不同）：
     * 换新令牌时租户要**重新解析**一遍 —— 店员可能已经换了店铺、已被停用，
     * 沿用旧值会让"停用/移店"在最长 7 天内不生效。{@code ent} 必须继承是因为入口是
     * "你在哪个站点登录的"，换了反而把入口限制洗掉；租户是"你现在属于谁"，必须重算。
     */
    private static final String CLAIM_TENANT = "tid";
    /**
     * E21 · 「该账号的两步验证要求已满足」。
     *
     * <p><b>只写在刷新令牌上，不写在访问令牌上。</b>为什么：
     * 强制的判定点是"能不能换到新的访问令牌"，而不是"每一次请求"——
     * 已启用 2FA 的账号在登录时若没通过校验，**根本拿不到任何令牌**，
     * 因此访问令牌不需要再带一个"我算不算数"的声明（带了也只是同义反复，
     * 反而多一处可能与登录判定不一致的地方）。而刷新令牌**必须带**：
     * 它有效期 7 天，若不带这个声明，一枚"启用 2FA 之前签发"的刷新令牌
     * 就能在最长 7 天内无限换新令牌，把这道门整个绕开。
     *
     * <p>没写 = 老令牌（E21 之前签发的）→ 刷新时按"未满足"处理。
     * 这里**刻意不做向后兼容**（与 ent / pv 的处理相反）：放行无声明的老令牌
     * 等于"启用 2FA 之前签发的会话永远有效"，那 2FA 就形同虚设。
     * 代价是启用 2FA 后需重新登录一次 —— 而这正是启用它时应有的效果。
     */
    private static final String CLAIM_TWO_FACTOR_SATISFIED = "tfv";

    private final SecretKey secretKey;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtTokenService(String secret, Duration accessTtl, Duration refreshTtl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度不足 256 bit，请通过环境变量配置足够强度的密钥");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /** 兼容入口：不声明入口（等价于"不受入口限制"），旧调用点与既有单测无需改动 */
    public String createAccessToken(LoginUser user) {
        return createAccessToken(user, null);
    }

    /**
     * 签发访问令牌。
     *
     * @param entry 入口标识（{@link ClientEntry} 的三个字面量之一）；为 null 或不合法时不写入 ent 声明，
     *              该令牌因此**不受入口路径限制**（小程序直连网关、以及直连网关的脚本夹具都走这条路）
     */
    public String createAccessToken(LoginUser user, String entry) {
        return createAccessToken(user, entry, null);
    }

    /**
     * 签发访问令牌（E17：带权限版本）。
     *
     * <p>三个重载的关系要说清楚，否则很容易"顺手用了少一个参数的那个"而静默失去版本校验：
     * <ul>
     *   <li>{@link #createAccessToken(LoginUser)} / {@link #createAccessToken(LoginUser, String)} ——
     *       <b>刻意</b>不写 {@code pv}（等价于"老令牌"）。保留它们是为了两件事：
     *       网关侧单测需要构造老令牌，以及本项目之外（小程序、脚本夹具）的历史调用方不被强制改造。</li>
     *   <li>本方法与 4 参重载（E20 起）—— <b>真实签发路径必须走 4 参那个</b>。目前只有两处：
     *       {@code AuthService.issueTokens}（登录/注册/刷新）与
     *       {@code AuthService.renewAccessToken}（角色升级后重签）。两处都从库里现读版本，
     *       不接受调用方传入 —— 版本只有一个来源，不给第二份口径。</li>
     * </ul>
     *
     * @param permVersion 权限版本；{@code null} 时不写声明（该令牌不参与版本校验，向后兼容）
     */
    public String createAccessToken(LoginUser user, String entry, Long permVersion) {
        return createAccessToken(user, entry, permVersion, null);
    }

    /**
     * 签发访问令牌（E20：带租户标识）。
     *
     * <p>{@code tenantId} 的取值规则只有一条：<b>由服务端从库里的店铺归属现读</b>，
     * 绝不接受客户端传入。真实签发路径是 {@code AuthService.issueTokens} /
     * {@code renewAccessToken}，两处都从 {@code t_merchant} 归属解析。
     *
     * <p>解析结果与 {@link #CLAIM_TENANT} 的三种取值：
     * <ul>
     *   <li>{@code null} —— 不属于任何店铺（买家 / 运营 / 管理员）→ 不写声明 → 下游不追加条件；</li>
     *   <li>{@code 0} —— 平台自营。**是有效值**，写进声明；</li>
     *   <li>{@code > 0} —— 第三方店铺主体 userId。</li>
     * </ul>
     * 注意 0 与 null 的区别必须由调用方用 {@code Long} 表达，不能用 {@code long} 默认值兜 ——
     * 那会把"买家"写成"自营"，等于给买家打开自营数据的门（见
     * {@link com.duoduo.common.tenant.TenantContext} 的"为什么用 Optional"）。
     *
     * @param tenantId 租户标识；{@code null} 时不写声明（该令牌不参与租户过滤，向后兼容）
     */
    public String createAccessToken(LoginUser user, String entry, Long permVersion, Long tenantId) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_ACCESS);
        claims.put(CLAIM_PHONE, user.phone() == null ? "" : user.phone());
        claims.put(CLAIM_ROLE, user.role() == null ? "USER" : user.role());
        claims.put(CLAIM_REAL_NAME, user.realNameVerified());
        String normalized = ClientEntry.normalize(entry);
        if (normalized != null) {
            claims.put(CLAIM_ENTRY, normalized);
        }
        if (permVersion != null) {
            claims.put(CLAIM_PERM_VERSION, permVersion);
        }
        if (tenantId != null) {
            // E20：自营（0）也会走到这里 —— 它是有效租户，不是"没有租户"
            claims.put(CLAIM_TENANT, tenantId);
        }
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(secretKey)
                .compact();
    }

    /** 兼容入口：不声明入口、不声明两步验证状态（等价于"老令牌"，刷新时按未满足处理） */
    public String createRefreshToken(Long userId, String jti) {
        return createRefreshToken(userId, jti, null, false);
    }

    /** 兼容入口：不声明两步验证状态 */
    public String createRefreshToken(Long userId, String jti, String entry) {
        return createRefreshToken(userId, jti, entry, false);
    }

    /**
     * 签发刷新令牌。
     *
     * <p>刷新令牌同样带 ent：{@code /api/auth/refresh} 用它换新的访问令牌，
     * 若刷新令牌不带入口，刷新一次就会把"入口限制"洗掉 —— 那等于这个字段形同虚设。
     *
     * <p>E21：{@code twoFactorSatisfied} 为 true 时写入 {@code tfv} 声明。
     * 真实签发路径是 {@code AuthService.issueTokens}，它传入的是"本次登录是否已满足
     * 该账号的两步验证要求"（未绑定 2FA 的账号视为已满足）。
     */
    public String createRefreshToken(Long userId, String jti, String entry, boolean twoFactorSatisfied) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH);
        String normalized = ClientEntry.normalize(entry);
        if (normalized != null) {
            builder.claim(CLAIM_ENTRY, normalized);
        }
        if (twoFactorSatisfied) {
            builder.claim(CLAIM_TWO_FACTOR_SATISFIED, true);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtl)))
                .signWith(secretKey)
                .compact();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    /**
     * 解析令牌。
     *
     * @param expectedType {@link #TYPE_ACCESS} 或 {@link #TYPE_REFRESH}
     * @return 解析结果；令牌无效/过期/类型不符时返回 {@link TokenPayload#invalid()}
     */
    public TokenPayload parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return TokenPayload.invalid();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                return TokenPayload.invalid();
            }

            Long userId = Long.valueOf(claims.getSubject());
            String phone = claims.get(CLAIM_PHONE, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            Boolean realName = claims.get(CLAIM_REAL_NAME, Boolean.class);
            // E8：老令牌没有 ent 声明 → 归一化后为 null → 网关不施加入口限制（向后兼容）
            String entry = ClientEntry.normalize(claims.get(CLAIM_ENTRY, String.class));
            // E17：同样取不到就是 null（老令牌）→ 网关不施加版本校验。
            //
            // 刻意按 Number 取值而不是 claims.get("pv", Long.class)：JSON 里的小整数
            // 反序列化后是 Integer，直接按 Long 取会走到 jjwt 的类型转换分支 ——
            // 那一步在版本升级里改过行为，属于"能省一次却要赌依赖实现"的地方。
            Object rawPermVersion = claims.get(CLAIM_PERM_VERSION);
            Long permVersion = rawPermVersion instanceof Number number ? number.longValue() : null;
            // E20：同样按 Number 取（理由同上，JSON 小整数是 Integer）。
            // 取不到就是 null —— 可能是老令牌，也可能是"本就不属于任何店铺"，
            // 两者在下游的处理**完全相同**（都不追加租户条件），所以不需要再区分。
            Object rawTenant = claims.get(CLAIM_TENANT);
            Long tenantId = rawTenant instanceof Number number ? number.longValue() : null;
            // E21：取不到就是 false（老刷新令牌）→ 刷新时按"两步验证未满足"处理，
            // 于是"启用 2FA 之前签发的刷新令牌"会在下一次刷新时被拦下，而不是继续用满 7 天。
            boolean twoFactorSatisfied =
                    Boolean.TRUE.equals(claims.get(CLAIM_TWO_FACTOR_SATISFIED, Boolean.class));
            return new TokenPayload(userId, phone, role, Boolean.TRUE.equals(realName), claims.getId(), entry,
                    permVersion, tenantId, twoFactorSatisfied, true);
        } catch (JwtException | IllegalArgumentException ex) {
            return TokenPayload.invalid();
        }
    }

    public LoginUser toLoginUser(TokenPayload payload) {
        return new LoginUser(payload.userId(), payload.phone(), payload.role(), payload.realNameVerified());
    }

    /**
     * 令牌载荷。invalid 表示签名不合法、已过期或类型不符。
     *
     * @param entry       E8 · 入口标识；{@code null} = 该令牌未声明入口（老令牌 / 小程序直连），不受入口路径限制
     * @param permVersion E17 · 权限版本；{@code null} = 该令牌未声明版本（老令牌），不参与版本校验
     * @param tenantId    E20 · 租户标识（店铺主体 userId）；{@code null} = 不属于任何店铺（买家/运营/管理员）
     *                    或老令牌，两者下游处理相同 —— 都不追加租户条件。{@code 0} = 平台自营，是有效值
     * @param twoFactorSatisfied E21 · 两步验证要求是否已满足。**只对刷新令牌有意义**：
     *                    {@code false} = 该刷新令牌是在"未满足 2FA"的状态下签发的（老令牌，
     *                    或账号启用 2FA 之前签发的），刷新时若账号现已启用 2FA 则应拒绝。
     *                    访问令牌上恒为 {@code false}（不带该声明），因为强制点不在这里
     */
    public record TokenPayload(
            Long userId,
            String phone,
            String role,
            boolean realNameVerified,
            String jti,
            String entry,
            Long permVersion,
            Long tenantId,
            boolean twoFactorSatisfied,
            boolean valid
    ) {
        public static TokenPayload invalid() {
            return new TokenPayload(null, null, null, false, null, null, null, null, false, false);
        }
    }
}
