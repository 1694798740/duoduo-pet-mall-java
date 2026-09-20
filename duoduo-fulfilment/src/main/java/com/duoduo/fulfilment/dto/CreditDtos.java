package com.duoduo.fulfilment.dto;

import java.util.List;

/**
 * 信用分相关 DTO（A4）。
 *
 * <p>拆成 {@code View}（对外读）/ {@code Inputs}（可解释的原始输入）/ {@code RefreshResult}（刷新回执）三组，
 * 而不是一个「大而全」的对象：公开接口只该看到「分数 + 构成明细」，
 * 原始输入（订单数、评价条数）虽然也要给出来做解释，但写成独立 record 后，
 * 将来若某个字段涉及隐私需要裁剪，改动面是一个明确的类型，不是撒在多个字段上。
 *
 * <p>注意：跨域写用的 {@code CreditWriteRequest} / {@code CreditWriteResult} 不在这里，
 * 而在 {@code client.dto} 包下（与 Feign 客户端同处），避免同一契约在本域有两份定义。
 */
public final class CreditDtos {

    private CreditDtos() {
    }

    /**
     * 信用分视图（公开只读）。
     *
     * @param score       总分 0~100（实际落在基础分~100）
     * @param level       等级编码（EXCELLENT / GOOD / FAIR / POOR）
     * @param levelLabel  等级中文文案（服务端给出，前端不再维护映射）
     * @param factors     构成明细；**各 factor.points 之和恒等于 score**
     * @param inputs      原始输入（不含任何身份信息，只有计数与评分）
     */
    public record CreditView(
            Long userId,
            int score,
            String level,
            String levelLabel,
            List<CreditFactor> factors,
            CreditInputs inputs,
            String generatedAt
    ) {
    }

    /**
     * 单个因子明细。
     *
     * @param weight   该因子满分（权重表里的一项）
     * @param rawValue 人类可读的原始值，如 {@code "92%（完成 23 / 已了结 25）"}
     * @param points   该因子得分（整数；四项之和 == 总分）
     */
    public record CreditFactor(
            String code,
            String label,
            int weight,
            String rawValue,
            int points
    ) {
    }

    /** 计算所用的原始输入。刻意只放计数与评分，不含昵称 / 手机号等任何身份字段。 */
    public record CreditInputs(
            int completedOrders,
            int closedOrders,
            int refundedOrders,
            int totalOrders,
            int reviewCount,
            /** 平均评分，保留两位小数的字符串（避免浮点在 JSON 里出现 4.666666666666667 这类噪声） */
            String avgRating,
            int adverseAfterSales
    ) {
    }

    /**
     * 批量刷新结果。
     *
     * @param scanned   本次处理的用户数
     * @param updated   分数发生变化、已写库的
     * @param unchanged 分数与库中一致、未写库的（幂等的正常结果，不是失败）
     * @param skipped   单个用户处理失败被跳过的（对端不可用等，已记日志）
     * @param note      口径说明（随结果返回，便于运维一眼看懂刷新了什么）
     */
    public record CreditRefreshResult(
            int scanned,
            int updated,
            int unchanged,
            int skipped,
            String note
    ) {
    }
}
