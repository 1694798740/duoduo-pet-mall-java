package com.duoduo.social.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 实时事件发布（2026-10-23）。
 *
 * <h2>为什么要经过 Redis，而不是直接推本机连接</h2>
 * 社交域是**无状态**服务，可以随意扩容到多实例。用户 A 的连接在实例 1，给用户 B 发消息时
 * 请求可能落在实例 2 —— 只推本机连接的话，B 在实例 1 上就会"收不到"。Redis Pub/Sub 把
 * 事件广播给所有实例，各实例只推自己持有的连接。这是 500+ 并发的扩展前提。
 *
 * <h2>失败降级</h2>
 * Redis 不可用时退回**本机直推**：单实例部署仍能实时（这也是本地开发与自测的形态），
 * 多实例会退化成"一半概率收不到"，但 HTTP 轮询仍在兜底，属于可接受的功能降级，
 * 日志会 WARN 出来，不静默。
 */
@Service
public class RealtimePublisher {

    public static final String CHANNEL = "duoduo:realtime";

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ConnectionRegistry registry;
    private final ObjectMapper objectMapper;

    public RealtimePublisher(StringRedisTemplate redis,
                             ConnectionRegistry registry,
                             ObjectMapper objectMapper) {
        this.redis = redis;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /** 信封：投给谁 + 事件本体（序列化后进 Redis 频道） */
    public record Envelope(Long toUserId, RealtimeEvent event) {
    }

    public void publish(Long toUserId, RealtimeEvent event) {
        if (toUserId == null || event == null) {
            return;
        }
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(new Envelope(toUserId, event)));
        } catch (Exception e) {
            // 本地开发没起 Redis / Redis 抖动：退回本机直推，保证单实例可用
            int n = registry.send(toUserId, event);
            log.warn("Redis 发布失败，已退回本机推送（送 {} 条连接）：{}", n, e.getMessage());
        }
    }
}
