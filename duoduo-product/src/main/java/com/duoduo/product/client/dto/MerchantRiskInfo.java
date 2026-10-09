package com.duoduo.product.client.dto;

/**
 * 商家风险画像（E28）：用户服务 {@code /api/users/internal/merchant-risk} 的响应体副本。
 *
 * <p>与其他跨服务 DTO 同一纪律：<b>复制而不是共享</b>。商品域要用到的只有
 * 「这个商家值不值得重点看」这一件事，用户域往后给这份视图加字段
 * （比如下一次续期时间、累计驳回明细），商品域不该跟着重新编译。
 */
public record MerchantRiskInfo(
        String merchantId,
        int violationCount,
        boolean suspended,
        boolean rejected,
        /** 是否处于「重新开业观察期」（最近一次审核通过 / 恢复营业在 7 天内） */
        boolean recentlyResumed
) {
    /** 是否命中「此前有驳回或停业记录」这条风险规则 */
    public boolean flagged() {
        return violationCount > 0 || suspended || rejected;
    }
}
