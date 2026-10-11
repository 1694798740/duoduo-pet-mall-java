package com.duoduo.trade.dto;

import com.duoduo.trade.entity.ProxyWorkOrderEntity;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer;

import java.util.List;

/**
 * E23 · 代运营（交易域）的请求 / 视图。
 *
 * <h2>★ 这里的 Long 字段为什么一个 {@code @JsonSerialize} 都没加</h2>
 * 它们**全部是标识**（工单 id、租户 id、申请人 id、执行人 id），而
 * {@code LongToStringJacksonModule} 对 record 组件的默认行为就是「按字符串输出」——
 * 正是标识需要的（雪花 id 18~19 位，超过 JS 安全整数 2^53−1，下发数字会被静默舍入）。
 *
 * <p>反过来，**只有金额 / 计数**才需要显式标注 {@link NumericLongSerializer} 把类型掰回数字
 * （见 {@code SettlementDtos} 的同类说明）。本文件里没有这类字段，
 * 所以一个注解都不该出现 —— 若将来给工单加了「涉及金额」之类的量字段，
 * 那时才需要标，而不是现在先标上"以防万一"：乱标会把 id 变成数字，正好把这个文件唯一要防的事做反。
 */
public final class ProxyDtos {

    private ProxyDtos() {
    }

    /**
     * 商家端登记代运营申请（授权凭证）。
     *
     * <p>{@code tenantId} / {@code requesterUserId} <b>不由请求体提供</b>，
     * 一律由服务端从登录态推导 —— 允许请求体指定的话，
     * 「商家申请」就变成了「谁都能替别人申请」，授权凭证瞬间失去意义。
     */
    public record ProxyRequestCreate(
            String targetType,
            String targetRef,
            String actionType,
            String reason) {
    }

    /**
     * 平台执行代发货。
     *
     * <p>{@code reason} 与工单里的 reason 是**两件事**，都要留：
     * 工单里那句是「商家为什么请求协助」，这里这句是「平台实际怎么做的」。
     * 合成一条就无法回答「商家说的和平台做的对不对得上」——
     * 而这恰恰是事后追责时唯一想知道的。
     */
    public record ProxyShipRequest(
            String workOrderNo,
            String reason,
            String logisticsCompany,
            String logisticsNo) {
    }

    /** 平台代第三方店铺处理售后（同意退款）。 */
    public record ProxyAftersaleRequest(
            String workOrderNo,
            String reason) {
    }

    /**
     * 工单视图。
     *
     * <p>{@code consumedAt / createdAt} 用字符串下发（UTC，带 Z）：
     * 让前端拿到的是可以直接展示、且不会被各地时区解释成不同时刻的原文。
     */
    public record ProxyWorkOrderView(
            Long id,
            String workOrderNo,
            Long tenantId,
            String targetType,
            String targetRef,
            String actionType,
            String actionLabel,
            String reason,
            Long requesterUserId,
            String status,
            String statusLabel,
            String consumedAt,
            Long consumedBy,
            String createdAt) {

        public static ProxyWorkOrderView of(ProxyWorkOrderEntity e, String consumedAtIso, String createdAtIso) {
            return new ProxyWorkOrderView(
                    e.getId(),
                    e.getWorkOrderNo(),
                    e.getTenantId(),
                    e.getTargetType(),
                    e.getTargetRef(),
                    e.getActionType(),
                    ProxyWorkOrderEntity.actionLabelOf(e.getActionType()),
                    e.getReason(),
                    e.getRequesterUserId(),
                    e.getStatus(),
                    ProxyWorkOrderEntity.labelOf(e.getStatus()),
                    consumedAtIso,
                    e.getConsumedBy(),
                    createdAtIso);
        }
    }

    /** 平台待办列表（只读） */
    public record ProxyWorkOrderPage(
            List<ProxyWorkOrderView> items,
            long openCount) {
    }
}
