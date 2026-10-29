package com.duoduo.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 写操作二次确认（2026-10-28）。
 *
 * <p>模型只能生成确认卡；用户点击确认后，后端消费一次性 token 再执行加购。
 * token 绑定 userId + action + 参数，5 分钟过期，消费即删。
 */
@Service
public class ConfirmService {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ConfirmService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public String issue(Long userId, String action, JsonNode payload) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set("ai:confirm:" + token,
                    mapper.writeValueAsString(new ConfirmPayload(userId, action, stripToken(payload))), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("确认令牌写入失败", e);
        }
        return token;
    }

    public boolean consume(Long userId, String token, String action, JsonNode payload) {
        if (userId == null || token == null || token.isBlank()) {
            return false;
        }
        String key = "ai:confirm:" + token;
        String json = redis.opsForValue().getAndDelete(key);
        if (json == null) {
            return false;
        }
        try {
            ConfirmPayload value = mapper.readValue(json, ConfirmPayload.class);
            return userId.equals(value.userId())
                    && action.equals(value.action())
                    && stripToken(payload).equals(value.payload());
        } catch (Exception e) {
            return false;
        }
    }

    /** confirmToken 是传输字段，不属于业务参数；比对时必须剔除，否则永远不相等。 */
    private static JsonNode stripToken(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        com.fasterxml.jackson.databind.node.ObjectNode copy = payload.deepCopy();
        copy.remove("confirmToken");
        return copy;
    }

    public record ConfirmPayload(Long userId, String action, JsonNode payload) {
    }
}
