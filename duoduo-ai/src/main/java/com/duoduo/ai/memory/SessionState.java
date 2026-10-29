package com.duoduo.ai.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 会话状态（2026-10-28）。
 *
 * <p>只保留「模型下一轮需要的事实」：最近消息、槽位、已推荐商品、摘要。
 * 完整审计消息另落 {@code t_ai_chat_message}，不把 Redis 当数据库。
 */
public record SessionState(
        String sessionId,
        Long userId,
        List<ChatMessage> messages,
        SlotState slots,
        List<Long> recommendedIds,
        String summary,
        int turnCount,
        String lastIntent,
        long updatedAt
) {

    private static final int MAX_MESSAGES = 10;

    public static SessionState create(String sessionId, Long userId) {
        return new SessionState(sessionId, userId, List.of(), SlotState.empty(),
                List.of(), null, 0, null, System.currentTimeMillis());
    }

    public SessionState withUserMessage(String message) {
        return withMessage("user", message);
    }

    public SessionState withAssistantMessage(String message) {
        return withMessage("assistant", message);
    }

    private SessionState withMessage(String role, String content) {
        List<ChatMessage> list = new ArrayList<>(messages == null ? List.of() : messages);
        list.add(new ChatMessage(role, content, System.currentTimeMillis()));
        if (list.size() > MAX_MESSAGES) {
            list = new ArrayList<>(list.subList(list.size() - MAX_MESSAGES, list.size()));
        }
        return new SessionState(sessionId, userId, List.copyOf(list), slots,
                recommendedIds, summary, turnCount, lastIntent, System.currentTimeMillis());
    }

    public SessionState withSlots(SlotState newSlots) {
        return new SessionState(sessionId, userId, messages, newSlots == null ? SlotState.empty() : newSlots,
                recommendedIds, summary, turnCount, lastIntent, System.currentTimeMillis());
    }

    public SessionState withIntent(String intent) {
        return new SessionState(sessionId, userId, messages, slots, recommendedIds, summary,
                turnCount, intent, System.currentTimeMillis());
    }

    public SessionState withRecommendedIds(List<Long> ids) {
        return new SessionState(sessionId, userId, messages, slots,
                ids == null ? List.of() : List.copyOf(ids), summary, turnCount, lastIntent,
                System.currentTimeMillis());
    }

    public SessionState incrementTurn() {
        return new SessionState(sessionId, userId, messages, slots, recommendedIds, summary,
                turnCount + 1, lastIntent, System.currentTimeMillis());
    }

    public SessionState withSummary(String newSummary, List<ChatMessage> keptMessages) {
        return new SessionState(sessionId, userId, keptMessages, slots, recommendedIds,
                newSummary, turnCount, lastIntent, System.currentTimeMillis());
    }
}
