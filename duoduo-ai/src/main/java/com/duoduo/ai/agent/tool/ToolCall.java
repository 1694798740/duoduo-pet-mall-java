package com.duoduo.ai.agent.tool;

/**
 * 模型发起的一次工具调用。
 */
public record ToolCall(String id, String name, String arguments) {
}
