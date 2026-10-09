package com.duoduo.product.client.dto;

/**
 * 违规上报的结果（E28）：用户服务 {@code /api/users/internal/merchant-violation} 的响应体副本。
 *
 * <p>为什么要把 {@code violationCount} 回传：商品域要在日志与后端判定里说清
 * 「这次违规之后这家店累计几次、有没有被停业」，而不是只记一句「已上报」。
 * 排查「为什么这家店突然停业了」时，答案必须能在日志里直接读到。
 */
public record MerchantViolationResult(
        String merchantId,
        /** 累计违规次数（含本次） */
        int violationCount,
        /** 本次上报是否触发了商家级停业 */
        boolean suspended
) {
}
