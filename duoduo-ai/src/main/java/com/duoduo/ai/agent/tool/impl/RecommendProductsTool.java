package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型显式选择最终推荐的 1~3 个商品（2026-10-28）。
 *
 * <p>只回 spuId，标题/价格由服务端从工具事实装配；这样「正文夸一款、卡片推另一款」
 * 不会同时出现。ids 仍会在 FactGuard 里做存在性校验。
 */
@Component
public class RecommendProductsTool implements AgentTool {

    private final ObjectMapper mapper;

    public RecommendProductsTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "recommend_products";
    }

    @Override
    public String description() {
        return "提交最终推荐的 1~3 个商品 spuId；只能从 search_products/get_product_detail 的结果里选";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.RECOMMEND_PRODUCTS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode arr = args.path("spuIds");
        if (arr.isArray()) {
            arr.forEach(n -> {
                String id = n.isNumber() ? String.valueOf(n.asLong()) : n.asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            });
        } else if (arr.isTextual()) {
            // 容忍模型把数组写成逗号分隔字符串
            for (String s : arr.asText().split("[,，\\s]+")) {
                if (!s.isBlank()) {
                    ids.add(s.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            return ToolResult.fail("EMPTY_SELECTION", "没有选择任何商品");
        }
        List<String> list = new ArrayList<>(ids);
        return ToolResult.ok(mapper.valueToTree(new Selection(list)));
    }

    public record Selection(List<String> spuIds) {
    }
}
