package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷问题、相关追问与商品对比（第五十八轮 · AI 加强项 S3）。
 *
 * <h2>三件事的共同原则：只提「链路真的答得上来」的问题
 * <ul>
 *   <li><b>快捷问题</b>取自商品域的<b>真实类目</b> —— 凭空编一句「给我推荐宠物无人机」，
 *       它点下去就是空结果，那不是引导，是挖坑；</li>
 *   <li><b>追问</b>只在「当前这一轮确实还能再查一次」时才给
 *       （比如候选里混着缺货的，才问「只看有货的」）；</li>
 *   <li><b>对比</b>只用候选里的客观字段（价格 / 库存 / 销量 / 评分）说话，
 *       **不交给模型润色** —— 对比是最容易编出「这款更适合幼猫」这种无据结论的场景，
 *       而这类结论既无法核对、又直接影响购买决定。</li>
 * </ul>
 */
public final class Suggestions {

    /** 对比意图的触发词 */
    private static final List<String> COMPARE_WORDS =
            List.of("对比", "比较", "哪个好", "哪个更好", "区别", "差异", "还是", "怎么选", "选哪个");

    private Suggestions() {
    }

    /**
     * 快捷问题（首页/空态用）。
     *
     * @param categoryNames 商品域返回的真实一级类目名
     */
    public static List<String> quickQuestions(List<String> categoryNames, int limit) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String name : categoryNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add("帮我挑一款" + name.trim());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /**
     * 相关追问：每一条都对应「当前状态下还能做的一次真实检索」。
     *
     * <p>为什么不做成固定文案列表：固定的「猜你想问」在候选为空时依然显示，
     * 点下去还是空 —— 用户会认为这个助手在敷衍。这里的每一条都由当前候选推导出来。
     */
    public static List<String> followUps(List<ProductCandidate> candidates, boolean hasBudget, int limit) {
        List<ProductCandidate> list = candidates == null ? List.of() : candidates;
        if (list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (!hasBudget) {
            out.add("200 元以内的有吗");
        }
        boolean anyOutOfStock = list.stream().anyMatch(c -> !c.inStock());
        if (anyOutOfStock) {
            out.add("只看有货的");
        }
        if (list.size() >= 2) {
            out.add("这几款帮我对比一下");
        }
        if (out.size() < limit) {
            out.add("还有别的推荐吗");
        }
        return out.size() <= limit ? out : out.subList(0, limit);
    }

    /** 是否是对比意图 */
    public static boolean isCompare(String message) {
        String text = message == null ? "" : message;
        for (String word : COMPARE_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对比回复（事实版，只用候选里真实存在的字段）。
     *
     * <p>刻意不写「哪个更好」的推荐结论：那需要依据（适口性、成分、适配年龄），
     * 本服务<b>没有这些数据</b>。写一句「A 更适合你家猫」就是凭空下判断。
     * 这里把可核对的事实并列出来，让用户自己判断 —— 导购的职责是把事实摆清楚。
     */
    public static String compareReply(List<ProductCandidate> candidates, int limit) {
        List<ProductCandidate> list = candidates == null ? List.of() : candidates;
        if (list.size() < 2) {
            return null;
        }
        int n = Math.min(limit, list.size());
        StringBuilder sb = new StringBuilder("我把这几款的客观差异列出来（价格是库里的真实价格）：");
        for (int i = 0; i < n; i++) {
            ProductCandidate c = list.get(i);
            sb.append("\n").append(i + 1).append(". 「").append(c.title()).append("」")
                    .append(yuan(c.minPrice())).append(" 元起");
            sb.append(c.inStock() ? "，有货" : "，暂时缺货");
            if (c.salesCount() > 0) {
                sb.append("，累计售出 ").append(c.salesCount()).append(" 件");
            }
            if (c.rating() > 0) {
                sb.append("，评分 ").append(String.format("%.1f", c.rating()));
            }
        }
        sb.append("\n适不适配你家宠物要看成分与年龄说明，商品页里更全，我这里不做判断。");
        return sb.toString();
    }

    private static String yuan(long cents) {
        long abs = Math.abs(cents);
        return abs % 100 == 0 ? String.valueOf(cents / 100)
                : (cents / 100) + "." + String.format("%02d", abs % 100);
    }
}
