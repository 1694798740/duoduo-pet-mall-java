package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.PetBrief;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 宠物档案 → 导购上下文单测（第五十八轮 · AI 加强项 S1）。
 *
 * <h2>最要紧的一条：「档案不得覆盖用户明说的条件」</h2>
 * 用户说「买狗粮」而档案里是猫 —— 推荐必须按狗粮来。
 * 这条在链路层由 ChatService 保证（有品类约束时不注入档案关键词），
 * 这里钉住零件：{@link #recallBoost} 只产出词，不判断时机；
 * 判断时机在调用方，测试见 {@code ChatServicePetContextTest}。
 */
class PetContextTest {

    private static PetBrief pet(String name, String species, String breed, String stage, List<String> allergens) {
        return new PetBrief(1L, name, species, breed, stage, allergens);
    }

    @Test
    void 物种码转成商品库用的中文词() {
        assertThat(PetContext.recallBoost(List.of(pet("咪咪", "cat", "布偶", "幼年", List.of()))))
                .isEqualTo("猫");
        assertThat(PetContext.recallBoost(List.of(pet("旺财", "DOG", "柯基", "成犬", List.of()))))
                .isEqualTo("狗");
    }

    @Test
    void 多只宠物去重后拼接() {
        String boost = PetContext.recallBoost(List.of(
                pet("咪咪", "cat", "布偶", "幼年", List.of()),
                pet("小黑", "cat", "田园", "成猫", List.of())));
        assertThat(boost).isEqualTo("猫"); // 同物种只出一次
    }

    @Test
    void 没有档案或物种不可识别时不补词() {
        assertThat(PetContext.recallBoost(List.of())).isEmpty();
        assertThat(PetContext.recallBoost(null)).isEmpty();
        assertThat(PetContext.recallBoost(List.of(pet("未知", "dragon", null, null, List.of())))).isEmpty();
    }

    @Test
    void 档案块只写档案里有的字段() {
        String block = PetContext.promptBlock(List.of(pet("咪咪", "cat", "布偶", "幼年", List.of("鸡肉"))));

        assertThat(block)
                .contains("咪咪").contains("猫").contains("布偶").contains("幼年")
                .contains("鸡肉")
                .contains("用户明确提出的条件优先");
    }

    @Test
    void 档案缺字段时一个字都不编() {
        String block = PetContext.promptBlock(List.of(pet(null, "cat", null, null, List.of())));

        assertThat(block).contains("猫");
        // 名字 / 品种 / 阶段都没有 —— 不能出现任何占位或猜测
        assertThat(block).doesNotContain("null").doesNotContain("未知");
    }

    @Test
    void 空档案不产生档案块() {
        assertThat(PetContext.promptBlock(List.of())).isEmpty();
        assertThat(PetContext.promptBlock(null)).isEmpty();
    }

    @Test
    void 只有名称没有物种时不算可用于个性化() {
        assertThat(PetContext.usable(List.of(pet("咪咪", null, null, null, List.of())))).isFalse();
        assertThat(PetContext.usable(List.of(pet("咪咪", "cat", null, null, List.of())))).isTrue();
    }
}
