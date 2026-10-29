package com.duoduo.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 工具契约（2026-10-28）。
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema：既发给 LLM，也用于服务端必填校验 */
    JsonNode inputSchema();

    /** 写操作必须走二次确认与幂等 */
    default boolean writeOperation() {
        return false;
    }

    ToolResult execute(ToolContext context, JsonNode arguments);
}
