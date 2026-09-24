package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.dto.AiDtos.ChatTurn;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 导购对话编排（A9）。
 *
 * <p>流水线：**召回 → 组织**。召回（真实商品）交给商品域；组织（说人话）交给 LLM 或
 * mock 规则。模型拿到的提示词里只有候选列表，且系统提示明确「只能推荐候选中的商品」——
 * 结构上杜绝编造（验收①），而不是事后用正则去抓幻觉。
 *
 * <p>降级三形态（见 {@code AiApplication} 类注释）：mock / unconfigured 503 / 超时 503。
 * mock 的回复也是**从真实候选拼出来的**，与真实模式共享召回链路 ——
 * 冒烟在 mock 下验证的「推荐必须来自商品库」，对真实模式同样成立。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 系统提示：把「不得编造」写成硬约束，并给出价格口径（分）与引导话术要求（验收②） */
    private static final String SYSTEM_PROMPT = """
            你是宠物商店「多多宠物」的导购助手「多多」。规则：
            1. 只能推荐【候选商品列表】里出现的商品，绝不虚构不存在的商品、价格或促销；
            2. 价格单位是「分」，请换算成「元」表述（例如 19900 分 = 199 元）；
            3. 用户提到下单 / 加购 / 购买时，引导用户登录后到对应商品页操作，不要承诺代下单；
            4. 没有合适候选时如实说明，并请用户换个描述，不要硬推；
            5. 回复简洁友好，控制在 150 字以内。
            【候选商品列表】
            """;

    private final ProductSearchClient productSearchClient;
    private final DashScopeClient dashScopeClient;
    private final boolean mockEnabled;

    public ChatService(ProductSearchClient productSearchClient,
                       DashScopeClient dashScopeClient,
                       @Value("${duoduo.ai.mock-enabled:true}") boolean mockEnabled) {
        this.productSearchClient = productSearchClient;
        this.dashScopeClient = dashScopeClient;
        this.mockEnabled = mockEnabled;
    }

    public ChatResponse chat(String message, List<ChatTurn> history) {
        // ① 召回：真实商品库（商品域内部接口）。商品域不可用 → 503，绝不用空候选硬答
        List<ProductCandidate> candidates;
        try {
            candidates = productSearchClient.candidates(message, 8).data();
        } catch (Exception ex) {
            log.error("候选召回失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后再试");
        }
        if (candidates == null) {
            candidates = List.of();
        }

        // ② 组织回复
        if (mockEnabled) {
            return new ChatResponse(mockReply(message, candidates), toRecommendations(candidates), "mock");
        }
        if (candidates.isEmpty()) {
            // 无候选时也如实说没有 —— 空候选喂给模型反而容易诱导它编
            return new ChatResponse("暂时没找到匹配的商品，麻烦换个描述试试，比如「幼犬狗粮」或「猫玩具」。", List.of(), "llm");
        }
        String prompt = SYSTEM_PROMPT + candidatesJson(candidates);
        String reply = dashScopeClient.chat(prompt, toMessages(history), message);
        return new ChatResponse(reply, toRecommendations(candidates), "llm");
    }

    /** 历史轮次 → OpenAI 兼容 messages：role 规范化（只认 user，其余一律 assistant），空内容直接丢弃 */
    private static List<Map<String, String>> toMessages(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(t -> t != null && t.content() != null && !t.content().isBlank())
                .map(t -> Map.of("role", "user".equalsIgnoreCase(t.role() == null ? "" : t.role().trim())
                        ? "user" : "assistant", "content", t.content()))
                .toList();
    }

    /**
     * mock 回复：确定性规则把召回结果拼成导购话术 —— 每个推荐都真实存在。
     * 关键词不存在时如实说没有（这也是验收①「不得编造」的一部分）。
     */
    private static String mockReply(String message, List<ProductCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "暂时没找到与「" + message + "」匹配的商品。换个说法试试，比如「幼犬狗粮」「猫零食」？";
        }
        StringBuilder sb = new StringBuilder("为你找到 " + candidates.size() + " 款相关商品：");
        int top = Math.min(3, candidates.size());
        for (int i = 0; i < top; i++) {
            ProductCandidate c = candidates.get(i);
            sb.append("「").append(c.title()).append("」")
                    .append(c.minPrice() / 100).append("元起");
            if (!c.inStock()) {
                sb.append("（暂时缺货）");
            }
            sb.append("；");
        }
        sb.append("如需下单，请登录后在商品页加购。");
        return sb.toString();
    }

    private static String candidatesJson(List<ProductCandidate> candidates) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            ProductCandidate c = candidates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(c.id())
                    .append(",\"title\":\"").append(escape(c.title())).append('"')
                    .append(",\"priceCents\":").append(c.minPrice())
                    .append(",\"inStock\":").append(c.inStock()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<ProductRecommendation> toRecommendations(List<ProductCandidate> candidates) {
        return candidates.stream()
                .map(c -> new ProductRecommendation(c.id(), c.title(), c.subtitle(),
                        c.minPrice(), c.maxPrice(), c.inStock()))
                .toList();
    }
}
