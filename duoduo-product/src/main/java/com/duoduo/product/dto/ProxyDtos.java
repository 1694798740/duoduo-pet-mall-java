package com.duoduo.product.dto;

import com.duoduo.product.entity.ProxyWorkOrderEntity;

import java.util.List;

/**
 * E23 · 代运营（商品域）的请求 / 视图。
 *
 * <p>与交易域的 {@code ProxyDtos} 同构。<b>这里的 Long 字段全部是标识</b>
 * （工单 id、租户 id、申请人 id、执行人 id），而 record 组件在
 * {@code LongToStringJacksonModule} 下的默认行为就是「按字符串输出」——
 * 正是标识需要的（雪花 id 18~19 位，超过 JS 安全整数上限，下发数字会被静默舍入）。
 * 所以本文件**一个 {@code @JsonSerialize} 都不该出现**：那些注解是给金额 / 计数用的，
 * 在这里标上会把 id 变成数字，正好把这行唯一要防的事做反。
 */
public final class ProxyDtos {

    private ProxyDtos() {
    }

    /**
     * 商家端登记代运营申请（授权凭证）。
     *
     * <p>{@code tenantId} / {@code requesterUserId} 一律由服务端从登录态推导 ——
     * 允许请求体指定的话，「商家申请」就变成了「谁都能替别人申请」。
     */
    public record ProxyRequestCreate(
            String targetType,
            String targetRef,
            String actionType,
            String reason) {
    }

    /** 平台执行代下架。{@code reason} 是**执行说明**，与工单里的「申请事由」是两件事，都要留。 */
    public record ProxyOffShelfRequest(
            String workOrderNo,
            String reason) {
    }

    /**
     * 工单视图。
     *
     * <p>{@code consumedAt / createdAt} 用字符串下发（UTC，带 Z）：
     * 前端拿到的是可直接展示、且不会被各地时区解释成不同时刻的原文。
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
