package com.duoduo.fulfilment.client.dto;

/**
 * 信用分回写请求（与 {@code com.duoduo.user.dto.UserDtos.CreditUpdateRequest} 对齐）。
 *
 * <p>{@code source} 只是留痕字段（如 {@code SCHEDULED_REFRESH} / {@code MANUAL_REFRESH}），
 * 用户域不据此改变行为 —— 它存在的意义是：将来发现某个用户分数异常时，
 * 能从库里看出「这条分数是谁、因为什么写进来的」。
 */
public record CreditWriteRequest(int score, String source) {
}
