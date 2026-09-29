package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出护栏单测（第五十七轮 · M6 · M4 配套）。
 *
 * <h2>为什么护栏只能在单测里验证</h2>
 * 护栏拦的是「模型的坏回复」，而 mock 模式的回复直接从候选事实拼装，天然不含违规词 ——
 * 端到端冒烟在 mock 下永远打不到护栏，打到的那个假绿比没有测试更危险（与本仓库
 * 「ai-smoke 预算断言只在 llm 模式生效 = 长期空转」是同一类教训）。所以护栏的行为
 * 契约在这里用纯单测钉死：喂一段带违规词的回复，断言它被拦、且替换结果是事实版。
 */
class ReplyGuardTest {

    private final ReplyGuard guard = new ReplyGuard(null);

    private static ProductCandidate cand(long id, String title, long minPrice, long maxPrice) {
        return new ProductCandidate(id, title, "subtitle", minPrice, maxPrice, 10, 4.8, true);
    }

    private static final List<ProductCandidate> CANDIDATES = List.of(
            cand(1L, "皇家幼猫粮", 19900L, 29900L),
            cand(2L, "麦富迪冻干双拼", 9900L, 15900L));

    // ---------- 合规词 ----------

    @Test
    void 绝对化用语被拦() {
        var v = guard.inspect("这款猫粮绝对有效，吃了保证不生病", CANDIDATES);
        assertThat(v.blocked()).isTrue();
        assertThat(v.reason()).contains("合规词");
        // 拦截后不是拒绝回答，而是替换为事实版回复（over-refusal 防线）
        assertThat(v.reply()).contains("皇家幼猫粮");
        assertThat(v.reply()).doesNotContain("绝对").doesNotContain("保证");
    }

    @Test
    void 词表全覆盖逐一命中() {
        for (String phrase : new String[] {"最低价", "全网最", "100%", "百分之百", "百分百",
                "包治", "根治", "假一赔十", "药到病除", "永不", "史上最", "全城最"}) {
            var v = guard.inspect("本文说 " + phrase + " 好用", CANDIDATES);
            assertThat(v.blocked()).as("应命中词: %s", phrase).isTrue();
        }
    }

    @Test
    void 主观推荐语不拦_over_refusal防线() {
        // 「最好/最适合」是导购场景的正常表达，拦掉它们 AI 就不会说人话了
        var v = guard.inspect("这款最适合幼猫，性价比也很不错", CANDIDATES);
        assertThat(v.blocked()).isFalse();
        assertThat(v.reply()).isEqualTo("这款最适合幼猫，性价比也很不错");
    }

    // ---------- 价格一致性 ----------

    @Test
    void 候选里没有的价格被拦() {
        var v = guard.inspect("这款现在只要 328 元，非常划算", CANDIDATES);
        assertThat(v.blocked()).isTrue();
        assertThat(v.reason()).contains("价格不一致");
        assertThat(v.reply()).contains("199");
    }

    @Test
    void 候选真实价格放行_元写法() {
        assertThat(guard.inspect("这款 199 元起，还有 299 元的规格", CANDIDATES).blocked()).isFalse();
    }

    @Test
    void 候选真实价格放行_货币符号写法() {
        assertThat(guard.inspect("¥99 就能带走麦富迪冻干双拼", CANDIDATES).blocked()).isFalse();
    }

    @Test
    void 普通数字不算价格_不误伤() {
        // 「2 岁」「3 袋」没有货币信号，不该被当成价格拦下
        assertThat(guard.inspect("适合 2 岁的猫，一天喂 3 袋", CANDIDATES).blocked()).isFalse();
    }

    // ---------- 事实版回复（同时是 mock 模式的生成器） ----------

    @Test
    void 事实版回复_只说候选事实() {
        String reply = ReplyGuard.factsReply(CANDIDATES);
        assertThat(reply).contains("2 款相关商品")
                .contains("皇家幼猫粮").contains("199")
                .contains("麦富迪冻干双拼").contains("99");
    }

    @Test
    void 事实版回复_空候选如实说没找到() {
        assertThat(ReplyGuard.factsReply(List.of())).contains("没找到");
        assertThat(ReplyGuard.factsReply(null)).contains("没找到");
    }

    @Test
    void 事实版回复_缺货标注() {
        List<ProductCandidate> withOos = List.of(
                cand(9L, "缺货款", 1000L, 1000L));
        // 重建一个缺货候选（上面的 helper 默认 inStock=true）
        withOos = List.of(new ProductCandidate(9L, "缺货款", "subtitle", 1000L, 1000L, 0, 4.0, false));
        assertThat(ReplyGuard.factsReply(withOos)).contains("暂时缺货");
    }

    // ---------- 词表非空守卫 ----------

    @Test
    void 词表不是空表() {
        assertThat(guard.riskyPhraseCount()).isGreaterThanOrEqualTo(10);
    }
}
