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
     *   <li>本方法 —— <b>真实签发路径必须走它</b>。目前只有两处：
     *       {@code AuthService.issueTokens}（登录/注册/刷新）与
     *       {@code AuthService.renewAccessToken}（角色升级后重签）。两处都从库里现读版本，
     *       不接受调用方传入 —— 版本只有一个来源，不给第二份口径。</li>
     * </ul>
     *
     * @param permVersion 权限版本；{@code null} 时不写声明（该令牌不参与版本校验，向后兼容）
     */
    public String createAccessToken(LoginUser user, String entry, Long permVersion) {
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
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(secretKey)
                .compact();
    }

    /** 兼容入口：不声明入口 */
    public String createRefreshToken(Long userId, String jti) {
        return createRefreshToken(userId, jti, null);
    }

    /**
     * 签发刷新令牌。
     *
     * <p>刷新令牌同样带 ent：{@code /api/auth/refresh} 用它换新的访问令牌，
     * 若刷新令牌不带入口，刷新一次就会把"入口限制"洗掉 —— 那等于这个字段形同虚设。
     */
    public String createRefreshToken(Long userId, String jti, String entry) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH);
        String normalized = ClientEntry.normalize(entry);
        if (normalized != null) {
            builder.claim(CLAIM_ENTRY, normalized);
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
            return new TokenPayload(userId, phone, role, Boolean.TRUE.equals(realName), claims.getId(), entry,
                    permVersion, true);
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
     */
    public record TokenPayload(
            Long userId,
            String phone,
            String role,
            boolean realNameVerified,
            String jti,
            String entry,
            Long permVersion,
            boolean valid
    ) {
        public static TokenPayload invalid() {
            return new TokenPayload(null, null, null, false, null, null, null, false);
        }
    }
}
