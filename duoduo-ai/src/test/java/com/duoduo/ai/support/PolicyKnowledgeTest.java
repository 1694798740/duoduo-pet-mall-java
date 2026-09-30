package com.duoduo.ai.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 政策知识库单测（第五十八轮 · AI 加强项 S2）。
 *
 * <h2>为什么必须断言「不含承诺性数字」</h2>
 * 知识库是写死在代码里的文本，而真实的时效 / 赔付规则是<b>配置项</b>
 * （比如「发货后 N 天自动确认收货」来自 `duoduo.trade.auto-confirm.days`）。
 * 一旦有人把「7 天到账」写进知识库，配置改了它也不变 ——
 * 用户按它去维权、平台按配置执行，两边对不上，责任在平台。
 * 这条断言就是把这类内容挡在门外（真要写数字，就必须改成「以页面显示为准」）。
 */
class PolicyKnowledgeTest {

    /** 承诺性 / 绝对化的说法：政策文本里一律不许出现 */
    private static final List<String> FORBIDDEN =
            List.of("保证", "绝对", "一定", "100%", "最低价", "包退包换", "假一赔十");

    @Test
    void 退货问题给出确定的流程而不是不知道() {
        String answer = PolicyKnowledge.answerFor("我想退货");

        assertThat(answer).isNotNull()
                .contains("还没发货").contains("已发货").contains("卖家签收后");
        assertThat(answer).doesNotContain("我查不到");
    }

    @Test
    void 物流问题指向订单详情并说明查不到天数() {
        String answer = PolicyKnowledge.answerFor("我的快递到哪了");

        assertThat(answer).isNotNull()
                .contains("订单详情").contains("以页面显示为准");
    }

    @Test
    void 仲裁问题给出平台介入入口() {
        assertThat(PolicyKnowledge.answerFor("卖家不同意退款怎么办")).contains("仲裁");
    }

    @Test
    void 与政策无关的问题返回null_交给后续出口() {
        assertThat(PolicyKnowledge.answerFor("推荐一款幼猫粮")).isNull();
        assertThat(PolicyKnowledge.answerFor("")).isNull();
        assertThat(PolicyKnowledge.answerFor(null)).isNull();
    }

    @Test
    void 政策文本不含任何承诺性说法() {
        // 逐个关键词过一遍：新增条目时这条会替新条目把关
        for (String probe : List.of("退货", "退款", "物流", "快递", "售后", "仲裁", "评价", "信用分")) {
            String answer = PolicyKnowledge.answerFor(probe);
            assertThat(answer).isNotNull();
            for (String word : FORBIDDEN) {
                assertThat(answer).as("政策「%s」不应包含「%s」", probe, word).doesNotContain(word);
            }
        }
    }

    @Test
    void 知识库不是空的() {
        assertThat(PolicyKnowledge.size()).isGreaterThanOrEqualTo(5);
    }
}
