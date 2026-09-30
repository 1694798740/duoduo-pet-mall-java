package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快捷问题 / 追问 / 对比单测（第五十八轮 · AI 加强项 S3）。
 *
 * <h2>核心断言：「给的建议必须点得下去」
 * 快捷问题取自真实类目、追问由当前候选推导、对比只用候选里的字段 ——
 * 三条同一个目的：**AI 给的每一句话，点下去都得有结果**。
 * 凭空的「猜你想问」在候选为空时仍然显示，点下去还是空，
 * 用户会认为这个助手在敷衍 —— 这比不给建议更伤。
 */
class SuggestionsTest {

    private static ProductCandidate c(long id, String title, long price, boolean inStock, int sales, double rating) {
        return new ProductCandidate(id, title, "副标题", price, price, sales, rating, inStock);
    }

    @Test
    void 快捷问题取自真实类目() {
        List<String> qs = Suggestions.quickQuestions(List.of("宠物主粮", "猫砂", "玩具"), 2);

        assertThat(qs).containsExactly("帮我挑一款宠物主粮", "帮我挑一款猫砂");
    }

    @Test
    void 类目取不到时不给快捷问题() {
        assertThat(Suggestions.quickQuestions(List.of(), 4)).isEmpty();
        assertThat(Suggestions.quickQuestions(null, 4)).isEmpty();
    }

    @Test
    void 候选为空时不给追问() {
        assertThat(Suggestions.followUps(List.of(), false, 3)).isEmpty();
    }

    @Test
    void 没提预算时追问里才有预算() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮", 9900, true, 10, 4.5));

        assertThat(Suggestions.followUps(candidates, false, 3)).contains("200 元以内的有吗");
        assertThat(Suggestions.followUps(candidates, true, 3)).doesNotContain("200 元以内的有吗");
    }

    @Test
    void 有缺货时才问只看有货的() {
        List<ProductCandidate> allInStock = List.of(c(1L, "幼猫粮", 9900, true, 10, 4.5));
        List<ProductCandidate> mixed = List.of(
                c(1L, "幼猫粮", 9900, true, 10, 4.5),
                c(2L, "猫玩具", 1900, false, 3, 4.0));

        assertThat(Suggestions.followUps(allInStock, true, 3)).doesNotContain("只看有货的");
        assertThat(Suggestions.followUps(mixed, true, 3)).contains("只看有货的");
    }

    @Test
    void 两款以上才建议对比() {
        List<ProductCandidate> one = List.of(c(1L, "幼猫粮", 9900, true, 10, 4.5));
        List<ProductCandidate> two = List.of(
                c(1L, "幼猫粮", 9900, true, 10, 4.5),
                c(2L, "成猫粮", 12900, true, 5, 4.8));

        assertThat(Suggestions.followUps(one, true, 3)).doesNotContain("这几款帮我对比一下");
        assertThat(Suggestions.followUps(two, true, 3)).contains("这几款帮我对比一下");
    }

    @Test
    void 对比意图能识别() {
        assertThat(Suggestions.isCompare("这两款哪个好")).isTrue();
        assertThat(Suggestions.isCompare("帮我对比一下")).isTrue();
        assertThat(Suggestions.isCompare("推荐一款猫粮")).isFalse();
    }

    @Test
    void 对比只用候选里的客观字段() {
        List<ProductCandidate> candidates = List.of(
                c(1L, "幼猫粮", 9900, true, 10, 4.5),
                c(2L, "成猫粮", 12900, false, 5, 4.8));

        String reply = Suggestions.compareReply(candidates, 3);

        assertThat(reply)
                .contains("幼猫粮").contains("99 元").contains("有货")
                .contains("成猫粮").contains("129 元").contains("缺货")
                .contains("累计售出 10 件").contains("评分 4.5");
        // 不做「哪个更好」的判断 —— 那需要成分与适配数据，本服务没有
        assertThat(reply).doesNotContain("更适合").doesNotContain("推荐买");
    }

    @Test
    void 只有一款时不做对比() {
        assertThat(Suggestions.compareReply(List.of(c(1L, "幼猫粮", 9900, true, 10, 4.5)), 3)).isNull();
        assertThat(Suggestions.compareReply(null, 3)).isNull();
    }
}
