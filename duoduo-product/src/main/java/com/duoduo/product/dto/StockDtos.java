package com.duoduo.product.dto;

import java.util.List;

/**
 * 库存操作 DTO（供交易服务内部调用）。
 */
public final class StockDtos {

    private StockDtos() {
    }

    /** 单个 SKU 的库存变更项 */
    public record StockChangeItem(Long skuId, Integer quantity) {
    }

    /** 批量库存变更请求（一个订单的多个 SKU 一次提交） */
    public record StockChangeRequest(List<StockChangeItem> items) {
    }

    /**
     * 单个 SKU 的变更结果。
     * 失败原因码：SKU_NOT_FOUND / SKU_INACTIVE / INSUFFICIENT_STOCK / FREEZE_NOT_ENOUGH
     */
    public record StockChangeResult(Long skuId, int quantity, boolean success, String reason) {
    }

    public record StockChangeResponse(String action, boolean allSuccess, List<StockChangeResult> results) {
    }

    /** 失败原因码常量（对外契约） */
    public static final class Reason {
        public static final String SKU_NOT_FOUND = "SKU_NOT_FOUND";
        public static final String SKU_INACTIVE = "SKU_INACTIVE";
        public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
        public static final String FREEZE_NOT_ENOUGH = "FREEZE_NOT_ENOUGH";

        private Reason() {
        }
    }
}
