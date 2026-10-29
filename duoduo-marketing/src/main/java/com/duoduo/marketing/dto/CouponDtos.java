package com.duoduo.marketing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 营销域 · 券 DTO 集合。
 *
 * <p>约定（与全项目一致）：金额一律整数「分」；时间一律 ISO8601 UTC 字符串。
 *
 * <p>分三组：
 * <ul>
 *   <li>{@code TemplateView} / {@code CouponView}：对外读视图；</li>
 *   <li>{@code ClaimResult} / {@code UsableResult}：领券与「结算页可用券」的回执；</li>
 *   <li>{@code Lock*} / {@code Release*} / {@code Consume*}：<b>服务间</b>契约（交易域调用）。</li>
 * </ul>
 */
public final class CouponDtos {

    private CouponDtos() {
    }

    // ---------------- 对外读视图 ----------------

    /**
     * 券模板视图（领券中心）。
     *
     * <p>{@code claimable} 与 {@code claimableReason} 由服务端算好一起返回：
     * 「为什么这张券不能领」的判断（窗口未开始 / 已结束 / 已发完 / 已领过）
     * 涉及领取窗口、库存、<b>该用户已领记录</b>，前端拿不到这些数据，
     * 与其让前端猜，不如服务端直接给结论 + 文案。
     */
    public record TemplateView(
            Long id,
            String name,
            String description,
            String type,
            /** 券类型中文文案，前端不再维护映射表 */
            String typeLabel,
            long thresholdAmount,
            /** 满减券的固定抵扣额（分）；折扣券恒为 0 */
            long discountAmount,
            /** 折扣券折后应付比例（百分比）：85 = 8.5 折；满减券恒为 100 */
            int discountPercent,
            /** 折扣券封顶抵扣（分）：0 = 不封顶 */
            long maxDiscountAmount,
            int totalCount,
            int claimedCount,
            int remainingCount,
            String startAt,
            String endAt,
            String status,
            boolean claimable,
            /** 不可领时为原因文案；可领时为 null */
            String claimableReason
    ) {
    }

    /** 用户手上一张券的视图（我的券包） */
    public record CouponView(
            String code,
            Long templateId,
            String name,
            String type,
            String typeLabel,
            long thresholdAmount,
            /** 满减券的固定抵扣额（分）；折扣券恒为 0 */
            long discountAmount,
            /** 折扣券折后应付比例（百分比）：85 = 8.5 折 */
            int discountPercent,
            /** 折扣券封顶抵扣（分）：0 = 不封顶 */
            long maxDiscountAmount,
            String status,
            /** 状态中文文案（{@code com.duoduo.marketing.support.CouponStatus}） */
            String statusLabel,
            String claimedAt,
            String expireAt,
            /** 被哪个订单锁定；未锁定为 null */
            String lockedOrderNo,
            /** 被哪个订单核销；未核销为 null */
            String usedOrderNo
    ) {
    }

    // ---------------- 领券与可用券 ----------------

    /**
     * 领券结果。
     *
     * <p>{@code replay=true} 表示「你已经领过这张券」，本次没有新建 ——
     * 由唯一索引 {@code uk_coupon_user_tpl} 兜底后的诚实回执，
     * 而不是抛一个 409 让用户以为自己操作失败了。
     */
    public record ClaimResult(
            String code,
            Long templateId,
            String name,
            long thresholdAmount,
            long discountAmount,
            String expireAt,
            boolean replay
    ) {
    }

    /**
     * 结算页的一条候选券：带上「能不能用」与「为什么不能」。
     *
     * <p>{@code discountAmount} 是<b>本单实际可抵金额</b>，不是模板上的固定额：
     * 满减券取 {@code min(固定额, 订单金额)}，折扣券按折率算并按封顶裁剪。
     * 若这里回模板固定额，结算页会显示「抵 5 元」而下单只减 1 元 —— 两条链路各算一次
     * 正是「页面说能抵、实际抵不到」的来源。
     */
    public record UsableCoupon(
            String code,
            String name,
            String type,
            /** 券类型中文文案（满减券 / 折扣券） */
            String typeLabel,
            long thresholdAmount,
            /** 本单实际可抵金额（分） */
            long discountAmount,
            /** 折扣券折后应付比例（百分比）：85 = 8.5 折；满减券恒为 100 */
            int discountPercent,
            /** 折扣券封顶抵扣（分）：0 = 不封顶 */
            long maxDiscountAmount,
            String expireAt,
            boolean usable,
            /** 不可用时为原因文案；可用时为 null */
            String reason
    ) {
    }

