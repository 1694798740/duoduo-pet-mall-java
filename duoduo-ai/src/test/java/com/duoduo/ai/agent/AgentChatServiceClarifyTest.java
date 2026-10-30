package com.duoduo.ai.agent;

import com.duoduo.ai.client.dto.CategoryBrief;
import com.duoduo.ai.dto.AiDtos.ClarifyView;
import com.duoduo.ai.memory.SlotState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-014：「推荐一下」这类无约束请求必须返回结构化反问与可点选项，而不是只靠模型自由文本。
 *
 * <p>修复前的复现证据是 {@code scripts/ai-smoke.ps1} 3f 段：回复在追问，但 {@code clarify=null}、
 * {@code options=0}。这里的用例把同一条规则收敛成可回归的单元断言。
 */
class AgentChatServiceClarifyTest {

    private static final List<CategoryBrief> CATEGORIES = List.of(
            new CategoryBrief(1L, "宠物主粮", 1),
            new CategoryBrief(2L, "宠物零食", 1),
            new CategoryBrief(3L, "宠物用品", 1));

    @Test
    @DisplayName("无任何约束的导购请求：返回反问与 >= 2 个可点选项")
    void clarifyWhenNoConstraint() {
        ClarifyView view = AgentChatService.clarifyFor(
                SlotState.empty(), Intent.PRODUCT_SEARCH, false, CATEGORIES);

        assertThat(view).isNotNull();
        assertThat(view.question()).isNotBlank();
        assertThat(view.options()).hasSizeGreaterThanOrEqualTo(2);
        // 选项的 send 必须是下一轮能直接命中的类目名，否则点了也没结果
        assertThat(view.options()).extracting(o -> o.send())
                .containsExactly("宠物主粮", "宠物零食", "宠物用品");
    }

    @Test
    @DisplayName("用户已给宠物种类：不再反问，交给检索")
    void noClarifyWhenSpeciesKnown() {
        SlotState slots = new SlotState("cat", null, null, null, null, null,
                List.of(), List.of(), "NORMAL", null, null);

        assertThat(AgentChatService.clarifyFor(slots, Intent.PRODUCT_SEARCH, false, CATEGORIES))
                .isNull();
    }

    @Test
    @DisplayName("已经澄清过一轮：一次对话最多反问一次")
    void noClarifyWhenAlreadyClarified() {
        assertThat(AgentChatService.clarifyFor(
                SlotState.empty(), Intent.PRODUCT_SEARCH, true, CATEGORIES)).isNull();
    }

    @Test
    @DisplayName("类目取不到/不足 2 个：不反问，退回模型自由回答")
    void noClarifyWhenOptionsTooFew() {
        assertThat(AgentChatService.clarifyFor(SlotState.empty(), Intent.PRODUCT_SEARCH, false,
                List.of(new CategoryBrief(1L, "宠物主粮", 1)))).isNull();
        assertThat(AgentChatService.clarifyFor(SlotState.empty(), Intent.PRODUCT_SEARCH, false,
                List.of())).isNull();
        assertThat(AgentChatService.clarifyFor(SlotState.empty(), Intent.PRODUCT_SEARCH, false,
                null)).isNull();
    }

    @Test
    @DisplayName("非导购意图（闲聊/订单/健康）：不反问")
    void noClarifyForOtherIntents() {
        for (Intent intent : List.of(Intent.CHITCHAT, Intent.ORDER_SUPPORT, Intent.PET_HEALTH)) {
            assertThat(AgentChatService.clarifyFor(SlotState.empty(), intent, false, CATEGORIES))
                    .as("intent=%s", intent)
                    .isNull();
        }
    }
}
