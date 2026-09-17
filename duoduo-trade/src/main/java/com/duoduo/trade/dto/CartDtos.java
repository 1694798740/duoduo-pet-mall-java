package com.duoduo.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 交易域 · 购物车 DTO 集合。
 *
 * <p>约定：
 * <ul>
 *   <li>金额一律整数「分」，字段名以 Amount / price 结尾的不做小数转换</li>
 *   <li>「有效性」由服务端判定并给出机器可读的原因码，前端只负责展示文案，
 *       避免「什么算失效」这套规则在前后端各写一遍而对不上</li>
 * </ul>
 */
public final class CartDtos {

    private CartDtos() {
    }

    /** 单条购物车项 */
    public record CartItemView(
            Long skuId,
            Long spuId,
            String title,
            String specText,
            String image,
            long price,
            int quantity,
            /** 小计（分）= price × quantity；失效项为 0 */
            long amount,
            /** 商品服务返回的可售库存，供前端做数量上限提示 */
            int availableStock,
            boolean selected,
            /** 是否可参与结算；false 时 invalidReason 必有值 */
            boolean valid,
            /** 失效原因码：SKU_NOT_FOUND / OFF_SHELF / OUT_OF_STOCK / STOCK_NOT_ENOUGH */
            String invalidReason
    ) {
    }

    /** 购物车整体视图 */
    public record CartView(
            List<CartItemView> items,
            /** 条目数（行数，不含数量） */
            int itemCount,
            /** 商品件数（各条数量之和） */
            int totalQuantity,
            /** 已勾选且有效的条数 */
            int selectedCount,
            /** 已勾选且有效的小计合计（分）—— 这就是结算页的「商品总额」 */
            long selectedAmount,
            /** 当前失效原因码去重后的集合，前端可据此提示「有 2 件商品已下架」 */
            List<String> invalidReasons
    ) {
    }

    /** 加购请求：quantity 省略时按 1 件处理 */
    public record AddCartRequest(
            @NotNull(message = "skuId 不能为空") Long skuId,
            @Min(value = 1, message = "购买数量至少为 1")
            @Max(value = 200, message = "单个规格最多购买 200 件")
            Integer quantity
    ) {
    }

    /** 修改数量请求 */
    public record UpdateQuantityRequest(
            @NotNull(message = "数量不能为空")
            @Min(value = 1, message = "购买数量至少为 1")
            @Max(value = 200, message = "单个规格最多购买 200 件")
            Integer quantity
    ) {
    }

    /** 勾选 / 取消勾选 */
    public record SelectionRequest(
            @NotNull(message = "selected 不能为空") Boolean selected
    ) {
    }

    /** 失效原因码常量（对外契约的一部分，改动需同步前端） */
    public static final class InvalidReason {
        public static final String SKU_NOT_FOUND = "SKU_NOT_FOUND";
        public static final String OFF_SHELF = "OFF_SHELF";
        public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
        public static final String STOCK_NOT_ENOUGH = "STOCK_NOT_ENOUGH";

        private InvalidReason() {
        }
    }
}