    /**
     * 结算页可用券结果。
     *
     * <p>{@code bestCouponCode} / {@code bestDiscountAmount} 是服务端算出的「最优券」：
     * v1 口径是「未过期、未锁定、已达门槛的券里抵扣额最大的一张」。
     * 放在服务端算，是为了让「默认帮用户选最省钱的券」这件事只有一个实现，
     * 前端与后端不会各自选出一个不同的券。
     */
    public record UsableResult(
            long amount,
            List<UsableCoupon> coupons,
            String bestCouponCode,
            long bestDiscountAmount
    ) {
    }

    // ---------------- 服务间契约（交易域 → 营销域） ----------------

    /**
     * 锁券请求（下单时）。
     *
     * <p>{@code orderAmount} 是<b>该订单的商品总额</b>，由交易域给出：
     * 满减门槛判的是订单金额，而这个数字只有交易域知道（营销域不持有购物车与 SKU 价）。
     * 营销域只做「用这个金额去比门槛」这一件事，不反过来去查订单 ——
     * 否则两个服务之间就要再开一条查询通道。
     */
    public record LockRequest(
            @NotBlank String code,
            @NotNull Long userId,
            @NotBlank String orderNo,
            @Min(0) long orderAmount
    ) {
    }

    /**
     * 锁券结果。
     *
     * <p>{@code success=false} 时 {@code reason} 必给人话：
     * 「券不存在 / 不属于该用户 / 已使用 / 未达门槛 / 已被别的订单占用」。
     * 交易域把它直接转成 422 抛给下单方 —— 用户在结算页看到的就是这句话。
     */
    public record LockResult(
            boolean success,
            String code,
            long discountAmount,
            /** 成功时为 null */
            String reason
    ) {
        public static LockResult ok(String code, long discountAmount) {
            return new LockResult(true, code, discountAmount, null);
        }

        public static LockResult reject(String reason) {
            return new LockResult(false, null, 0L, reason);
        }
    }

    /** 解锁请求：按订单号解锁（不用券码 —— 交易域只知道订单号，这正是它该知道的） */
    public record ReleaseRequest(@NotBlank String orderNo) {
    }

    /**
     * 解锁结果。
     *
     * <p>{@code released} 是真实解锁的张数：0 表示这张订单本来就没锁券（或已解锁过），
     * 这是<b>常态</b>而不是错误 —— 大多数订单不用券，取消时调用它就会得到 0。
     */
    public record ReleaseResult(int released) {
    }

    /** 核销请求：按订单号核销 */
    public record ConsumeRequest(@NotBlank String orderNo) {
    }

    /** 核销结果：{@code consumed} 为 0 表示该订单没有待核销的券（同样可能是常态） */
    public record ConsumeResult(int consumed) {
    }

    /**
     * 券补偿调度一轮的结果（解锁超时锁定 + 标记过期）。
     */
    public record SweepResult(int scanned, int released, int expired) {
    }

    // ---------------- 运营建券 ----------------

    /**
     * 运营创建券模板的请求。
     *
     * <p>两种类型共用 {@code thresholdAmount}（券前商品金额门槛）：
     * <ul>
     *   <li>{@code FULL_REDUCE}：{@code discountAmount} 必填且 &gt; 0，{@code discountPercent} 忽略；</li>
     *   <li>{@code DISCOUNT}：{@code discountPercent} 必填（1~99，如 85 = 8.5 折），
     *       {@code discountAmount} 必须为 0，{@code maxDiscountAmount} 可选（0 = 不封顶）。</li>
     * </ul>
     * 两边混填一律 400，而不是「悄悄按某一种处理」—— 运营会以为自己建了另一种券。
     */
    public record CreateTemplateRequest(
            @NotBlank(message = "券名称不能为空")
            @Size(max = 100, message = "券名称过长") String name,

            @Size(max = 500, message = "券描述过长") String description,

            @NotBlank(message = "券类型不能为空") String type,

            @Min(value = 0, message = "门槛金额不能为负数") long thresholdAmount,

            @Min(value = 0, message = "抵扣金额不能为负数") long discountAmount,

            /** 折扣券折后应付比例（百分比）：85 = 8.5 折；满减券留空 */
            @Min(value = 0, message = "折扣比例不能为负数") Integer discountPercent,

            /** 折扣券封顶抵扣（分）：留空或 0 表示不封顶 */
            @Min(value = 0, message = "封顶抵扣不能为负数") Long maxDiscountAmount,

            @Min(value = 1, message = "发放总量必须大于 0") int totalCount,

            @NotBlank(message = "领取开始时间不能为空") String startAt,

            @NotBlank(message = "领取结束时间不能为空") String endAt
    ) {
    }
}
