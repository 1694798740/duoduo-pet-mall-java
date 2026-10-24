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

    /**
     * 批量库存变更请求（一个订单的多个 SKU 一次提交）。
     *
     * @param orderId 订单 id：{@code freeze} / {@code release} / {@code deduct} 必填。
     *                商品域按 {@code (order_id, sku_id)} 记预占台账，释放与扣减只作用于
     *                **本订单自己的**预占，不再靠 SKU 上的全局冻结量判断归属。
     * @param refNo   **入库幂等号**（第五十八轮 · B-02）：仅 {@code restock} 必需。
     *                同一 refNo 的同一 SKU 只会真正加回一次库存；重复提交（响应丢失后的重试）
     *                原样返回成功但**不再加库存**。其他动作可传 null。
     */
    public record StockChangeRequest(List<StockChangeItem> items, Long orderId, String refNo) {
    }

    /**
     * 单个 SKU 的变更结果。
     * 失败原因码：SKU_NOT_FOUND / SKU_INACTIVE / INSUFFICIENT_STOCK / FREEZE_NOT_ENOUGH /
     * RESTOCK_FAILED / RESERVATION_NOT_FOUND / RESERVATION_QTY_MISMATCH / RESERVATION_STATE_CONFLICT
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
        /** 扣减时本订单根本没有预占记录（不是「已扣过」，是真的没冻过） */
        public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
        /** 请求数量与本订单已登记的预占数量不一致，拒绝按错误数量释放 / 扣减 */
        public static final String RESERVATION_QTY_MISMATCH = "RESERVATION_QTY_MISMATCH";
        /** 预占状态与本次操作冲突（如已释放后又来扣减、已扣减后又来释放） */
        public static final String RESERVATION_STATE_CONFLICT = "RESERVATION_STATE_CONFLICT";

        private Reason() {
        }
    }
}
