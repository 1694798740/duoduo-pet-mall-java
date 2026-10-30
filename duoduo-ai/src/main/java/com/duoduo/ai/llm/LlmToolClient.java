package com.duoduo.ai.llm;

import com.duoduo.ai.agent.tool.ToolSpec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 支持 Function Calling 的 LLM 客户端。
 */
public interface LlmToolClient {

    /** 非流式：用于工具决策循环 */
    LlmTurn chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools);

    /**
     * 带整轮剩余预算的非流式调用。
     *
     * <p>默认实现忽略预算、保持旧行为；支持预算的实现（DashScope）必须在
     * {@code budget} 用尽时尽快失败，避免单个 Agent 轮次无限拖长。
     */
    default LlmTurn chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools, Duration budget) {
        return chatWithTools(messages, tools);
    }

    /** 流式：用于最终回复；每个 delta 回调一次 */
    void streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta);

    /** 带整轮剩余预算的流式调用；默认忽略预算以保持旧行为。 */
    default void streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta, Duration budget) {
        streamChat(messages, onDelta);
    }

    boolean isConfigured();
}
