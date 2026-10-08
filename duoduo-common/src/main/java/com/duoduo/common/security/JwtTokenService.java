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
            return new TokenPayload(userId, phone, role, Boolean.TRUE.equals(realName), claims.getId(), entry, true);
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
     * @param entry E8 · 入口标识；{@code null} = 该令牌未声明入口（老令牌 / 小程序直连），不受入口路径限制
     */
    public record TokenPayload(
            Long userId,
            String phone,
            String role,
            boolean realNameVerified,
            String jti,
            String entry,
            boolean valid
    ) {
        public static TokenPayload invalid() {
            return new TokenPayload(null, null, null, false, null, null, false);
        }
    }
}
