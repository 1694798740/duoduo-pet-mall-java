package com.duoduo.fulfilment.client.dto;

/**
 * 信用分回写结果（与 {@code com.duoduo.user.dto.UserDtos.CreditUpdateResult} 对齐）。
 *
 * <p>{@code changed} 是幂等的可见证据：同一分数重复回写时它为 {@code false}，
 * 刷新任务据此把「未变化」与「失败」分开统计 —— 否则「重跑了但没写」看起来像出了问题。
 */
public record CreditWriteResult(
        Long userId,
        Integer previousScore,
        int score,
        boolean changed
) {
}
