package com.duoduo.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 发给 LLM 的工具描述（OpenAI tools 格式的 function 部分）。
 */
public record ToolSpec(String name, String description, JsonNode parameters) {
}
