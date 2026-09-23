package com.duoduo.trade.client.dto;

/**
 * 交易域 → 营销域的调用契约（A5 · 优惠券）。
 *
 * <p>为什么在交易域<b>再定义一份</b>而不是直接引用营销域的 DTO：
 * 引入 {@code duoduo-marketing} 的 jar 会让两个服务在编译期耦合，
 * 之后任何一方改字段都要同时发版另一方。服务间只共享 HTTP 上的 JSON 形状，
 * 各自持有自己的一份 —— 与 {@link SkuSnapshot} / {@link StockChangeDtos} 同一约定。
 *
 * <p>代价是字段要同步改两处，因此这里的字段刻意保持<b>最少且稳定</b>：
 * 只传「券码 / 用户 / 订单号 / 金额」与回「成功与否 / 抵扣额 / 原因」，
 * 不复制营销域内部的展示字段。
 */
public final class CouponCallDtos {

    private CouponCallDtos() {
    }

    /**
     * 锁券请求。
     *
     * @param code        券码
     * @param userId      下单用户（营销域校验归属用）
     * @param orderNo     订单号（写进 {@code t_coupon.locked_order_no}，后续解锁 / 核销都按它定位）
     * @param orderAmount 该订单的商品总额（分）；营销域用它比满减门槛
     */
    public record LockRequest(String code, Long userId, String orderNo, long orderAmount) {
    }

    /**
     * 锁券结果。
     *
     * <p><b>刻意用「结果对象」而不是异常</b>：券不可用的原因（未达门槛 / 已使用 / 已被占用）
     * 是要直接给下单用户看的一句话。若营销域抛异常，Feign 只剩一个 HTTP 状态码，
     * 那句话就丢了 —— 而它恰恰是用户唯一需要的信息。
     */
    public record LockResult(boolean success, String code, long discountAmount, String reason) {
    }

    /** 解锁请求（按订单号） */
    public record ReleaseRequest(String orderNo) {
    }

    /** 解锁结果：{@code released} 为 0 是常态（该单没用券） */
    public record ReleaseResult(int released) {
    }

    /** 核销请求（按订单号） */
    public record ConsumeRequest(String orderNo) {
    }

    /** 核销结果 */
    public record ConsumeResult(int consumed) {
    }
}
