package com.duoduo.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-015：用户提了预算但没解析出数字时，必须在回复里如实说明，不能静默当「没提预算」。
 */
class AgentChatServiceBudgetNoteTest {

    @Test
    @DisplayName("预算未解析：回复追加统一告知")
    void appendNoteWhenBudgetUnparsed() {
        String reply = "没拿到可推荐商品，请补充预算后我再帮你筛。";

        String finalReply = AgentChatService.withBudgetNote(reply, true);

        assertThat(finalReply).contains("没听清你的预算");
        assertThat(finalReply).startsWith(reply);
    }

    @Test
    @DisplayName("未提预算或已解析：不追加任何文案")
    void noNoteWhenBudgetParsedOrAbsent() {
        String reply = "这是几款符合预算的猫粮。";

        assertThat(AgentChatService.withBudgetNote(reply, false)).isEqualTo(reply);
    }

    @Test
    @DisplayName("模型已经说过同样的话：不重复追加")
    void noDuplicateNote() {
        String reply = "我好像没听清你的预算数字，可以再说一次吗？";

        assertThat(AgentChatService.withBudgetNote(reply, true)).isEqualTo(reply);
    }
}
