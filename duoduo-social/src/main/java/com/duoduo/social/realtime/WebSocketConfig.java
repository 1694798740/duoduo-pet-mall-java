package com.duoduo.social.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（2026-10-23）：{@code /ws/chat?ticket=...}。
 *
 * <h2>为什么不开 SockJS</h2>
 * SockJS 的价值是在老旧浏览器不支持 WS 时退回 xhr-streaming。本项目的三个客户端
 * 都是现代浏览器 / 微信内核，都支持原生 WS；多引一层会让小程序端无法复用同一协议
 * （{@code wx.connectSocket} 说的是裸 WS），反而制造两端差异。
 *
 * <h2>为什么不校验 Origin</h2>
 * 鉴权靠一次性票据（必须先用带 Bearer 的 HTTP 换取），跨站脚本拿不到票据，
 * 因此 Origin 不是安全边界；写死白名单反而会让三个站点域名变化时必须改代码。
 * 这一点与"Cookie 鉴权必须校验 Origin"的场景不同，不能照搬结论。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatSocketHandler handler;
    private final WsHandshakeInterceptor interceptor;

    public WebSocketConfig(ChatSocketHandler handler, WsHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
