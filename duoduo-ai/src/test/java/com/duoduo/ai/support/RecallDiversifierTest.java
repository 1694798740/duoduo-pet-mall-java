package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 召回去重与多样性单测（第五十八轮 · AI 加强项第 22 条）。
 *
 * <h2>为什么这条值得单独测</h2>
 * 「推荐里翻来覆去就一款」是用户能直接感知、但日志里没有任何错误的那类问题 ——
 * 召回位被同款的不同规格吃掉了。修法本身不复杂，难的是**判定「同款」的规则**
 * 极易被后来的改动悄悄破坏（比如有人为了修别的 bug 把去规格的正则放宽），
 * 而破坏后的表现只是「推荐又变单调了」，没人会联想到这里。
 */
class RecallDiversifierTest {

    private static ProductCandidate c(long id, String title, long price) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, true);
    }

    @Test
    void 同款不同规格只保留一件() {
        List<ProductCandidate> raw = List.of(
                c(1L, "幼猫粮 500g", 9900),
                c(2L, "幼猫粮 1kg", 18900),
                c(3L, "幼猫粮 2kg", 32900),
                c(4L, "猫玩具球", 1900));

        List<ProductCandidate> kept = RecallDiversifier.diversify(raw, 8);

        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(ProductCandidate::title)
                .containsExactly("幼猫粮 500g", "猫玩具球");
    }

    @Test
    void 同款保留价格更低的那件() {
        // 顺序反过来：贵的先来，也应被更便宜的同款替换掉
        List<ProductCandidate> raw = List.of(
                c(1L, "幼猫粮 2kg", 32900),
                c(2L, "幼猫粮 500g", 9900));

        List<ProductCandidate> kept = RecallDiversifier.diversify(raw, 8);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).minPrice()).isEqualTo(9900);
    }

    @Test
    void 不同款全部保留且顺序不变() {
        List<ProductCandidate> raw = List.of(
                c(1L, "猫粮", 9900),
                c(2L, "狗粮", 12900),
                c(3L, "猫砂", 4900));

        assertThat(RecallDiversifier.diversify(raw, 8))
                .extracting(ProductCandidate::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 超过上限时截断() {
        List<ProductCandidate> raw = List.of(
                c(1L, "猫粮", 9900), c(2L, "狗粮", 12900),
                c(3L, "猫砂", 4900), c(4L, "猫爬架", 19900));

        assertThat(RecallDiversifier.diversify(raw, 2)).hasSize(2);
    }

    @Test
    void 空候选与零上限都不炸() {
        assertThat(RecallDiversifier.diversify(List.of(), 8)).isEmpty();
        assertThat(RecallDiversifier.diversify(null, 8)).isEmpty();
        assertThat(RecallDiversifier.diversify(List.of(c(1L, "猫粮", 9900)), 0)).isEmpty();
    }

    @Test
    void 标题为空时按id去重_不整批丢掉() {
        List<ProductCandidate> raw = List.of(
                c(1L, null, 9900),
                c(2L, "", 4900));

        assertThat(RecallDiversifier.diversify(raw, 8)).hasSize(2);
    }

    @Test
    void 标点与全角数字不影响同款判定() {
        assertThat(RecallDiversifier.titleKey("幼猫粮（500g）")).isEqualTo(RecallDiversifier.titleKey("幼猫粮500g"));
        assertThat(RecallDiversifier.titleKey("幼猫粮 １kg")).isEqualTo(RecallDiversifier.titleKey("幼猫粮1kg"));
    }
}
