package com.duoduo.ai.agent;

import com.duoduo.ai.dto.AiDtos.ClarifyView;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.duoduo.ai.dto.ai.AiCard;
import com.duoduo.ai.dto.ai.BadgePatch;

import java.util.List;

/**
 * 一轮对话的最终结果（面向前端）。
 *
 * <p>在原有「回复 + 商品推荐」之上补充了管家链路的产物：
 * <ul>
 *   <li>{@code cards}：服务端装配的结构化卡片（通知 / 券 / 订单 / 宫格），
 *       模型只贡献 card_refs，不贡献卡片内容；</li>
 *   <li>{@code badgePatch}：本轮产生的红点补丁；null = 没有角标变化；</li>
 *   <li>{@code loginUrl}：本轮因「游客轮次用尽 / 目标页需要登录」而返回的登录地址，
 *       由服务端用 RouteRegistry 生成，带站内 next；</li>
 *   <li>{@code clarify}：非空表示本轮是**结构化反问**（DEF-014），
 *       前端应把选项渲染成可点按钮，而不是让用户对着输入框重新打字。</li>
 * </ul>
 */
public record AgentResult(
        boolean success,
        String sessionId,
        String reply,
        List<ProductRecommendation> recommendations,
        List<Long> recommendedIds,
        List<AiCard> cards,
        BadgePatch badgePatch,
        String loginUrl,
        ClarifyView clarify,
        String errorCode,
        String errorMessage
) {

    public AgentResult {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        recommendedIds = recommendedIds == null ? List.of() : List.copyOf(recommendedIds);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    public static AgentResult ok(String sessionId, String reply,
                                 List<ProductRecommendation> recommendations,
                                 List<Long> ids,
                                 List<AiCard> cards,
                                 BadgePatch badgePatch,
                                 String loginUrl) {
        return ok(sessionId, reply, recommendations, ids, cards, badgePatch, loginUrl, null);
    }

    public static AgentResult ok(String sessionId, String reply,
                                 List<ProductRecommendation> recommendations,
                                 List<Long> ids,
                                 List<AiCard> cards,
                                 BadgePatch badgePatch,
                                 String loginUrl,
                                 ClarifyView clarify) {
        return new AgentResult(true, sessionId, reply, recommendations, ids,
                cards, badgePatch, loginUrl, clarify, null, null);
    }

    public static AgentResult fail(String code, String message) {
        return new AgentResult(false, null, null, List.of(), List.of(),
                List.of(), null, null, null, code, message);
    }
}
