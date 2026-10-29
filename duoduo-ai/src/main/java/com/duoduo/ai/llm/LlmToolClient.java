package com.duoduo.ai.llm;

import com.duoduo.ai.agent.tool.ToolSpec;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 支持 Function Calling 的 LLM 客户端。
 */
public interface LlmToolClient {

    /** 非流式：用于工具决策循环 */
    LlmTurn chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools);

    /** 流式：用于最终回复；每个 delta 回调一次 */
    void streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta);

    boolean isConfigured();
}
