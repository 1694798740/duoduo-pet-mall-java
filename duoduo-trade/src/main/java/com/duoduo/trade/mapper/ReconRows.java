package com.duoduo.trade.mapper;

import java.time.LocalDateTime;

/**
 * 对账取数用的行对象（S3）。
 *
 * <p><b>为什么不复用各业务实体</b>：对账只需要每张表的 4~6 列，
 * 而这些查询的形态也很特别（跨表 LEFT JOIN、按窗口 + 按 id 混合取、带聚合）。
 * 复用实体意味着把每个实体的全部字段都拉回来、并且以后业务实体加字段时
 * 这些查询会被动跟着变 —— 而"对账口径不应随业务实体演进而改变"。
 *
 * <p><b>为什么不用 {@code Map<String,Object>}</b>：Map 看起来省事，
 * 但把列名变成字符串之后，列名打错不会编译报错、只会在运行时静默拿到 null，
 * 而对账恰好是"拿到 null 也不报错、只是判据少判一次"的场景 ——
 * 静默失效正是这套代码最需要避免的失败方式（见 classes 注释里的铁律）。
 * 显式属性名让编译器和 IDE 都能帮忙。
 *
 * <p>属性名与 SQL 里的 {@code AS} 别名逐一对应；类型用包装类型，
 * 因为 LEFT JOIN 的右表列可能为 null（例如冻结单找不到订单时的 {@code orderStatus}）。
 */
public final class ReconRows {

    private ReconRows() {
    }

    /** {@code t_order} 的对账视图 */
    public static class OrderRow {
        private Long orderId;
        private String status;
        private Long sellerId;
        private Long payAmount;
        /** 确认收货时间（未完成时为空） */
        private LocalDateTime completedAt;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getSellerId() { return sellerId; }
        public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
        public Long getPayAmount() { return payAmount; }
        public void setPayAmount(Long payAmount) { this.payAmount = payAmount; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    /** {@code t_settlement} 的对账视图 */
    public static class SettlementRow {
        private Long orderId;
        private Long sellerId;
        private String status;
        private Long amount;
        private Long platformFee;
        private LocalDateTime releasedAt;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getSellerId() { return sellerId; }
        public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public Long getPlatformFee() { return platformFee; }
        public void setPlatformFee(Long platformFee) { this.platformFee = platformFee; }
        public LocalDateTime getReleasedAt() { return releasedAt; }
        public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
    }

    /** {@code t_seller_debt} 的对账视图 */
    public static class DebtRow {
        private Long orderId;
        private Long amount;
        private String status;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** {@code t_stock_freeze} LEFT JOIN {@code t_order} 的对账视图 */
    public static class FreezeRow {
        private Long skuId;
        private Long orderId;
        private Integer quantity;
        private String status;
        private LocalDateTime expireAt;
        /** 关联订单状态；订单不存在时为 {@code null}（这本身是一条差异，不能当成"未知"放过） */
        private String orderStatus;

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getExpireAt() { return expireAt; }
        public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
        public String getOrderStatus() { return orderStatus; }
        public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
    }

    /** 订单明细按 (订单 × SKU) 聚合后的数量 */
    public static class ItemQtyRow {
        private Long orderId;
        private Long skuId;
        private Long quantity;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public Long getQuantity() { return quantity; }
        public void setQuantity(Long quantity) { this.quantity = quantity; }
    }

    /** 交易域按 SKU 汇总的「冻结中」数量 */
    public static class SkuFrozenRow {
        private Long skuId;
        private Long quantity;

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public Long getQuantity() { return quantity; }
        public void setQuantity(Long quantity) { this.quantity = quantity; }
    }
}
