package com.duoduo.social.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连接注册表：userId → 该用户的全部 WebSocket 连接（**多端/多标签页**）。
 *
 * <h2>为什么按 userId 而不是按会话 id 建索引</h2>
 * 一个用户可能同时开着客服页与私信页，甚至两个标签页；按会话建索引会让「同一个人收到的
 * 消息只到其中一个连接」。按 userId 聚合后，一次推送覆盖他所有在线端，
 * 由客户端自己判断"这条事件是否属于我当前打开的会话"。
 *
 * <h2>线程安全</h2>
 * {@code WebSocketSession.sendMessage} 官方明确<b>不是线程安全的</b>：两个线程同时往
 * 同一个连接写会抛出 {@code TEXT_PARTIAL_WRITING}。这里对每个 session 加锁串行发送；
 * 发送失败（连接已断）直接摘除，不向调用方抛异常 —— 推送失败不该影响业务写入。
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<Long, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-pinger");
        t.setDaemon(true);
        return t;
    });

    public ConnectionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 30 秒一次心跳：清理掉没有正常 close 的连接（笔记本合盖、断网、进程被杀）
        pinger.scheduleAtFixedRate(this::pingAll, 30, 30, TimeUnit.SECONDS);
    }

    public void register(Long userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(Long userId, WebSocketSession session) {
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> set = byUser.get(userId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            byUser.remove(userId, set);
        }
    }

    /** 在线连接数（自检与容量观测用） */
    public int onlineConnections() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    public int onlineUsers() {
        return byUser.size();
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> set = userId == null ? null : byUser.get(userId);
        return set != null && !set.isEmpty();
    }

    /** 推给某个用户的全部连接；返回实际送达的连接数 */
    public int send(Long userId, RealtimeEvent event) {
        Set<WebSocketSession> set = userId == null ? null : byUser.get(userId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("实时事件序列化失败 type={}", event.type(), e);
            return 0;
        }
        int sent = 0;
        for (WebSocketSession session : set) {
            if (!session.isOpen()) {
                set.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                sent++;
            } catch (IOException | IllegalStateException e) {
                log.debug("推送失败，摘除连接 userId={}：{}", userId, e.getMessage());
                set.remove(session);
                closeQuietly(session);
            }
        }
        return sent;
    }

    private void pingAll() {
        for (Map.Entry<Long, Set<WebSocketSession>> entry : byUser.entrySet()) {
            for (WebSocketSession s : entry.getValue()) {
                if (!s.isOpen()) {
                    entry.getValue().remove(s);
                    continue;
                }
                try {
                    synchronized (s) {
                        s.sendMessage(new TextMessage("{\"type\":\"PING\"}"));
                    }
                } catch (Exception e) {
                    entry.getValue().remove(s);
                    closeQuietly(s);
                }
            }
            if (entry.getValue().isEmpty()) {
                byUser.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // 已断开，无需处理
        }
    }

    @PreDestroy
    public void shutdown() {
        pinger.shutdownNow();
        byUser.values().forEach(set -> set.forEach(this::closeQuietly));
        byUser.clear();
    }
}
