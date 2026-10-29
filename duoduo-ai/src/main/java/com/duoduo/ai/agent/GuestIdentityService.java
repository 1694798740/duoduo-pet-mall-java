package com.duoduo.ai.agent;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 游客身份与轮次配额（2026-10-29 · 阶段三）。
 *
 * <h2>为什么是「匿名 Cookie + deviceId」而不是 IP</h2>
 * 按 IP 限流会误伤整栋楼（公司/学校/运营商 NAT 出口共用一个 IP），也会让
 * 「同一个人在 Wi-Fi 与 4G 之间切换」凭空多出配额。Cookie 标识的是**设备**，
 * 天然不牵连同网段的其他用户；代价是清 Cookie 可以重置 —— 拉高成本而不是绝对杜绝，
 * 这是产品可接受的口径（决策 Q1）。
 *
 * <h2>三条实现约束</h2>
 * <ol>
 *   <li><b>Cookie 必须 HttpOnly + SameSite=Lax</b>：前端 JS 读不到，脚本注入也偷不走；
 *       Lax 保证从外部链接跳进来时仍会带上，站内跳转不受影响。</li>
 *   <li><b>deviceId 必须是 UUID 形状</b>：它会被拼进 Redis Key。若允许任意字符串，
 *       一个值为 {@code a:b} 的 Cookie 就能和别的 Key 撞上，甚至写坏别人的计数。</li>
 *   <li><b>Redis 故障时放行（fail-open）</b>：配额是体验约束，不是安全边界。
 *       为了「限制游客三轮」而让整个对话在 Redis 抖动时 503，代价远大于收益。</li>
 * </ol>
 *
 * <h2>计轮口径</h2>
 * 只有**成功产出回复**的一轮才计数（消费在对话末尾，见 AgentChatService）；
 * LLM 超时、工具异常、被配额拦下都不扣次数 —— 用户没得到任何东西，不该付代价。
 */
@Component
public class GuestIdentityService {

    private static final Logger log = LoggerFactory.getLogger(GuestIdentityService.class);

    /** 游客总轮次：3 轮 / 24 小时 / 设备（文档 Q1）。 */
    public static final int MAX_GUEST_TURNS = 3;

    private static final String COOKIE_NAME = "duoduo_guest";
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final String KEY_PREFIX = "ai:guest:turns:";

    /** 只接受 UUID：既排除 Redis Key 注入，也让「伪造一个超长 Cookie」无效。 */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final StringRedisTemplate redis;

    /**
     * 站点是否已上 HTTPS。默认 false（当前部署是 IP 直连 http）；
     * 上 HTTPS 后设为 true，浏览器就不会在明文请求里带这个 Cookie。
     */
    private final boolean cookieSecure;

    public GuestIdentityService(StringRedisTemplate redis,
                                @Value("${duoduo.ai.guest.cookie-secure:false}") boolean cookieSecure) {
        this.redis = redis;
        this.cookieSecure = cookieSecure;
    }

    /**
     * 解析（必要时签发）游客标识。
     *
     * <p>登录用户也会走到这里（前端无法预知登录态就先发了请求），但登录态下
     * 配额不生效 —— 见 {@link #remaining}，调用方只在 userId 为空时才查配额。
     */
    public GuestIdentity resolve(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (existing != null && UUID_SHAPE.matcher(existing).matches()) {
            return new GuestIdentity(existing.toLowerCase(Locale.ROOT), false);
        }
        String deviceId = UUID.randomUUID().toString();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, deviceId)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(WINDOW)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new GuestIdentity(deviceId, true);
    }

    /** 剩余可用轮次（未用过的设备为 {@link #MAX_GUEST_TURNS}）。 */
    public int remaining(String deviceId) {
        return Math.max(0, MAX_GUEST_TURNS - used(deviceId));
    }

    /**
     * 消费一轮配额。只在回复成功后调用。
     *
     * <p>先 INCR 再判断 TTL：第一次写入时键没有过期时间，必须补上；
     * 已存在的键如果 TTL 丢了（运维误操作 / 迁移），也要补回 24 小时，
     * 否则计数会永远累积、游客被永久锁死。
     */
    public void consume(String deviceId) {
        if (!valid(deviceId)) {
            return;
        }
        try {
            Long count = redis.opsForValue().increment(key(deviceId));
            Long ttl = redis.getExpire(key(deviceId));
            if (count != null && count == 1L || ttl == null || ttl < 0) {
                redis.expire(key(deviceId), WINDOW);
            }
        } catch (Exception e) {
            log.warn("游客轮次计数失败（放行本次） device={}: {}", mask(deviceId), e.getMessage());
        }
    }

    private int used(String deviceId) {
        if (!valid(deviceId)) {
            return 0;
        }
        try {
            String value = redis.opsForValue().get(key(deviceId));
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.warn("游客轮次读取失败（按未使用处理） device={}: {}", mask(deviceId), e.getMessage());
            return 0;
        }
    }

    private static boolean valid(String deviceId) {
        return deviceId != null && UUID_SHAPE.matcher(deviceId).matches();
    }

    /**
     * Redis Key 用规范化后的 UUID（与 Cookie 值一致）。
     * 不额外哈希：UUID 本身不含分隔符，Key 可读反而便于排查「这台设备还剩几轮」。
     */
    private static String key(String deviceId) {
        return KEY_PREFIX + deviceId.toLowerCase(Locale.ROOT);
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 日志里只留前 8 位：deviceId 是可长期追踪的标识，没必要整段落盘。 */
    private static String mask(String deviceId) {
        return deviceId == null || deviceId.length() <= 8 ? "?" : deviceId.substring(0, 8) + "…";
    }

    /**
     * 解析结果。
     *
     * @param deviceId 设备标识（UUID）
     * @param fresh    是否本次新签发（前端首次访问时为 true）
     */
    public record GuestIdentity(String deviceId, boolean fresh) {
    }
}
