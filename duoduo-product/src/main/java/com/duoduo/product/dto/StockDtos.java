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
     * 失败原因码：SKU_NOT_FOUND / SKU_INACTIVE / INSUFFICIENT_STOCK / FREEZE_NOT_ENOUGH / RESTOCK_FAILED
     */
    public record StockChangeResult(Long skuId, int quantity, boolean success, String reason) {
    }

    public record StockChangeResponse(String action, boolean allSuccess, List<StockChangeResult> results) {
    }

    /**
     * 失败原因码常量（对外契约）。
     *
     * <p>调用方按原因码做**不同处理**，所以原因码必须区分开：
     * {@link #FREEZE_NOT_ENOUGH} 表示「对方已无此冻结」，可安全冲正台账；
     * {@link #INSUFFICIENT_STOCK} 表示真的卖超了，必须报错给用户；
     * 两者混成一个码，就会把「重复释放」当成故障反复重试，或者反过来把超卖当成正常。
     */
    public static final class Reason {
        public static final String SKU_NOT_FOUND = "SKU_NOT_FOUND";
        public static final String SKU_INACTIVE = "SKU_INACTIVE";
        public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
        public static final String FREEZE_NOT_ENOUGH = "FREEZE_NOT_ENOUGH";
        /** 入库失败（SKU 不存在等数据异常） */
        public static final String RESTOCK_FAILED = "RESTOCK_FAILED";

        private Reason() {
        }
    }
}
