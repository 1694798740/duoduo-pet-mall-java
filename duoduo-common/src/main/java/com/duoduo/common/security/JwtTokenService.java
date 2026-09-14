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

    public String createAccessToken(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claims(Map.of(
                        CLAIM_TYPE, TYPE_ACCESS,
                        CLAIM_PHONE, user.phone() == null ? "" : user.phone(),
                        CLAIM_ROLE, user.role() == null ? "USER" : user.role(),
                        CLAIM_REAL_NAME, user.realNameVerified()
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(secretKey)
                .compact();
    }

    public String createRefreshToken(Long userId, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
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
            return new TokenPayload(userId, phone, role, Boolean.TRUE.equals(realName), claims.getId(), true);
        } catch (JwtException | IllegalArgumentException ex) {
            return TokenPayload.invalid();
        }
    }

    public LoginUser toLoginUser(TokenPayload payload) {
        return new LoginUser(payload.userId(), payload.phone(), payload.role(), payload.realNameVerified());
    }

    /** 令牌载荷。invalid 表示签名不合法、已过期或类型不符。 */
    public record TokenPayload(
            Long userId,
            String phone,
            String role,
            boolean realNameVerified,
            String jti,
            boolean valid
    ) {
        public static TokenPayload invalid() {
            return new TokenPayload(null, null, null, false, null, false);
        }
    }
}
