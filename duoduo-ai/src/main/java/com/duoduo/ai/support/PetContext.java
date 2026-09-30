package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.PetBrief;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宠物档案 → 导购上下文（第五十八轮 · AI 加强项 S1）。
 *
 * <h2>为什么值得做</h2>
 * 「给我推荐点吃的」这句话本身没有品类信息，之前只能靠澄清反问或按字面召回（结果往往是空的）。
 * 而库里其实已经存着「这个用户养的是一只 3 个月的布偶猫」——
 * 档案现成、AI 此前只是没传 userId 去取。个性化最有效的一步往往不是换模型，
 * 而是把**已经存在的数据**接进来。
 *
 * <h2>三条硬边界（防止「个性化」变成「越权」或「编造」）
 * <ol>
 *   <li><b>档案只做补充，不做覆盖</b>：用户明确说了品类 / 预算时，档案不参与召回关键词 ——
 *       他说「买狗粮」而你因为他养猫就推猫粮，那不是个性化，是听不懂人话；</li>
 *   <li><b>拿不到档案就当没有</b>：个性化是可选增强，用户域抖动不该让导购整体答不了；</li>
 *   <li><b>只说档案里有的事实</b>：档案没有的字段（比如年龄）就一个字都不提 ——
 *       用「你家猫应该 3 个月了吧」这种猜测去冒充个性化，正好踩在不得编造的底线上。</li>
 * </ol>
 */
public final class PetContext {

    /** 物种码 → 中文检索词（与商品库标题用词对齐；查不到时退回原值） */
    private static final Map<String, String> SPECIES_WORDS = Map.of(
            "cat", "猫", "dog", "狗", "small_pet", "小宠",
            "bird", "鸟", "fish", "鱼", "reptile", "爬行", "other", "");

    private PetContext() {
    }

    /**
     * 召回关键词补充：只在用户**没有指定品类**时生效（见类注释第 1 条）。
     *
     * @return 需要追加到查询串里的词；没有可用信息时返回空串
     */
    public static String recallBoost(List<PetBrief> pets) {
        if (pets == null || pets.isEmpty()) {
            return "";
        }
        Set<String> words = new LinkedHashSet<>();
        for (PetBrief pet : pets) {
            String word = speciesWord(pet.species());
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return String.join(" ", words);
    }

    /**
     * 给模型的档案块（客观陈述，不含任何推测）。
     *
     * <p>末句「用户明确提出的条件优先」是刻意写进去的：不给模型这个口径，
     * 它会在「用户说买狗粮」和「档案说养猫」之间自己选一个，而选错的代价是用户觉得它答非所问。
     */
    public static String promptBlock(List<PetBrief> pets) {
        if (pets == null || pets.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n【用户宠物档案（仅作参考，用户明确提出的条件优先）】\n");
        for (PetBrief pet : pets) {
            List<String> parts = new ArrayList<>();
            if (notBlank(pet.name())) {
                parts.add(pet.name());
            }
            if (notBlank(pet.species())) {
                parts.add(speciesWord(pet.species()).isEmpty() ? pet.species() : speciesWord(pet.species()));
            }
            if (notBlank(pet.breed())) {
                parts.add(pet.breed());
            }
            if (notBlank(pet.lifeStage())) {
                parts.add(pet.lifeStage());
            }
            if (parts.isEmpty()) {
                continue;
            }
            sb.append("- ").append(String.join(" · ", parts)).append('\n');
            if (pet.allergens() != null && !pet.allergens().isEmpty()) {
                sb.append("  过敏/忌口：").append(String.join("、", pet.allergens())).append('\n');
            }
        }
        return sb.toString();
    }

    /** 档案是否至少有一条能用的记录（用于决定是否值得多说一句） */
    public static boolean usable(List<PetBrief> pets) {
        return pets != null && pets.stream().anyMatch(p -> notBlank(p.species()) || notBlank(p.breed()));
    }

    private static String speciesWord(String species) {
        if (species == null) {
            return "";
        }
        return SPECIES_WORDS.getOrDefault(species.trim().toLowerCase(Locale.ROOT), "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
