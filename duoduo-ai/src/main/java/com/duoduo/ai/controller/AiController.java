package com.duoduo.ai.controller;

import com.duoduo.ai.dto.AiDtos.CapabilityView;
import com.duoduo.ai.dto.AiDtos.ChatRequest;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.service.ChatService;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.IpRateLimiter;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 导购「多多」端点（A9）。
 *
 * <p>两条路径都在网关白名单（{@code /api/duoduo/chat}、{@code /api/duoduo/capabilities}）：
 * 未登录可问答（验收②），需登录的操作（加购 / 下单）由回复内容引导用户去登录 ——
 * 对话本身不承载身份，也就不需要令牌。
 *
 * <p>限流在业务入口做（每 IP 每分钟）：LLM 配额是真金白银，网关层限流管全站洪水，
 * 这里管的是「把导购当聊天机器人刷」的定向滥用。
 */
@RestController
@RequestMapping("/api/duoduo")
public class AiController {

    private final ChatService chatService;
    private final DashScopeClient dashScopeClient;
    private final IpRateLimiter rateLimiter;
    private final boolean mockEnabled;

    public AiController(ChatService chatService,
                        DashScopeClient dashScopeClient,
                        IpRateLimiter rateLimiter,
                        @Value("${duoduo.ai.mock-enabled:true}") boolean mockEnabled) {
        this.chatService = chatService;
        this.dashScopeClient = dashScopeClient;
        this.rateLimiter = rateLimiter;
        this.mockEnabled = mockEnabled;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                          HttpServletRequest httpRequest) {
        String clientKey = clientKey(httpRequest);
        if (!rateLimiter.allow(clientKey)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "对话太频繁啦，请一分钟后再来");
        }
        return ApiResponse.ok(chatService.chat(request.message(), request.history()));
    }

    /** 能力声明：前端据此显示/隐藏 AI 入口，并在 mock / 未配置形态下给出相应提示 */
    @GetMapping("/capabilities")
    public ApiResponse<CapabilityView> capabilities() {
        boolean llmReady = dashScopeClient.isConfigured();
        String mode = mockEnabled ? "mock" : (llmReady ? "llm" : "unconfigured");
        boolean available = mockEnabled || llmReady;
        String hint = mockEnabled
                ? "当前为演示模式（未接大模型），推荐结果均来自真实商品库。"
                : (llmReady ? "AI 导购已就绪。" : "AI 服务未配置，暂不可用。");
        return ApiResponse.ok(new CapabilityView(available, mode, hint));
    }

    /** 限流键：优先取网关透传的原始 IP（X-Forwarded-For 首段），退化到直连地址 */
    private static String clientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
