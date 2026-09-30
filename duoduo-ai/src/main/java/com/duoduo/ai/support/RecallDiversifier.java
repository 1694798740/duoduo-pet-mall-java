package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 召回结果的去重与多样性（第五十八轮 · AI 加强项）。
 *
 * <h2>为什么要去重</h2>
 * 商品域的候选是按关键词召回的，同一款商品的<b>不同规格 / 不同配色</b>会各占一行
 * （「幼猫粮 500g」「幼猫粮 1kg」「幼猫粮 2kg」）。把它们一起喂给模型有两个后果：
 * <ul>
 *   <li><b>浪费候选位</b>：召回上限是 8 条，同款占了 3 条，真正不同的商品只剩 5 条进得来 ——
 *       用户看到的就是「翻来覆去就这一款」，导购价值大打折扣；</li>
 *   <li><b>诱导模型做无意义比较</b>：模型会把三个规格当成三个选择，认真比较「哪个更适合」，
 *       而用户问的是「买什么」，不是「买哪个规格」。</li>
 * </ul>
 *
 * <h2>「同款」怎么判：标题去规格后缀后的等价</h2>
 * 直接按标题全等去重不管用（规格不同则标题不同）；按 id 去重没意义（id 本来就唯一）。
 * 这里把标题**归一化**后再比：去掉规格数字与单位、标点、空白，全角转半角。
 * 同一款的不同规格会归一到同一个键，于是只保留一件（**保留价格最低的那件** ——
 * 同款里最便宜的那条才是对用户最有用的代表，也避免「给你推荐了个贵的」）。
 *
 * <h2>为什么保留顺序而不是重排</h2>
 * 候选顺序来自商品域的排序（相关性 / 销量），那是**服务端已生效的事实**；
 * AI 不擅自重排，只在去重后按原顺序紧凑。重排会让「为什么推这个」变得无法解释。
 */
public final class RecallDiversifier {

    /** 规格片段：数字 + 单位（500g / 1kg / 2L / 3袋 / 400ml），含全角数字 */
    private static final Pattern SPEC = Pattern.compile(
            "[0-9０-９]+(?:\\.[0-9０-９]+)?\\s*(?:kg|g|ml|l|KG|G|ML|L|千克|克|毫升|升|斤|袋|包|罐|支|条|只|个|片|块|厘米|cm|CM)");

    /** 标点与空白 */
    private static final Pattern NOISE = Pattern.compile("[\\s　,，.。、;；:：!！?？~～\\-—_/|（）()\\[\\]【】]");

    private RecallDiversifier() {
    }

    /**
     * 去重并截断。
     *
     * @param candidates 原始候选（商品域返回顺序）
     * @param limit      最多保留几条
     */
    public static List<ProductCandidate> diversify(List<ProductCandidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<ProductCandidate> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProductCandidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            String key = titleKey(candidate.title());
            // 标题为空时退化成按 id 去重：不能因为取不到标题就把整批丢掉
            if (key.isEmpty()) {
                key = "id:" + candidate.id();
            }
            if (!seen.add(key)) {
                int at = indexOfKey(kept, key, candidate.title());
                if (at >= 0 && candidate.minPrice() < kept.get(at).minPrice()) {
                    kept.set(at, candidate); // 同款保留更便宜的那件
                }
                continue;
            }
            kept.add(candidate);
            if (kept.size() >= limit) {
                break;
            }
        }
        return kept;
    }

    /** 标题 → 去重键（去规格、去标点、小写） */
    static String titleKey(String title) {
        if (title == null) {
            return "";
        }
        String noSpec = SPEC.matcher(title).replaceAll("");
        return NOISE.matcher(noSpec).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 已保留列表里与给定键相同的那个的下标；标题为空时按「同一空标题」处理 */
    private static int indexOfKey(List<ProductCandidate> kept, String key, String title) {
        for (int i = 0; i < kept.size(); i++) {
            if (titleKey(kept.get(i).title()).equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
