package com.duoduo.ai.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 红点/角标补丁：只描述「变化量」，不是全量角标。
 *
 * <p>四个字段都是可空的：{@code null} 表示「这一项没有变化」，前端保持原值即可。
 * 若用 0 表示无变化，前端就无法区分「无变化」与「清零」，会出现角标该消失却不消失的问题，
 * 因此本类型整体 {@link JsonInclude.Include#NON_NULL} —— null 字段直接不出现在 JSON 里。
 *
 * @param notification 未读通知数
 * @param order        订单待办数（待付款/待发货等）
 * @param afterSale    售后待处理数
 * @param support      客服未读数
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BadgePatch(
        Integer notification,
        Integer order,
        Integer afterSale,
        Integer support
) {
}
