package com.duoduo.ai.controller;

import com.duoduo.ai.dto.AiDtos.CapabilityView;
import com.duoduo.ai.dto.AiDtos.ChatRequest;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.service.ChatService;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.IpRateLimiter;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
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
 * <p>限流在业务入口做：LLM 配额是真金白银，网关层限流管全站洪水，
 * 这里管的是「把导购当聊天机器人刷」的定向滥用。**限流键的来源见 {@link #clientKey}** ——
 * 第五十七轮修掉了「键可被客户端伪造」这个洞。
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
        if (!rateLimiter.allow(clientKey(httpRequest))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "对话太频繁啦，请一分钟后再来");
        }
        return ApiResponse.ok(chatService.chat(
                request.message(), request.history(), request.clarifiedOnce()));
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

    /**
     * 限流键（第五十七轮加固）：**绝不采用客户端能自己写的头**。
     *
     * <h2>修的是什么
     * 此前实现取 {@code X-Forwarded-For} 的**首段**并直接当键。问题是那个头由请求方提供：
     * 每次请求换一个假 IP，就等于每次都是「新用户」，限流形同虚设 ——
     * 而 LLM 配额是按调用量真金白银付费的。这类洞不会报任何错，
     * 只有在账单或配额告警上才看得出来。
     *
     * <h2>现在怎么取
     * <ol>
     *   <li><b>登录态优先用 userId</b>：{@code X-User-Id} 由网关在校验令牌后写入，
     *       且网关会**先删掉客户端传的同名字段**（见 {@code JwtAuthGlobalFilter}）——
     *       所以它有可信来源；</li>
     *   <li><b>未登录用 {@code X-Real-IP}</b>：同样由网关注入（写入前清除客户端伪造值，
     *       见同一过滤器），不是客户端说了算；</li>
     *   <li><b>兜底用 TCP 对端地址</b>：直连（不经网关）时至少还有一个真实对端地址，
     *       仍然优于信任请求头。</li>
     * </ol>
     *
     * <p>就算有人绕过网关直连本服务，也拿不到合法的 {@code X-User-Id}（伪造值会被当匿名处理），
     * 于是落到第 2/3 条 —— 单键限流仍生效，另外还有全站总配额兜底。
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
}
