package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.client.AiCatalogClient;
import com.duoduo.ai.client.dto.AiProductFact;
import com.duoduo.ai.client.dto.AiSkuFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CheckStockTool implements AgentTool {

    private final AiCatalogClient catalog;
    private final ObjectMapper mapper;

    public CheckStockTool(AiCatalogClient catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "check_stock";
    }

    @Override
    public String description() {
        return "查询指定 SKU 的实时可售库存；加购前必须调用";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.CHECK_STOCK;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        try {
            String spuId = args.path("spuId").asText();
            String skuId = args.path("skuId").asText();
            int quantity = args.path("quantity").asInt(1);
            List<AiProductFact> facts = catalog.facts(spuId).data();
            if (facts == null || facts.isEmpty()) {
                return ToolResult.fail("PRODUCT_NOT_FOUND", "商品已下架或不存在");
            }
            AiSkuFact sku = facts.get(0).skus().stream()
                    .filter(s -> String.valueOf(s.skuId()).equals(skuId))
                    .findFirst().orElse(null);
            if (sku == null) {
                return ToolResult.fail("SKU_NOT_FOUND", "规格不存在");
            }
            boolean enough = sku.stock() >= quantity;
            return ToolResult.ok(mapper.valueToTree(new StockResult(
                    sku.skuId(), sku.stock(), enough, sku.price())));
        } catch (Exception e) {
            return ToolResult.fail("STOCK_FAILED", "库存暂时取不到");
        }
    }

    public record StockResult(Long skuId, int stock, boolean enough, long priceCents) {
    }
}
