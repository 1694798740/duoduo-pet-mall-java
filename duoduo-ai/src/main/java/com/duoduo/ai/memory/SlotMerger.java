package com.duoduo.ai.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 槽位合并（2026-10-28）：本轮明确表达 > 会话槽位 > 长期档案 > 默认值。
 *
 * <p>这条优先级是硬规则：用户本轮说「我要狗粮」，不能因为档案里养猫就推猫粮；
 * 本轮没说预算，才回落到会话/长期记忆。
 */
@Component
public class SlotMerger {

    public SlotState merge(SlotState currentTurn, SlotState session, SlotState longTerm) {
        SlotState s = session == null ? SlotState.empty() : session;
        SlotState l = longTerm == null ? SlotState.empty() : longTerm;
        SlotState c = currentTurn == null ? SlotState.empty() : currentTurn;
        return new SlotState(
                firstText(c.species(), s.species(), l.species()),
                firstText(c.lifeStage(), s.lifeStage(), l.lifeStage()),
                firstText(c.breed(), s.breed(), l.breed()),
                firstLong(c.budgetMinCents(), s.budgetMinCents(), l.budgetMinCents()),
                firstLong(c.budgetMaxCents(), s.budgetMaxCents(), l.budgetMaxCents()),
                firstLong(c.categoryId(), s.categoryId(), l.categoryId()),
                firstList(c.allergens(), s.allergens(), l.allergens()),
                firstList(c.symptoms(), s.symptoms(), l.symptoms()),
                firstText(c.urgency(), s.urgency(), l.urgency(), "NORMAL"),
                // 健康备注与称呼不参与「本轮覆盖」：它们不是本轮说出来的槽位，
                // 而是用户确认过的长期事实，只从会话/长期记忆回落
                firstText(s.healthNotes(), l.healthNotes()),
                firstText(s.nickname(), l.nickname()));
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Long firstLong(Long... values) {
        for (Long v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static List<String> firstList(List<String>... values) {
        for (List<String> v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return List.of();
    }
}
