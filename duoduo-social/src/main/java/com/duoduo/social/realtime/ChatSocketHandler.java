package com.duoduo.social.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天 WebSocket 处理器（2026-10-23）。
 *
 * <h2>协议（极简 JSON，不引 STOMP/SockJS）</h2>
 * 客户端 → 服务端：
 * <pre>
 *   {"type":"PING"}
 *   {"type":"TYPING","biz":"SUPPORT","sessionId":"123"}
 *   {"type":"TYPING","biz":"DIRECT","conversationId":"lo:hi"}
 * </pre>
 * 服务端 → 客户端：{@link RealtimeEvent}（MESSAGE / READ / TYPING / READY / PING / PONG）。
 *
 * <p>为什么不用 STOMP：三端都要手写客户端（小程序只有 {@code wx.connectSocket}，
 * 带不了 STOMP 库），协议越简单越不会在某一端出现"连上了但收不到"。
 * 心跳由服务端每 30 秒 PING、客户端回 PONG，双向确认链路还活着。
 *
 * <h2>TYPING 的频控</h2>
 * 每连接 700ms 最多转发一次：输入事件本身高频（每敲一个字都可能触发），
 * 不限流会把"正在输入"变成一条持续的广播风暴。
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    private static final long TYPING_INTERVAL_MS = 700;

    public static final String ATTR_USER_ID = "uid";

    private final ConnectionRegistry registry;
    private final RealtimePublisher publisher;
    private final RealtimePeerResolver peerResolver;
    private final ObjectMapper objectMapper;
    /** 连接 → 上次转发 TYPING 的时间（只用于限流，断连即清理） */
    private final Map<String, Long> lastTypingAt = new ConcurrentHashMap<>();

    public ChatSocketHandler(ConnectionRegistry registry,
                             RealtimePublisher publisher,
                             RealtimePeerResolver peerResolver,
                             ObjectMapper objectMapper) {
        this.registry = registry;
        this.publisher = publisher;
        this.peerResolver = peerResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        registry.register(userId, session);
        sendDirect(session, RealtimeEvent.simple(RealtimeEvent.READY));
        log.debug("WS 建连 userId={} 当前在线连接={}", userId, registry.onlineConnections());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String payload = message.getPayload();
        if (payload == null || payload.length() > 512) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("type").asText("");
            if ("PING".equals(type)) {
                sendDirect(session, RealtimeEvent.simple(RealtimeEvent.PONG));
                return;
            }
            if ("PONG".equals(type)) {
                return; // 服务端心跳的回执，收到即可
            }
            if (!RealtimeEvent.TYPING.equals(type)) {
                return; // 其它类型不接收：消息写入只走 HTTP，杜绝绕过校验的通道
            }
            if (!allowTyping(session.getId())) {
                return;
            }
            String biz = node.path("biz").asText("");
            if ("SUPPORT".equals(biz)) {
                String sessionId = node.path("sessionId").asText(null);
                Long peer = peerResolver.supportPeer(userId, sessionId);
                if (peer != null) {
                    publisher.publish(peer, RealtimeEvent.typing("SUPPORT", sessionId, null, userId));
                }
            } else if ("DIRECT".equals(biz)) {
                String conversationId = node.path("conversationId").asText(null);
                Long peer = peerResolver.directPeer(userId, conversationId);
                if (peer != null) {
                    publisher.publish(peer, RealtimeEvent.typing("DIRECT", null, conversationId, userId));
                }
            }
        } catch (Exception e) {
            log.debug("WS 报文解析失败 userId={}：{}", userId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(userIdOf(session), session);
        lastTypingAt.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输异常 userId={}：{}", userIdOf(session), exception.getMessage());
        registry.unregister(userIdOf(session), session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private boolean allowTyping(String wsSessionId) {
        long now = System.currentTimeMillis();
        Long last = lastTypingAt.get(wsSessionId);
        if (last != null && now - last < TYPING_INTERVAL_MS) {
            return false;
        }
        lastTypingAt.put(wsSessionId, now);
        return true;
    }

    private Long userIdOf(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_USER_ID);
        return v instanceof Long id ? id : null;
    }

    private void sendDirect(WebSocketSession session, RealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 已断开
        }
    }
}
