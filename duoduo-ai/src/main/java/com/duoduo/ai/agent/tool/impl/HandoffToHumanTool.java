package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HandoffToHumanTool implements AgentTool {

    private final ObjectMapper mapper;

    public HandoffToHumanTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "handoff_to_human";
    }

    @Override
    public String description() {
        return "医疗红线、用户要求或工具连续失败时，转交人工客服";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.HANDOFF_TO_HUMAN;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        // 真正的客服会话由前端带登录态进入 /support 创建；这里返回转交卡片所需信息。
        return ToolResult.ok(mapper.valueToTree(Map.of(
                "handoff", true,
                "reason", args.path("reason").asText("USER_REQUEST"),
                "urgency", args.path("urgency").asText("NORMAL"),
                "summary", args.path("summary").asText(""),
                "url", "/support"
        )));
    }
}
