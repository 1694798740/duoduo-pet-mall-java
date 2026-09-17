package com.duoduo.trade.client.dto;

import java.util.List;

/**
 * 库存变更的跨服务传输对象（与服务端 {@code com.duoduo.product.dto.StockDtos} 字段对齐）。
 *
 * <p>同 {@link SkuSnapshot} 的理由：不共享类，只对齐字段名。
 * 服务端新增字段不会影响本服务的反序列化；服务端**改名**才是破坏性变更，
 * 因此这里在注释里把契约写清楚。
 */
public final class StockChangeDtos {

    private StockChangeDtos() {
    }

    public record StockChangeItem(Long skuId, Integer quantity) {
    }

    /** 请求体：{@code {"items":[{"skuId":2001,"quantity":2}]}} */
    public record StockChangePayload(List<StockChangeItem> items) {
    }

    public record StockChangeResult(Long skuId, int quantity, boolean success, String reason) {
    }

    public record StockChangeOutcome(String action, boolean allSuccess, List<StockChangeResult> results) {
    }
}
