package com.duoduo.product.support.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 同义词表与查询扩展（A10，验收①：同义词容错）。
 *
 * <p><b>为什么同义词由应用侧替换承担，而不是交给 ES 的 synonym filter</b>
 * （以下是在 ES 8.11.4 上实测得到的结论，不是推测）：
 * <ol>
 *   <li>内置 {@code standard} 分析器对中文是<b>逐字切分</b>（「猫粮」→「猫」「粮」）；</li>
 *   <li>{@code synonym_graph} 在查询侧展开多字词时，产出的查询是<b>相邻短语</b>而非 or 分支：
 *       规则 {@code 猫咪,喵星人} 下查「猫咪」，客户端生成的查询是
 *       {@code title:"猫 咪"} —— 要求标题里「猫」「咪」连续出现；</li>
 *   <li>于是查「猫咪」连标题「麦富迪 冻干双拼全价猫粮」都<b>命中不了</b>（实测 0 命中）。
 *       同义词非但没帮上召回，反而把 or 语义收紧成 phrase，<b>降低</b>了召回。</li>
 * </ol>
 *
 * <p>把同义词挂到<b>索引分析器</b>更是错的：它会让索引里凭空多出同义 token，
 * 「这个词为什么被召回」变得无法解释，且规则一改就得重建索引。
 *
 * <p>所以：<b>同义词在应用侧替换</b> —— 把查询串里出现的词换成同组其它词，
 * 作为<b>额外的 or 分支</b>交给 ES。替换后的串经逐字分词得到单字 token，
 * 与其他分支同样是 or 关系，召回只增不减；行为完全可预测，也能直接单测。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchSynonyms {

    /** 单次查询最多扩展出的变体数：防止超大词组把 should 子句撑爆 */
    private static final int MAX_VARIANTS = 8;

    /** 同义词组：组内任意词可互相替代 */
    private final List<List<String>> groups;

    public SearchSynonyms(@Value("${duoduo.search.synonyms}") String raw) {
        List<List<String>> parsed = new ArrayList<>();
        for (String part : raw.split("[;；]")) {
            List<String> group = new ArrayList<>();
            for (String word : part.split("[,，]")) {
                String t = word.trim();
                if (!t.isEmpty()) {
                    group.add(t);
                }
            }
            // 单词组无意义（没有可替代对象），直接丢弃
            if (group.size() >= 2) {
                parsed.add(List.copyOf(group));
            }
        }
        this.groups = List.copyOf(parsed);
    }

    public List<List<String>> groups() {
        return groups;
    }

    /** ES synonym filter 的规则形态：{@code ["犬,狗","幼犬,小狗"]} */
    public List<String> rules() {
        return groups.stream().map(g -> String.join(",", g)).toList();
    }

    /**
     * 查询扩展：把查询串里命中的同义词替换为同组其它词。
     *
     * <p>返回<b>不含原文</b>的变体（原文由调用方自己加），去重且限量。
     */
    public List<String> variants(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        List<String> out = new ArrayList<>();
        for (List<String> group : groups) {
            for (String term : group) {
                if (!kw.contains(term)) {
                    continue;
                }
                for (String alt : group) {
                    if (alt.equals(term)) {
                        continue;
                    }
                    String variant = kw.replace(term, alt);
                    if (!variant.equals(kw) && !out.contains(variant)) {
                        out.add(variant);
                        if (out.size() >= MAX_VARIANTS) {
                            return List.copyOf(out);
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }
}
