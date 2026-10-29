package com.duoduo.ai.guardrail;

import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品事实护栏（2026-10-28）。
 *
 * <p>只信任工具返回的数据：推荐卡片的 id/标题/价格/库存全部从工具结果装配；
 * 正文里出现不在工具事实中的价格时，替换成事实版回复。
 */
@Component
public class FactGuard {

    private static final Pattern PRICE = Pattern.compile("[¥￥]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元");

    public Result verify(String reply, List<ToolResult> toolResults) {
        Map<Long, ProductRecommendation> facts = collect(toolResults);
        List<Long> selected = selectedIds(toolResults);
        List<ProductRecommendation> recs;
        if (!selected.isEmpty()) {
            recs = selected.stream()
                    .map(facts::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(r -> !isTestProduct(r))
                    .limit(3)
                    .toList();
        } else {
            // 模型没调 recommend_products 时，用正文里真正提到的商品做兜底；
            // 同时排除测试商品，避免「正文说别买、卡片却推了」。
            String normalizedReply = normalize(reply);
            recs = facts.values().stream()
                    .filter(r -> !isTestProduct(r))
                    .filter(r -> normalizedReply.contains(normalize(r.title())))
                    .limit(3)
                    .toList();
            if (recs.isEmpty()) {
                recs = facts.values().stream()
                        .filter(r -> !isTestProduct(r))
                        .limit(2)
                        .toList();
            }
        }
        String cleaned = reply == null ? "" : reply;
        if (priceMismatch(cleaned, facts.values().stream().map(ProductRecommendation::minPrice).toList())) {
            cleaned = factsReply(recs);
        }
        return new Result(cleaned, recs, recs.stream().map(ProductRecommendation::id).toList());
    }

    /** 模型显式提交的推荐 id；没有提交时返回空（由调用方退回工具结果） */
    private List<Long> selectedIds(List<ToolResult> results) {
        if (results == null) {
            return List.of();
        }
        for (ToolResult result : results) {
            if (!result.success() || result.data() == null) {
                continue;
            }
            JsonNode ids = result.data().path("spuIds");
            if (!ids.isArray()) {
                continue;
            }
            List<Long> out = new ArrayList<>();
            ids.forEach(n -> {
                try {
                    out.add(n.asLong());
                } catch (Exception ignored) {
                    // 脏 id 丢弃
                }
            });
            if (!out.isEmpty()) {
                return out;
            }
        }
        return List.of();
    }

    private Map<Long, ProductRecommendation> collect(List<ToolResult> results) {
        Map<Long, ProductRecommendation> map = new LinkedHashMap<>();
        if (results == null) {
            return map;
        }
        for (ToolResult result : results) {
            if (!result.success() || result.data() == null) {
                continue;
            }
            collectNode(result.data(), map);
        }
        return map;
    }

    private void collectNode(JsonNode node, Map<Long, ProductRecommendation> map) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collectNode(n, map));
            return;
        }
        if (!node.isObject() || !node.hasNonNull("spuId")) {
            return;
        }
        try {
            long id = node.path("spuId").asLong();
            String title = node.path("title").asText("");
            long min = node.path("minPrice").asLong(0);
            long max = node.path("maxPrice").asLong(min);
            boolean inStock = node.path("inStock").asBoolean(true);
            if (id <= 0 || title.isBlank()) {
                return;
            }
            map.putIfAbsent(id, new ProductRecommendation(
                    id, title, node.path("subtitle").asText(""), min, max, inStock));
        } catch (Exception ignored) {
            // 单个脏节点不影响其它推荐
        }
    }

    private boolean priceMismatch(String reply, List<Long> allowedCents) {
        if (reply.isBlank() || allowedCents.isEmpty()) {
            return false;
        }
        List<String> allowed = new ArrayList<>();
        for (Long cents : allowedCents) {
            allowed.add(yuan(cents));
            allowed.add(String.valueOf(cents / 100));
        }
        Matcher m = PRICE.matcher(reply);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null || allowed.contains(raw)) {
                continue;
            }
            // 「预算 200 元以内」是用户条件，不是商品报价；不能因此把整段自然话术替换掉。
            int from = Math.max(0, m.start() - 10);
            String prefix = reply.substring(from, m.start());
            if (prefix.contains("预算") || prefix.contains("以内") || prefix.contains("以下")
                    || prefix.contains("不超过") || prefix.contains("最多")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String factsReply(List<ProductRecommendation> recs) {
        if (recs.isEmpty()) {
            return "暂时没有找到符合全部条件的商品，可以换个描述或放宽一点预算试试。";
        }
        StringBuilder sb = new StringBuilder("按你的条件，比较合适的是：");
        for (int i = 0; i < recs.size(); i++) {
            ProductRecommendation r = recs.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append("「").append(r.title()).append("」").append(yuan(r.minPrice())).append(" 元起");
            if (!r.inStock()) {
                sb.append("（暂时缺货）");
            }
        }
        return sb.append("。需要我帮你对比或加入购物车吗？").toString();
    }

    private static String yuan(long cents) {
        return cents % 100 == 0 ? String.valueOf(cents / 100) : String.format("%.2f", cents / 100.0);
    }

    private static boolean isTestProduct(ProductRecommendation r) {
        return r.title() != null && (r.title().contains("测试") || r.title().contains("夹具"));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s（）()\\-—/、，,。.!！?？]", "").toLowerCase();
    }

    public record Result(String reply, List<ProductRecommendation> recommendations,
                         List<Long> recommendedIds) {
    }
}
