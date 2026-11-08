package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.product.client.dto.ListingOrderCreated;
import com.duoduo.product.client.dto.ListingSoldOrderView;
import com.duoduo.product.dto.ProductDtos.ListingOrderRequest;
import com.duoduo.product.dto.ProductDtos.ListingShipRequest;
import com.duoduo.product.service.ListingOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 闲置担保交易的对外端点（B-11）：买家下单、卖家查卖出、卖家发货。
 *
 * <p><b>为什么挂 {@code /api/listings} 而不是 {@code /api/seller/**}</b>：
 * 个人闲置卖家是普通 USER（发布闲置只要求实名，不要求 SELLER 角色），
 * 后者有角色门禁与入口边界（E8）—— 他进不去。这里全部以登录态为准，
 * 归属校验下沉到交易域（卖出列表按登录人过滤、发货要求订单归属）。
 *
 * <p>路径都刻意避开单段 {@code /{id}}：下单是 {@code /{id:\d+}/orders}（两段），
 * 卖出/发货在 {@code /orders/...} 字面量段下 —— 与既有 {@code /{id:\\d+}} 详情
 * 路由互不冲突（既有 {@code /{id}/status} 等三段 POST 同理共存）。
 */
@RestController
@RequestMapping("/api/listings")
public class ListingOrderController {

    private final ListingOrderService listingOrderService;

    public ListingOrderController(ListingOrderService listingOrderService) {
        this.listingOrderService = listingOrderService;
    }

    /**
     * 买家下单（担保成交）：以<b>路径</b>里的闲置为准，请求体里的 listingId 一律忽略 ——
     * 防止「详情页看 A、请求体买 B」的错位；其余收货信息与幂等键来自请求体。
     * CAS 防超卖、补偿回滚都在服务层（见 {@link ListingOrderService}）。
     */
    @PostMapping("/{id:\\d+}/orders")
    public ApiResponse<ListingOrderCreated> placeOrder(@PathVariable("id") Long id,
                                                       @RequestBody ListingOrderRequest request) {
        ListingOrderRequest normalized = new ListingOrderRequest(id,
                request.receiverName(), request.receiverPhone(),
                request.receiverAddress(), request.idempotentKey());
        return ApiResponse.ok(listingOrderService.placeOrder(UserContext.requireUserId(), normalized));
    }

    /** 卖家的「卖出订单」列表（登录即本人；数据事实在交易域，此处透传） */
    @GetMapping("/orders/sold")
    public ApiResponse<PageResult<ListingSoldOrderView>> soldOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(listingOrderService.listSold(
                UserContext.requireUserId(), status, page, pageSize));
    }

    /**
     * 卖家发货：归属（订单必须是登录人卖出的闲置单）与状态机（PAID 才可发）
     * 都在交易域校验 —— 本域不重复判断，避免两处口径漂移。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public ApiResponse<Void> ship(@PathVariable("orderNo") String orderNo,
                                  @RequestBody ListingShipRequest request) {
        listingOrderService.ship(UserContext.requireUserId(), orderNo,
                request.logisticsCompany(), request.logisticsNo());
        return ApiResponse.ok();
    }
}
