package com.duoduo.ai.agent.tool;

import com.duoduo.ai.memory.SlotState;

/**
 * 本轮工具约束上下文（DEF-013）。
 *
 * <p>为什么需要它：`ToolContext` 只承载身份，工具目前完全信任模型传入的
 * `budgetMax`。模型可以「带预算查一次 -> 不带预算再查一次」，导致用户明确设置的
 * 预算被绕过。这里把本轮合并后的 {@link SlotState} 暴露给工具，让服务端槽位
 * 成为预算的最终口径；模型只能收紧、不能放宽。
 *
 * <p>与 `UserContext` / `AuditTrail` 同性质：ThreadLocal，只在当前 Agent 线程内有效；
 * 必须由调用方在 finally 中 {@link #clear()}，避免线程复用串场。
 */
public final class ToolConstraintContext {

    private static final ThreadLocal<SlotState> HOLDER = new ThreadLocal<>();

    private ToolConstraintContext() {
    }

    public static void set(SlotState slots) {
        HOLDER.set(slots);
    }

    public static SlotState get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
