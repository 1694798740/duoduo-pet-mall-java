package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.client.dto.SkuUsageInfo;
import com.duoduo.product.client.dto.SkuUsageQuery;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 交易域 · SKU 订单占用查询（A-14）：删除规格前的硬门禁。
 *
 * <p><b>contextId 必须显式给</b>：同一个 {@code duoduo-trade} 在本应用里还没有别的契约，
 * 但按商品域既有习惯（成员 / 资质 / 风险三份客户端）显式命名，避免以后新增第二个契约时
 * 撞 {@code BeanDefinitionOverrideException} —— 那是启动即崩、而单测用 Mockito 发现不了的错误。
 *
 * <p>内部凭据由 {@code InternalCallConfig} 统一注入（每次调用重新签名）。
 */
@FeignClient(name = "duoduo-trade", contextId = "productSkuUsageClient", path = "/api/orders/internal")
public interface SkuUsageClient {

    /** 只返回「有订单」的 SKU；没有订单的 SKU 不出现（调用方按 0 处理） */
    @PostMapping("/sku-usage")
    ApiResponse<List<SkuUsageInfo>> usage(@RequestBody SkuUsageQuery query);
}
