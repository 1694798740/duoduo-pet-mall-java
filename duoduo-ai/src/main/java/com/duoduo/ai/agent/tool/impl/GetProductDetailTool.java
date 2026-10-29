package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.client.AiCatalogClient;
import com.duoduo.ai.client.dto.AiProductFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetProductDetailTool implements AgentTool {

    private final AiCatalogClient catalog;
    private final ObjectMapper mapper;

    public GetProductDetailTool(AiCatalogClient catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_product_detail";
    }

    @Override
    public String description() {
        return "查询商品的实时价格、SKU 规格与库存；生成推荐前必须调用";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.GET_PRODUCT_DETAIL;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        try {
            String spuId = args.path("spuId").asText();
            List<AiProductFact> facts = catalog.facts(spuId).data();
            if (facts == null || facts.isEmpty()) {
                return ToolResult.fail("PRODUCT_NOT_FOUND", "商品已下架或不存在");
            }
            return ToolResult.ok(mapper.valueToTree(facts.get(0)));
        } catch (Exception e) {
            return ToolResult.fail("FACT_FAILED", "实时商品信息暂时取不到");
        }
    }
}
