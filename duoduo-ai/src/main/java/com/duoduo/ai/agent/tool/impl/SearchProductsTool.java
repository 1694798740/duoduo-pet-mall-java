package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.client.dto.AiProductCard;
import com.duoduo.ai.rag.ProductRagRetriever;
import com.duoduo.ai.rag.QueryPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class SearchProductsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchProductsTool.class);

    private final ProductRagRetriever retriever;
    private final ObjectMapper mapper;

    public SearchProductsTool(ProductRagRetriever retriever, ObjectMapper mapper) {
        this.retriever = retriever;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "search_products";
    }

    @Override
    public String description() {
        return "按真实商品库搜索候选商品，支持物种/生命阶段/预算/类目/过敏原/有货过滤";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.SEARCH_PRODUCTS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        try {
            List<String> allergens = args.path("excludeAllergens").isArray()
                    ? Arrays.stream(mapper.convertValue(args.path("excludeAllergens"), String[].class)).toList()
                    : List.of();
            QueryPlan plan = new QueryPlan(
                    args.path("query").asText(""),
                    nullable(args, "species"),
                    nullable(args, "lifeStage"),
                    longOrNull(args.path("categoryId")),
                    args.hasNonNull("budgetMin") ? args.path("budgetMin").asLong() : null,
                    args.hasNonNull("budgetMax") ? args.path("budgetMax").asLong() : null,
                    allergens,
                    !args.has("inStockOnly") || args.path("inStockOnly").asBoolean(true),
                    args.path("limit").asInt(8));
            List<AiProductCard> list = retriever.retrieve(plan);
            return ToolResult.ok(mapper.valueToTree(list));
        } catch (Exception e) {
            log.warn("商品搜索失败: {}", e.toString(), e);
            return ToolResult.fail("SEARCH_FAILED", "商品搜索暂时不可用");
        }
    }

    /** 模型可能把类目写成 cat_food 这类业务码；非纯数字一律当“未指定”，不阻断搜索。 */
    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText("").trim();
        if (!raw.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullable(JsonNode args, String field) {
        if (!args.hasNonNull(field)) {
            return null;
        }
        String value = args.path(field).asText();
        return value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static String textArray(JsonNode node) {
        if (!node.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        node.forEach(n -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(n.asText());
        });
        return sb.isEmpty() ? null : sb.toString();
    }
}
