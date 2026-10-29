package com.duoduo.common.api;

import java.util.List;

/**
 * 批量操作统一返回结构（2026-10-27 · 批量操作即已读）。
 *
 * <p>与商品批量上下架（{@code SellerProductBatchResultView}）同一套语义：
 * HTTP 200 只代表「处理完成」，逐条成败看 {@code items[].success}；
 * 部分失败不回滚已成功的条目。
 *
 * <p>为什么放在 common：交易、履约、商品三个域都要返回同一种形状，
 * 前端才能用同一个结果弹窗与同一套「成功条数」逻辑，而不是每加一个批量入口
 * 就得在前端再写一遍解析。
 *
 * <p>{@code id} 一律是字符串：雪花主键超过 JS 安全整数，转成 number 会静默丢末位；
 * 订单号本身也是字符串。
 */
public final class BatchDtos {

    private BatchDtos() {
    }

    /** 单条执行结果 */
    public record Item(
            /** 业务标识：订单号 / 售后单 id / 评价 id / 商品 id（统一转字符串） */
            String id,
            boolean success,
            /** 失败时的错误码（ErrorCode 名称，如 FORBIDDEN / BUSINESS_RULE）；成功为 null */
            String errorCode,
            /** 失败原因；成功为 null */
            String message
    ) {
    }

    /**
     * 批量结果汇总。
     *
     * <p>{@code counts} = 操作后的最新角标分桶，可能为空（该模块没有分桶角标）。
     * 前端拿到非空 {@code counts} 时直接覆盖本地值，省掉一次额外请求，
     * 也避免「操作成功但角标还是旧值」的中间态。
     */
    public record Result(
            int total,
            int successCount,
            int failedCount,
            List<Item> items,
            List<StatusCount> counts
    ) {
    }
}
