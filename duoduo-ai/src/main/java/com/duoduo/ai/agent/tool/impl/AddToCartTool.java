package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.client.CartClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AddToCartTool implements AgentTool {

    private final CartClient cart;
    private final ObjectMapper mapper;

    public AddToCartTool(CartClient cart, ObjectMapper mapper) {
        this.cart = cart;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "add_to_cart";
    }

    @Override
    public String description() {
        return "把用户确认过的 SKU 加入购物车；必须带服务端确认令牌";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.ADD_TO_CART;
    }

    @Override
    public boolean writeOperation() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        if (!context.loggedIn()) {
            return ToolResult.needLogin(null);
        }
        try {
            long skuId = Long.parseLong(args.path("skuId").asText());
            int quantity = args.path("quantity").asInt(1);
            var resp = cart.add(context.userId(), context.role(), context.phone(),
                    new CartClient.CartAddRequest(skuId, quantity));
            JsonNode data = resp == null ? null : resp.data();
            return ToolResult.ok(data == null ? mapper.createObjectNode() : data);
        } catch (Exception e) {
            return ToolResult.fail("CART_FAILED", "加入购物车失败，请稍后再试");
        }
    }
}
