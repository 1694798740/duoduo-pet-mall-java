package com.duoduo.ai.dto;

import java.util.List;

/**
 * 记忆管理契约（2026-10-29 · 第二阶段）。
 *
 * <p>把「低风险偏好」与「高风险健康信息」分成两组接口，是刻意的：
 * 前者可以在对话里自动沉淀（用户可撤销），后者必须走一次显式确认。
 * 两者混在一个 PUT 里，等于把「改个预算」和「记录过敏原」当成同一风险等级。
 */
public final class AiMemoryDtos {

    private AiMemoryDtos() {
    }

    /** 当前记忆视图（低风险列 + 高风险快照 + 称呼）。 */
    public record MemoryView(
            Long userId,
            String species,
            Long budgetMinCents,
            Long budgetMaxCents,
            List<String> categories,
            List<String> brands,
            List<String> allergens,
            String healthNotes,
            String consentVersion,
            String updatedAt,
            /**
             * 用户希望被怎么称呼。刻意不由对话自动抽取（模型很容易把宠物名记成人名），
             * 只在记忆页由用户显式保存。
             */
            String nickname
    ) {
    }

    /** 低风险更新：可直接保存，用户可在页面撤销。 */
    public record LowRiskUpdate(
            String species,
            Long budgetMinCents,
            Long budgetMaxCents,
            List<String> categories,
            List<String> brands,
            String nickname
    ) {
    }

    /**
     * 高风险内容：过敏原与健康备注。
     *
     * <p>与确认令牌绑定的就是这份**规范化后的内容** —— 用户确认的是具体值，
     * 不是「某个可变的请求」。
     */
    public record HighRiskUpdate(
            List<String> allergens,
            String healthNotes
    ) {
    }

    /** 高风险确认申请结果。 */
    public record ConfirmTicket(
            String token,
            int expireSeconds,
            HighRiskUpdate preview,
            String hint
    ) {
    }

    /** 高风险确认请求：令牌 + 与申请时一致的内容。 */
    public record HighRiskConfirmRequest(
            String token,
            List<String> allergens,
            String healthNotes
    ) {
    }
}
