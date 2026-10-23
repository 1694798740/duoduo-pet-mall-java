package com.duoduo.social.realtime;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时通道的接入点（2026-10-23）。
 *
 * <h2>为什么换票要单独一个 HTTP 接口</h2>
 * 浏览器建 WS 不能带 Authorization 头；把长期令牌塞进 URL 会进访问日志。
 * 所以先用正常的登录态换一张 60 秒、一次性、无权限的票，再用票建连。
 * 换票接口本身走网关统一鉴权，不需要额外安全措施。
 *
 * <h2>online 为什么允许登录用户看</h2>
 * 它只回聚合数字（连接数 / 在线用户数），不含任何个人信息，用于自检与容量观察
 * （与 {@code /api/support/unread-count} 同级别的运行指标）。单用户维度的在线状态
 * 不对外暴露 —— 那是隐私。
 */
@RestController
public class RealtimeController {

    private final WsTicketService tickets;
    private final ConnectionRegistry registry;

    public RealtimeController(WsTicketService tickets, ConnectionRegistry registry) {
        this.tickets = tickets;
        this.registry = registry;
    }

    @PostMapping("/api/socials/realtime/ticket")
    public ApiResponse<Map<String, Object>> ticket() {
        Long userId = UserContext.requireUserId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", tickets.issue(userId));
        body.put("expiresIn", 60);
        body.put("path", "/ws/chat");
        return ApiResponse.ok(body);
    }

    /** 本实例的连接规模（多实例部署时是单实例口径，不是全局） */
    @GetMapping("/api/socials/realtime/online")
    public ApiResponse<Map<String, Object>> online() {
        UserContext.requireUserId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connections", registry.onlineConnections());
        body.put("users", registry.onlineUsers());
        return ApiResponse.ok(body);
    }
}
