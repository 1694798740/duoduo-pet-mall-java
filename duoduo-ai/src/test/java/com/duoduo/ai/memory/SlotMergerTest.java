package com.duoduo.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotMergerTest {

    private final SlotMerger merger = new SlotMerger();

    @Test
    void currentTurnOverridesSessionAndLongTerm() {
        SlotState current = new SlotState("dog", "BABY", null, 10000L, 20000L,
                null, List.of("鸡肉"), List.of("腹泻"), "WATCH", null, null);
        SlotState session = new SlotState("cat", "ADULT", null, null, 50000L,
                null, List.of("谷物"), List.of(), "NORMAL", null, null);
        SlotState memory = new SlotState("cat", "SENIOR", "布偶", null, 90000L,
                null, List.of(), List.of(), "NORMAL", "肠胃敏感", "小明");

        SlotState merged = merger.merge(current, session, memory);

        assertThat(merged.species()).isEqualTo("dog");
        assertThat(merged.lifeStage()).isEqualTo("BABY");
        assertThat(merged.budgetMinCents()).isEqualTo(10000L);
        assertThat(merged.budgetMaxCents()).isEqualTo(20000L);
        assertThat(merged.allergens()).containsExactly("鸡肉");
        assertThat(merged.symptoms()).containsExactly("腹泻");
        // 健康备注与称呼不是本轮槽位，只从长期档案回落
        assertThat(merged.healthNotes()).isEqualTo("肠胃敏感");
        assertThat(merged.nickname()).isEqualTo("小明");
    }

    @Test
    void fallsBackToSessionThenMemory() {
        SlotState current = SlotState.empty();
        SlotState session = new SlotState(null, "ADULT", null, null, null,
                null, List.of(), List.of(), "NORMAL", null, null);
        SlotState memory = new SlotState("cat", "SENIOR", "布偶", null, null,
                null, List.of("牛肉"), List.of(), "NORMAL", null, null);

        SlotState merged = merger.merge(current, session, memory);

        assertThat(merged.species()).isEqualTo("cat");
        assertThat(merged.lifeStage()).isEqualTo("ADULT");
        assertThat(merged.breed()).isEqualTo("布偶");
        assertThat(merged.allergens()).containsExactly("牛肉");
    }
}
