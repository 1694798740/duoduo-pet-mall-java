package com.duoduo.product.client.dto;

import java.util.List;

/** SKU 订单占用查询请求（A-14）；交易域会去重并限制单次 100 个。 */
public record SkuUsageQuery(List<Long> skuIds) {
}
