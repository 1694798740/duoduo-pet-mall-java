package com.duoduo.ai.controller;

import com.duoduo.ai.agent.AgentChatService;
import com.duoduo.ai.agent.AgentCommand;
import com.duoduo.ai.agent.AgentEvent;
import com.duoduo.ai.agent.AgentResult;
import com.duoduo.ai.agent.GuestIdentityService;
import com.duoduo.ai.agent.tool.ToolCall;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolRegistry;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.dto.AiDtos.CapabilityView;
import com.duoduo.ai.dto.AiDtos.ChatRequest;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.llm.LlmToolClient;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.IpRateLimiter;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * AI 导购端点（2026-10-28 重构）。
 *
 * <ul>
 *   <li>{@code POST /api/duoduo/chat/stream}：SSE 打字机，推荐走 Agent；</li>
 *   <li>{@code POST /api/duoduo/chat}：非流式兼容入口；</li>
 *   <li>{@code POST /api/duoduo/confirm}：加购二次确认执行；</li>
 *   <li>{@code GET /api/duoduo/capabilities}：能力声明。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/duoduo")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AgentChatService agentChatService;
    private final GuestIdentityService guestIdentityService;
    private final ToolRegistry toolRegistry;
    private final LlmToolClient llm;
    private final IpRateLimiter rateLimiter;
    private final CategoryCatalog categoryCatalog;
    private final ExecutorService streamExecutor;
    private final ObjectMapper mapper;

    public AiController(AgentChatService agentChatService,
                        GuestIdentityService guestIdentityService,
                        ToolRegistry toolRegistry,
                        LlmToolClient llm,
                        IpRateLimiter rateLimiter,
                        CategoryCatalog categoryCatalog,
                        @Qualifier("aiStreamExecutor") ExecutorService streamExecutor,
                        ObjectMapper mapper) {
        this.agentChatService = agentChatService;
        this.guestIdentityService = guestIdentityService;
        this.toolRegistry = toolRegistry;
        this.llm = llm;
        this.rateLimiter = rateLimiter;
        this.categoryCatalog = categoryCatalog;
        this.streamExecutor = streamExecutor;
        this.mapper = mapper;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                          HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) {
        checkRate(httpRequest);
        LoginUser user = UserContext.get();
        // 未登录也要有稳定的设备标识：首访签发 HttpOnly Cookie，后续按它计游客轮次
        GuestIdentityService.GuestIdentity guest =
                guestIdentityService.resolve(httpRequest, httpResponse);
        AgentResult result = agentChatService.chat(command(request, user, guest), event -> { });
        if (!result.success()) {
            throw new BizException(ErrorCode.DEGRADED, result.errorMessage());
        }
        return ApiResponse.ok(new ChatResponse(result.reply(), result.recommendations(),
                "llm", result.clarify(), List.of(), result.cards(), result.badgePatch(), result.loginUrl(),
                result.sessionId()));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request,
                             HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse) {
        checkRate(httpRequest);
        LoginUser user = UserContext.get();
        // Cookie 必须在响应提交前写入：SSE 一旦开始吐事件，响应头就冻结了
        GuestIdentityService.GuestIdentity guest =
                guestIdentityService.resolve(httpRequest, httpResponse);
        SseEmitter emitter = new SseEmitter(120_000L);
        AgentCommand command = command(request, user, guest);

        streamExecutor.execute(() -> {
            try {
                agentChatService.chat(command, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE 对话失败: {}", e.getMessage());
                send(emitter, AgentEvent.error("AI 暂时不可用，请稍后再试"));
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 加购二次确认：只有用户点击确认后才会消费令牌并执行 */
    @PostMapping("/confirm")
    public ApiResponse<ToolResult> confirm(@RequestBody ConfirmRequest request,
                                           HttpServletRequest httpRequest) {
        checkRate(httpRequest);
        LoginUser user = UserContext.get();
        if (user == null) {
            throw BizException.unauthorized("请先登录");
        }
        ObjectNode args = mapper.createObjectNode();
        args.put("spuId", request.spuId());
        args.put("skuId", request.skuId());
        args.put("quantity", request.quantity() == null ? 1 : request.quantity());
        args.put("confirmToken", request.token());
        ToolContext context = new ToolContext(user.userId(), user.role(), user.phone(),
                request.sessionId() == null ? UUID.randomUUID().toString().replace("-", "") : request.sessionId(),
                UUID.randomUUID().toString().replace("-", ""), true);
        ToolResult result = toolRegistry.execute(
                new ToolCall(UUID.randomUUID().toString(), "add_to_cart", args.toString()), context);
        return ApiResponse.ok(result);
    }

    @GetMapping("/capabilities")
    public ApiResponse<CapabilityView> capabilities() {
        boolean ready = llm.isConfigured();
        String mode = ready ? "llm" : "unconfigured";
        String hint = ready
                ? "AI 导购已就绪，正在从真实商品库为你挑选。"
                : "AI 服务未配置（DASHSCOPE_API_KEY 为空），请联系管理员。";
        List<String> quick = new java.util.ArrayList<>();
        try {
            for (var c : categoryCatalog.clarifyOptions(4)) {
                quick.add("帮我挑一款" + c.name());
            }
        } catch (Exception ex) {
            log.warn("类目取不到，本次不返回快捷问题: {}", ex.getMessage());
        }
        return ApiResponse.ok(new CapabilityView(ready, mode, hint, quick));
    }

    private AgentCommand command(ChatRequest request, LoginUser user,
                                 GuestIdentityService.GuestIdentity guest) {
        return new AgentCommand(request.sessionId(),
                user == null ? null : user.userId(),
                user == null ? null : user.role(),
                user == null ? null : user.phone(),
                request.message(),
                guest == null ? null : guest.deviceId(),
                request.pageUrl(),
                request.clarifiedOnce());
    }

    private void checkRate(HttpServletRequest request) {
        if (!rateLimiter.allow(clientKey(request))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "对话太频繁啦，请一分钟后再来");
        }
    }

    private void send(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 限流键：登录态用 userId，未登录用网关注入的 X-Real-IP，兜底 TCP 对端。
     */
    private static String clientKey(HttpServletRequest request) {
        LoginUser user = UserContext.get();
        if (user != null && user.userId() != null) {
            return "u:" + user.userId();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return "ip:" + realIp.trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    public record ConfirmRequest(String token, Long spuId, Long skuId, Integer quantity,
                                 String sessionId) {
    }
}
