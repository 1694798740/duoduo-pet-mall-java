package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.client.dto.ListingOrderCreated;
import com.duoduo.product.client.dto.ListingOrderPlacement;
import com.duoduo.product.client.dto.ListingSoldOrderView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 交易域内部客户端（B-11 闲置担保交易）。
 *
 * <p>职责边界：商品域只管「单件锁」（listing 状态 CAS）与「入口归属」，
 * 订单创建、发货状态机、托管与放款的事实全部在交易域 ——
 * 跨库不查、走对端内部端点（硬规矩 4）。
 *
 * <p>与 duoduo-social 的 {@code ListingClient} 同款纪律：contextId 必填，
 * 防止与其它同名 FeignClient 注册冲突（第五十三轮实测教训）。
 */
@FeignClient(name = "duoduo-trade", contextId = "listingOrder", path = "/api/orders")
public interface TradeClient {

    /**
     * C2C 下单（内部）：商品域已完成 listing CAS 锁定后才调这里，
     * 交易域只收快照事实建单（orderType=C2C、PENDING_PAYMENT）。
     */
    @PostMapping("/internal/listing-orders")
    ApiResponse<ListingOrderCreated> placeListingOrder(@RequestBody ListingOrderPlacement placement);

    /** 卖家视角的卖出订单列表（内部）：t_order 在交易域，列表事实由它出 */
    @GetMapping("/internal/listing-orders/sold")
    ApiResponse<PageResult<ListingSoldOrderView>> soldOrders(
            @RequestParam("sellerId") Long sellerId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam("page") int page,
            @RequestParam("pageSize") int pageSize);

    /**
     * 闲置订单发货（内部）：归属与状态校验都在交易域做 ——
     * C2C 单必须 sellerId == 操作者、只有 PAID 可发（SHIPPED 终点）。
     * 商品域不再重复校验（校验放交易域一处，避免两处口径漂移）。
     */
    @PostMapping("/internal/{orderNo}/listing-ship")
    ApiResponse<Void> shipListingOrder(@PathVariable("orderNo") String orderNo,
                                       @RequestParam("operatorId") Long operatorId,
                                       @RequestParam("logisticsCompany") String logisticsCompany,
                                       @RequestParam("logisticsNo") String logisticsNo);
}
