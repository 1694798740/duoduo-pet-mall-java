package com.duoduo.ai.client.dto;

import java.util.List;

/**
 * 交易域（duoduo-trade）订单视图（AI 侧只读）。
 *
 * <p>刻意**不**声明收货人姓名 / 电话 / 地址：AI 回答「我的订单到哪了」不需要这些，
 * 服务间接口按调用方真正需要的字段裁剪，是 AGENTS.md 硬规矩 4 的要求，
 * 也避免把一份隐私数据复制进提示词上下文。
 */
public final class ButlerOrderDtos {

    private ButlerOrderDtos() {
    }

    /** 订单分页（结构对齐 duoduo-common 的 PageResult）。 */
    public record OrderPage(List<OrderView> items, long total, int page, int pageSize) {
    }

    /** 订单列表行：只保留 AI 要转述的字段。 */
    public record OrderView(
            String orderNo,
            String status,
            String statusLabel,
            long totalAmount,
            long payAmount,
            String createdAt,
            List<OrderItemView> items
    ) {
    }

    /** 订单明细行（用于生成「商品标题 等 N 件」摘要）。 */
    public record OrderItemView(
            String title,
            String specText,
            int quantity
    ) {
    }
}
