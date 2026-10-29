package com.duoduo.ai.dto.ai;

import java.util.List;

/**
 * AI 管家一轮对话的最终返回结构（面向前端）。
 *
 * <p>与既有流式返回（meta/delta/done 事件）互补：流式负责「边说边显示」，
 * 本类型负责一次请求结束后的完整结果，便于前端直接渲染卡片与红点。
 *
 * <p>{@code cards} 为 {@code List<AiCard>} 而不是具体卡片类型，
 * 因为一轮回复里可能同时出现「通知列表 + 动作宫格」等多种卡片；
 * 每个元素自带 {@code type} 字段供前端分派渲染组件。
 *
 * @param sessionId  服务端会话 ID
 * @param reply      纯文本回复
 * @param cards      结构化卡片（无卡片时为空列表）
 * @param badgePatch 本轮产生的红点补丁（无变化时为 null）
 */
public record AiChatResponse(
        String sessionId,
        String reply,
        List<AiCard> cards,
        BadgePatch badgePatch
) {

    /** 卡片列表归一为不可变列表，避免下游误改。 */
    public AiChatResponse {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
