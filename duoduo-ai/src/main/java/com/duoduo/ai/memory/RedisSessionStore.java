package com.duoduo.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话状态存储（2026-10-28）。
 *
 * <p>Redis 存 JSON，TTL 2 小时；同一 session 用 SET NX 锁住，防止用户连点造成两轮并发写。
 * Redis 故障时退化为「本次请求无会话状态」，不让整条对话链路挂掉。
 */
@Component
public class RedisSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);
    private static final Duration TTL = Duration.ofHours(2);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public SessionState loadOrCreate(String sessionId, Long userId) {
        String key = key(sessionId);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                SessionState state = mapper.readValue(json, SessionState.class);
                return state.userId() == null && userId != null
                        ? new SessionState(state.sessionId(), userId, state.messages(), state.slots(),
                        state.recommendedIds(), state.summary(), state.turnCount(),
                        state.lastIntent(), state.updatedAt())
                        : state;
            }
        } catch (Exception e) {
            log.warn("会话读取失败，按新会话继续 session={}: {}", sessionId, e.getMessage());
        }
        return SessionState.create(sessionId, userId);
    }

    public void save(SessionState state) {
        try {
            redis.opsForValue().set(key(state.sessionId()),
                    mapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.warn("会话保存失败 session={}: {}", state.sessionId(), e.getMessage());
        }
    }

    public boolean tryLock(String sessionId) {
        try {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(lockKey(sessionId), "1", LOCK_TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("会话锁不可用，放行本次请求 session={}: {}", sessionId, e.getMessage());
            return true;
        }
    }

    public void unlock(String sessionId) {
        try {
            redis.delete(lockKey(sessionId));
        } catch (Exception e) {
            log.debug("会话锁释放失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private static String key(String sessionId) {
        return "ai:sess:" + sessionId;
    }

    private static String lockKey(String sessionId) {
        return "ai:sess:" + sessionId + ":lock";
    }
}
