package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock 形态的话术与护栏可达性（第五十八轮 · AI 加强项第 21 条）。
 *
 * <h2>核心断言：mock 的话术要「能被护栏拦住」</h2>
 * 早先 mock 直接复用 {@link ReplyGuard#factsReply} —— 那正是护栏命中后的替换话术，
 * 于是「拿护栏的产物去问护栏」永远合规，mock 形态下护栏<b>结构上不可达</b>。
 * 这里反过来钉住：mock 的话术是独立措辞（不等于事实版），
 * 且它自带的每个价格都能在候选里找到（所以正常情况下护栏放行）。
 * 两条一起才构成「护栏在 mock 下可达且不过严」。
 */
class MockReplyTest {

    private static ProductCandidate c(long id, String title, long price, boolean inStock) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, inStock);
    }

    private final ReplyGuard guard = new ReplyGuard(null);

    @Test
    void 话术不是护栏的事实版回复() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮", 9900, true));

        String mock = MockReply.compose(candidates);

        assertThat(mock).isNotEqualTo(ReplyGuard.factsReply(candidates));
        assertThat(mock).contains("幼猫粮");
    }

    @Test
    void 话术自带的价都在候选里_护栏放行() {
        List<ProductCandidate> candidates = List.of(
                c(1L, "幼猫粮", 9900, true),
                c(2L, "猫玩具", 1900, true));

        ReplyGuard.Verdict verdict = guard.inspect(MockReply.compose(candidates), candidates);

        assertThat(verdict.blocked()).isFalse();
    }

    @Test
    void 话术里出现违规词时护栏会拦下并换事实版() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮", 9900, true));

        // 模拟「有人把话术模板改成了绝对化表述」—— 这正是护栏该起作用的场景
        String broken = "这款绝对适合你家猫，只要 " + MockReply.yuan(9900) + " 元。";
        ReplyGuard.Verdict verdict = guard.inspect(broken, candidates);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.reply()).isEqualTo(ReplyGuard.factsReply(candidates));
    }

    @Test
    void 话术里的价写错时护栏会拦下() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮", 9900, true));

        String wrongPrice = "推荐「幼猫粮」89 元。";
        ReplyGuard.Verdict verdict = guard.inspect(wrongPrice, candidates);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.reason()).contains("价格不一致");
    }

    @Test
    void 空候选时给的是引导而不是空话术() {
        assertThat(MockReply.compose(List.of())).contains("换个描述");
        assertThat(MockReply.compose(null)).contains("换个描述");
    }

    @Test
    void 缺货时如实说明() {
        String reply = MockReply.compose(List.of(c(1L, "幼猫粮", 9900, false)));
        assertThat(reply).contains("缺货");
    }
}
