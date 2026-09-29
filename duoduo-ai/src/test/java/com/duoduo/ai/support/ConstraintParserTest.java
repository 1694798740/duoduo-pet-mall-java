package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.CategoryBrief;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 约束解析单测（第五十七轮 · M6）。
 *
 * <h2>为什么这些用例必须存在</h2>
 * 预算解析的失败形态是「看起来像正常」：解析错了不会报错，只会**推荐错商品**或
 * **悄悄不按预算筛**。端到端冒烟只能覆盖几句话术，三种状态（解析到 / 没提 / 提了没解析出）
 * 的边界必须在这里逐个钉死。
 *
 * <h2>「最便宜」误判回归</h2>
 * 「那最便宜的是哪个？」里没有预算，但旧版信号词表里的「便宜」会让它被打上
 * 「我没听清你的预算」的前缀 —— 一句正常追问被当成预算失效，用户每问一次最便宜
 * 就看一次道歉。这条用例防止它回归。
 */
class ConstraintParserTest {

    private ConstraintParser parser;

    @BeforeEach
    void setUp() {
        CategoryCatalog catalog = mock(CategoryCatalog.class);
        when(catalog.match(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            if (text != null && text.contains("猫粮")) {
                return List.of(new CategoryBrief(1L, "宠物主粮", 1));
            }
            return List.of();
        });
        parser = new ConstraintParser(catalog);
    }

    // ---------- 状态 1：解析到了 ----------

    @Test
    void 上限_预算两百元以内() {
        var c = parser.parse("预算 200 元以内的猫粮，有推荐吗？");
        assertThat(c.budgetMax()).isEqualTo(20000L);
        assertThat(c.budgetMin()).isNull();
        assertThat(c.hasBudget()).isTrue();
        assertThat(c.budgetUnparsed()).isFalse();
    }

    @Test
    void 上限_不超过写法() {
        assertThat(parser.parse("不超过300块的零食").budgetMax()).isEqualTo(30000L);
    }

    /**
     * 同位重匹配回归：「预算 200」会被 MAX 与 MIN 两套正则各命中一次，
     * 不去重就会得出 min=max=200，把 99~159 元的便宜货误杀掉。
     */
    @Test
    void 上限写法不得同时生成下限() {
        var c = parser.parse("预算 200 元以内的猫粮");
        assertThat(c.budgetMax()).isEqualTo(20000L);
        assertThat(c.budgetMin()).as("「预算 200 以内」不该产生下限").isNull();
    }

    @Test
    void 裸预算写法也不得生成下限() {
        var c = parser.parse("预算 200 的狗粮");
        assertThat(c.budgetMax()).isEqualTo(20000L);
        assertThat(c.budgetMin()).isNull();
    }

    /** 真·上下限同时给（两段不同文本）时两者都保留 */
    @Test
    void 分开写明的上下限都保留() {
        var c = parser.parse("至少 100 元，不超过 300 元的猫粮");
        assertThat(c.budgetMin()).isEqualTo(10000L);
        assertThat(c.budgetMax()).isEqualTo(30000L);
    }

    @Test
    void 下限_三百元以上() {
        var c = parser.parse("300 元以上的猫粮有什么");
        assertThat(c.budgetMin()).isEqualTo(30000L);
        assertThat(c.budgetMax()).isNull();
    }

    @Test
    void 区间_一百到三百元() {
        var c = parser.parse("预算 100-300 元的猫粮");
        assertThat(c.budgetMin()).isEqualTo(10000L);
        assertThat(c.budgetMax()).isEqualTo(30000L);
    }

    @Test
    void 区间_带到字写法() {
        var c = parser.parse("100 到 200 元的玩具");
        assertThat(c.budgetMin()).isEqualTo(10000L);
        assertThat(c.budgetMax()).isEqualTo(20000L);
    }

    @Test
    void 小数金额() {
        assertThat(parser.parse("预算 99.9 元以内的狗粮").budgetMax()).isEqualTo(9990L);
    }

    @Test
    void 品类_猫粮命中主粮类目() {
        var c = parser.parse("预算 200 元以内的猫粮");
        assertThat(c.categoryIds()).containsExactly(1L);
        assertThat(c.categoryNames()).containsExactly("宠物主粮");
    }

    // ---------- 状态 2：没提预算 ----------

    @Test
    void 没提预算_正常话术不算未解析() {
        var c = parser.parse("推荐一款适合幼犬吃的狗粮");
        assertThat(c.hasBudget()).isFalse();
        assertThat(c.budgetUnparsed()).isFalse();
    }

    /** 「最便宜」是问候选里最便宜的商品，不是表达预算 —— 误判会往回复里塞「没听清预算」前缀 */
    @Test
    void 最便宜的追问_不算预算未解析() {
        var c = parser.parse("那最便宜的是哪个？");
        assertThat(c.hasBudget()).isFalse();
        assertThat(c.budgetUnparsed()).isFalse();
    }

    @Test
    void 最便宜的追问_带上下文也不误判() {
        assertThat(parser.parse("这几款里最便宜的是哪款？").budgetUnparsed()).isFalse();
    }

    // ---------- 状态 3：提了但没解析出来（必须告知） ----------

    @Test
    void 便宜点就行_算预算未解析() {
        assertThat(parser.parse("有没有便宜划算的猫粮").budgetUnparsed()).isTrue();
    }

    @Test
    void 两百来块_算预算未解析() {
        assertThat(parser.parse("两百来块的猫粮").budgetUnparsed()).isTrue();
    }

    // ---------- 合理性校验：越界数字当没解析（防止年份/数量被当预算） ----------

    @Test
    void 超大数字_当未解析处理() {
        var c = parser.parse("预算 5000000 元的猫粮");
        assertThat(c.hasBudget()).isFalse();
        assertThat(c.budgetUnparsed()).isTrue();
    }

    @Test
    void 零元_当未解析处理() {
        var c = parser.parse("预算 0 元以内");
        assertThat(c.hasBudget()).isFalse();
        assertThat(c.budgetUnparsed()).isTrue();
    }

    // ---------- 矛盾约束：以更严上限为准，下限丢掉 ----------

    @Test
    void 矛盾上下限_丢下限保上限() {
        var c = parser.parse("至少 300 元，不超过 200 元的猫粮");
        assertThat(c.budgetMin()).isNull();
        assertThat(c.budgetMax()).isEqualTo(20000L);
    }
}
