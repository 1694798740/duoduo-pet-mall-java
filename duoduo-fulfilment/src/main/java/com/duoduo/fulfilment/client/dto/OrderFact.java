package com.duoduo.fulfilment.client.dto;

import java.util.List;

/**
 * 交易域的「订单事实」只读视图（与 {@code com.duoduo.trade.dto.OrderDtos.OrderFactView} 对齐）。
 *
 * <p>刻意<b>不含收货人信息</b>：评价资格校验只需要知道归属谁、是否已完成、买了什么。
 * 服务间接口按调用方真正需要什么裁剪字段，是减少隐私扩散面最省事的一步。
 *
 * <p>为什么手写一份而不是依赖交易域的 dto 包：跨服务共享 DTO 会让两个服务必须同时发布，
 * 一处加字段就可能让另一处编译不过。契约在这里只体现为「字段名与语义」，由冒烟测试守住。
 */
public record OrderFact(
        String orderNo,
        Long userId,
        Long sellerId,
        String status,
        String statusLabel,
        String finishedAt,
        List<Item> items
) {

    /** 订单明细（下单时刻快照） */
    public record Item(
            Long skuId,
            Long spuId,
            String title,
            String specText,
            String mainImage,
            long price,
            int quantity,
            long amount
    ) {
    }
}
