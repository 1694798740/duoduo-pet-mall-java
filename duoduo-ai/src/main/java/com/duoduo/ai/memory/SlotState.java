package com.duoduo.ai.memory;

import java.util.List;

/**
 * 会话槽位（2026-10-28）。
 *
 * <p>槽位是「结构化事实」，不是聊天记录：模型下一轮只依赖这些字段，
 * 不再靠把历史消息拼成关键词。
 */
public record SlotState(
        String species,
        String lifeStage,
        String breed,
        Long budgetMinCents,
        Long budgetMaxCents,
        Long categoryId,
        List<String> allergens,
        List<String> symptoms,
        String urgency,
        /**
         * 用户确认过的高风险健康备注（来自 {@code t_ai_user_memory.memory_json.healthNotes}）。
         *
         * <p>为什么放进槽位：推荐商品时「这只猫肠胃敏感」与「预算 200 元」同等重要，
         * 只把备注留在记忆接口里、不进提示词，等于用户填了但 AI 永远看不到。
         * 它只是**参考事实**，不是诊断结论；Prompt 里会包在定界符中并明确「不能替代兽医」。
         */
        String healthNotes,
        /**
         * 用户希望被怎么称呼（来自 {@code memory_json.nickname}，为空则不带称呼）。
         *
         * <p>刻意**不从对话里自动抽取名字**：模型很容易把「我家狗叫小七」记成「用户叫小七」，
         * 那是隐私数据里最不该猜的一类。名字只由用户在记忆页显式保存。
         */
        String nickname
) {

    public static SlotState empty() {
        return new SlotState(null, null, null, null, null, null,
                List.of(), List.of(), "NORMAL", null, null);
    }

    public boolean hasSpecies() {
        return species != null && !species.isBlank();
    }

    public boolean hasBudget() {
        return budgetMinCents != null || budgetMaxCents != null;
    }

    public boolean hasCategory() {
        return categoryId != null;
    }
}
