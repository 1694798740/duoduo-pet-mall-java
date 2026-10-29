package com.duoduo.ai.guardrail;

import com.duoduo.ai.memory.SlotState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalRedFlagGuardTest {

    private final MedicalRedFlagGuard guard = new MedicalRedFlagGuard();

    @Test
    void detectsRedFlags() {
        assertThat(guard.isRedFlag("狗狗便血两天了")).isTrue();
        assertThat(guard.isRedFlag("幼犬不吃不喝没精神")).isTrue();
        assertThat(guard.isRedFlag("猫误食了百合")).isTrue();
        assertThat(guard.isRedFlag("推荐一款幼猫粮")).isFalse();
    }

    @Test
    void safeAnswerStopsProductRecommendation() {
        var answer = guard.safeAnswer(new SlotState("cat", null, null, null, null,
                null, java.util.List.of(), java.util.List.of("呕吐"), "RED_FLAG", null, null));
        assertThat(answer.reply()).contains("兽医").contains("不能替代");
        assertThat(answer.summary()).contains("医疗红线");
    }
}
