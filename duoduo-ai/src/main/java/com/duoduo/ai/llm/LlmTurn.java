package com.duoduo.ai.llm;

import com.duoduo.ai.agent.tool.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * 一次模型回复：正文 + 工具调用 + 原始 tool_calls（用于回传 OpenAI 消息体）。
 */
public record LlmTurn(String content, List<ToolCall> toolCalls,
                      List<Map<String, Object>> rawToolCalls) {

    public static LlmTurn text(String content) {
        return new LlmTurn(content, List.of(), List.of());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
