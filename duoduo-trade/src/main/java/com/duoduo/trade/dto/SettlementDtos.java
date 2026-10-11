package com.duoduo.trade.dto;

import com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 交易域 · 资金托管 DTO。
 *
 * <p>金额一律整数「分」；时间一律 ISO8601 UTC 字符串。
 *
 * <h2>★ 为什么这里的每个「量」字段都要显式标注 {@code @JsonSerialize}</h2>
 * 全站的 Long 序列化策略是「默认按字符串输出，只有名字命中白名单的才保持数字」
 * （{@code LongToStringJacksonModule}）。而该白名单是通过
 * {@code BeanSerializerModifier.changeProperties} 生效的，**record 的属性不流经那条路径**——
 * 于是 record 组件里的 {@code long} 会被当成标识、以字符串形态下发。
 *
 * <p>后果不是"看着不好看"，而是**功能性错误**：前端 {@code types.ts} 声明的是 number，
 * 实况却是 {@code "2382"}。JS 里 {@code "2382" / 100} 因隐式转换"看着正常"，
 * 但 {@code +} 会变成字符串拼接、{@code ===} 恒 false、{@code .toFixed()} 直接抛错 ——
 * 属 T-40 同一类缺陷。判据很简单：**这个数前端会拿去做加减/比较吗？** 会 → 必须标注。
 *
 * <p>{@code sellerId} / {@code tenantId} 这类**标识**刻意**不**标注：它们是 id，
 * 18 位雪花值超过 JS 安全整数上限，必须以字符串下发。
 * （已经踩过一次：本文件的金额字段漏标 → 前端 {@code formatPrice} 拿到字符串，
 * 页面金额是否正常取决于格式化函数恰好用了隐式转换，纯属侥幸。）
 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    /**
     * 资金托管视图。
     *
     * <p>{@code sellerAmount} = {@code amount} − {@code platformFee}，
     * 由服务端算好给出，而不是让前端做减法 ——
     * 前端做减法意味着「佣金口径」在很多个页面各实现一遍，
     * 以后调整费率或加阶梯规则时必然出现某些页面没有同步更新。
     */
    public record SettlementView(
            String orderNo,
            /** 卖家 id：**标识**，保持字符串下发（雪花值超 JS 安全整数） */
            Long sellerId,
            /** 托管金额（= 买家实付） */
            @JsonSerialize(using = NumericLongSerializer.class) long amount,
            /** 平台佣金 */
            @JsonSerialize(using = NumericLongSerializer.class) long platformFee,
            /** 卖家可得 = amount − platformFee */
            @JsonSerialize(using = NumericLongSerializer.class) long sellerAmount,
            String status,
            /** 状态中文文案，由服务端给出 */
            String statusLabel,
            String releaseAt,
            String releasedAt,
            String createdAt
    ) {
    }

    /**
     * E22 · 卖家钱包视图 ——「我能取多少钱」的唯一答案。
     *
     * <p>四个金额字段的口径见 {@code SettlementService.walletOf}，这里只强调一点：
     * <b>{@code netWithdrawableAmount} 才是真正能提的数</b>，{@code withdrawableAmount}
     * 是抵扣欠款前的毛额。两个都给，是因为用户看到"有 500 可提、却被扣了 200 欠款"时，
     * 需要一个能对上账的解释；只给净额会让人以为系统少给了钱。
     */
    public record SellerWalletView(
            /** 待结算：钱还在平台托管，订单尚未确认收货 */
            @JsonSerialize(using = NumericLongSerializer.class) long pendingAmount,
            /** 冻结中：已记账到卖家名下，但仍在售后期内（T+N 未到期） */
            @JsonSerialize(using = NumericLongSerializer.class) long frozenAmount,
            /** 可提现（毛额）：已过售后期，尚未抵扣欠款 */
            @JsonSerialize(using = NumericLongSerializer.class) long withdrawableAmount,
            /** 累计到账：已记账给卖家的总额（含冻结中与已被垫付的） */
            @JsonSerialize(using = NumericLongSerializer.class) long releasedAmount,
            /** 未结欠款（平台垫付形成，见 {@code t_seller_debt}） */
            @JsonSerialize(using = NumericLongSerializer.class) long outstandingDebt,
            /** 可提现（净额）= 毛额 − 欠款，且不小于 0；**提现以此为准** */
            @JsonSerialize(using = NumericLongSerializer.class) long netWithdrawableAmount,
            /** 下一笔资金解冻时间（ISO-8601 UTC）；当前无冻结资金时为 null */
            String nextReleaseAt,
            /** 售后期天数，前端据此解释"为什么还不能提" */
            int holdDays
    ) {
    }

    /**
     * E22 · 结算账单（租户 × 周期）。
     *
     * <h2>为什么账单要分「本期发生额」与「实时快照」两段</h2>
     * 这两段刻意**不合并**，因为它们回答的是两个问题，而且可信度不一样：
     * <ul>
     *   <li><b>本期发生额</b>：这个周期里发生了什么。按事件时间严格过滤，
     *       任何一天都可以重新跑一遍得到同一个数 —— 财务要的就是这个可复核性；</li>
     *   <li><b>实时快照</b>：截至你看的这一秒，账上还剩多少。
     *       它来自与钱包页**同一段计算**（{@code walletOf}），
     *       所以商家端、平台端、将来的提现接口三处必然是同一个数。</li>
     * </ul>
     * 如果把快照强行算成"期末值"，就得改写成一套第二口径的回溯逻辑；
     * 两套口径一旦存在，迟早会在某个边界上分叉，而分叉的那天没人说得清谁对。
     *
     * <p><b>金额一律整数「分」</b>；时间一律 ISO-8601 UTC 字符串。
     * {@code periodStart} 含、{@code periodEnd} **不含**（左闭右开，
     * 与 {@code java.time} 的惯例一致，也让"10 月的最后一秒"不会被记进 11 月）。
     *
     * @param period              规范化后的周期串（{@code yyyy-MM} 或 {@code yyyy-MM-dd}）
     * @param periodStart         周期起点（含）
     * @param periodEnd           周期终点（不含）
     * @param tenantId            租户键 = 店铺主体 userId（与 E20 同一口径）
     * @param orderAmount         本期新进托管的买家实付合计
     * @param platformFeeAmount   本期佣金合计（**这同时就是本期平台收入**）
     * @param sellerEarningAmount 本期新进托管中卖家应得 = orderAmount − platformFeeAmount
     * @param orderCount          本期新进托管笔数
     * @param releasedAmount      本期放款（记账）合计
     * @param releasedSellerAmount 本期放款中卖家应得
     * @param releasedCount       本期放款笔数
     * @param unfrozenAmount      本期解冻（转为可提现）合计 —— 售后期在本期内到期的那部分
     * @param unfrozenCount       本期解冻笔数
     * @param refundedAmount      本期退回买家合计
     * @param refundedCount       本期退回笔数
     * @param advancedAmount      本期平台垫付合计（每笔同时形成一笔卖家欠款）
     * @param advancedCount       本期垫付笔数
     * @param pendingAmount       快照 · 待结算（钱还在平台，尚未放款）
     * @param frozenAmount        快照 · 冻结中（已记账，仍在售后期内）
     * @param withdrawableAmount  快照 · 可提现（毛额，未抵扣欠款）
     * @param outstandingDebt     快照 · 未结欠款
     * @param snapshotAt          快照生成时刻（ISO-8601 UTC）；与实时快照一起看才有意义
     */
    public record SettlementBill(
            String period,
            String periodStart,
            String periodEnd,
            /** 租户键（店铺主体 userId）：**标识**，保持字符串下发 */
            Long tenantId,
            @JsonSerialize(using = NumericLongSerializer.class) long orderAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long platformFeeAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long sellerEarningAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long orderCount,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedSellerAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedCount,
            @JsonSerialize(using = NumericLongSerializer.class) long unfrozenAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long unfrozenCount,
            @JsonSerialize(using = NumericLongSerializer.class) long refundedAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long refundedCount,
            @JsonSerialize(using = NumericLongSerializer.class) long advancedAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long advancedCount,
            @JsonSerialize(using = NumericLongSerializer.class) long pendingAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long frozenAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long withdrawableAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long outstandingDebt,
            String snapshotAt
    ) {
    }
}
