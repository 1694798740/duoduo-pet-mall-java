package com.duoduo.social.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 握手票据（2026-10-23）。
 *
 * <h2>为什么不直接把 JWT 放进 ws:// 的查询串</h2>
 * 浏览器的 {@code new WebSocket()} **不能设置请求头**，令牌只能走 URL 或子协议。
 * 而 URL 会进入 nginx access log、浏览器历史、监控采样 —— 一个有效期 2 小时的
 * access token 出现在日志里，等于把账号交出去。
 *
 * <p>因此改成**一次性、短时效的票据**：客户端先用正常的 HTTP（带 Bearer）换一张
 * 60 秒内有效、用一次即失效的票，再用票建连。票据本身没有任何权限，泄露也只能建一条连接。
 *
 * <h2>存储：Redis 优先，内存兜底</h2>
 * 多实例部署时票据必须共享（换票的实例和建连的实例可能不是同一个），所以优先写 Redis；
 * Redis 不可用时退回本机 Map —— 单实例仍能工作，多实例下会表现为「偶尔建连失败」，
 * 由日志里的 WARN 显式暴露，不静默。
 */
@Service
public class WsTicketService {

    private static final Logger log = LoggerFactory.getLogger(WsTicketService.class);
    private static final String KEY_PREFIX = com.duoduo.common.realtime.RealtimeTicketKeys.PREFIX;
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    /** Redis 不可用时的本机兜底：ticket → userId#expireAt */
    private final Map<String, String> local = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public WsTicketService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 签发一张票据（只对当前登录用户有效） */
    public String issue(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        String value = String.valueOf(userId);
        try {
            redis.opsForValue().set(KEY_PREFIX + ticket, value, TTL);
        } catch (Exception e) {
            log.warn("Redis 写票据失败，退回本机内存（多实例下可能建连失败）：{}", e.getMessage());
            local.put(ticket, value + "#" + (System.currentTimeMillis() + TTL.toMillis()));
            sweepLocal();
        }
        return ticket;
    }

    /** 消费票据：成功返回 userId，失败返回 null（一次性，重复使用必然失败） */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 64) {
            return null;
        }
        try {
            String value = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket);
            if (value != null) {
                return parse(value);
            }
            // Redis 里没有：可能是内存兜底签发的（Redis 刚恢复）
        } catch (Exception e) {
            log.warn("Redis 取票据失败，尝试本机内存：{}", e.getMessage());
        }
        String value = local.remove(ticket);
        if (value == null) {
            return null;
        }
        int idx = value.indexOf('#');
        String uid = idx > 0 ? value.substring(0, idx) : value;
        long expireAt = idx > 0 ? Long.parseLong(value.substring(idx + 1)) : 0;
        return expireAt > System.currentTimeMillis() ? parse(uid) : null;
    }

    private Long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 兜底表清理：只清过期的，避免长时间运行后无界增长 */
    private void sweepLocal() {
        if (counter.incrementAndGet() % 50 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(en -> {
            int idx = en.getValue().indexOf('#');
            return idx > 0 && Long.parseLong(en.getValue().substring(idx + 1)) < now;
        });
    }
}
