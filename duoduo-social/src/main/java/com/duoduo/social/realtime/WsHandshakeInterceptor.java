package com.duoduo.social.realtime;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 握手鉴权：{@code /ws/chat?ticket=xxx}（2026-10-23）。
 *
 * <p>浏览器的 WebSocket 不能带 Authorization 头，所以令牌不能直接放 URL（会进访问日志）。
 * 这里用一次性票据换身份：票据 60 秒有效、用后即焚，泄露也只能建一条连接。
 * 校验通过后把 userId 写进会话属性，之后所有报文都以它为准 —— 客户端无法自称成别人。
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private final WsTicketService tickets;

    public WsHandshakeInterceptor(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        Long userId = tickets.consume(ticket);
        if (userId == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ChatSocketHandler.ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
