package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeOutcome;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangePayload;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务客户端（服务间调用，走 Nacos 服务发现 + 负载均衡，不经网关）。
 *
 * <p>为什么不走网关：网关承担的是「对外入口」职责（鉴权、限流、CORS）。
 * 服务间调用再绕一圈网关，会平白多一跳、多一次鉴权解析，且网关故障会放大成全站故障。
 *
 * <p>参数用「逗号分隔字符串」而不是 {@code List<Long>}：
 * 两端的集合参数编码规则（重复 query vs 逗号分隔）在不同 Feign 版本下有过变化，
 * 固定成单值字符串后两端行为完全确定。
 *
 * <p>库存写接口（freeze / release / deduct / restock）由 {@link com.duoduo.trade.config.InternalCallConfig}
 * 统一加上 {@code X-Internal-Token} 请求头，服务端会校验。
 */
@FeignClient(name = "duoduo-product", path = "/api/products")
public interface ProductClient {

    /**
     * 批量取 SKU 快照；参数为逗号分隔的 SKU ID，例如 {@code 2001,2002}。
     * 走内部路由（快照含卖家归属），由 {@code InternalCallConfig} 统一带 {@code X-Internal-Token}。
     */
    @GetMapping("/internal/skus/batch")
    ApiResponse<List<SkuSnapshot>> batchSkus(@RequestParam("ids") String skuIds);

    /**
     * S3 对账用：商品域里持有冻结库存（或库存异常）的 SKU id 清单。
     *
     * <p>必须存在的理由见 {@code ProductGateway#frozenHolderSkuIds} ——
     * 只有反查入口，才能把「交易域少记了冻结」这个方向也覆盖住。
     */
    @GetMapping("/internal/skus/frozen-holders")
    ApiResponse<List<Long>> frozenHolderSkus();

    /** 冻结库存（下单时调用）：可售余量足够才成功 */
    @PostMapping("/skus/stock/freeze")
    ApiResponse<StockChangeOutcome> freezeStock(@RequestBody StockChangePayload payload);

    /** 释放冻结（取消订单 / 超时关单时调用） */
    @PostMapping("/skus/stock/release")
    ApiResponse<StockChangeOutcome> releaseStock(@RequestBody StockChangePayload payload);

    /** 扣减库存（支付成功时调用） */
    @PostMapping("/skus/stock/deduct")
    ApiResponse<StockChangeOutcome> deductStock(@RequestBody StockChangePayload payload);

    /** 退货入库（退款成功后调用，把货补回库存） */
    @PostMapping("/skus/stock/restock")
    ApiResponse<StockChangeOutcome> restockStock(@RequestBody StockChangePayload payload);
}
