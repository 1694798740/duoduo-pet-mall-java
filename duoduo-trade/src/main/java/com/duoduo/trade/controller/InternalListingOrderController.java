package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.trade.dto.OrderDtos.ListingOrderCreated;
import com.duoduo.trade.dto.OrderDtos.ListingOrderPlacement;
import com.duoduo.trade.dto.OrderDtos.ListingSoldOrderView;
import com.duoduo.trade.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 闲置担保交易的交易域内部端点（B-11）：只收商品域 ListingOrderService 的委托。
 *
 * <p><b>内部接口</b>：整个前缀 {@code /api/orders/internal/**} 由本域
 * {@code InternalTokenFilter} 校验 {@code X-Internal-Token}（失败关闭）。
 * 端点一律不接受「前端能传的信任字段」—— 买家、卖家、价格全部来自
 * 商品域服务端取的事实（{@link ListingOrderPlacement} 的注释），
 * 个人的卖家身份靠 {@code operatorId == t_order.sellerId} 的归属校验，
 * 而不是请求里的自报身份。
 *
 * <p>为什么挂 {@code /api/orders} 而不是 {@code /api/seller/**}：
 * 后者按入口边界（E8）只对商家平台入口开放，个人闲置卖家是普通 USER，
 * 从商城站点登录 —— 网关到不了那边。内部端点经 Nacos 直达本服务，
 * 不受网关入口裁剪影响，对外则被 Filter 的凭据墙挡住。
 */
@RestController
@RequestMapping("/api/orders")
public class InternalListingOrderController {

    private final OrderService orderService;

    public InternalListingOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** C2C 建单（orderType=C2C、单件无 SKU、不冻库存）；幂等键撞车按首单重放 */
    @PostMapping("/internal/listing-orders")
    public ApiResponse<ListingOrderCreated> placeListingOrder(@RequestBody ListingOrderPlacement placement) {
        return ApiResponse.ok(orderService.placeListingOrder(placement));
    }

    /**
     * 幂等键反查：商品域 CAS 落空时先来这问 —— 有单是重试（重放首单），没单才是真被买走。
     * 返回 null 表示该键下没有 C2C 单。
     */
    @GetMapping("/internal/listing-orders/idem")
    public ApiResponse<ListingOrderCreated> findCreatedByIdempotentKey(
            @RequestParam("buyerId") Long buyerId,
            @RequestParam("key") String key) {
        return ApiResponse.ok(orderService.findC2CCreatedByIdempotentKey(buyerId, key));
    }

    /** 卖家视角卖出订单列表：sellerId 由商品域从登录态带入，本方法自己加归属条件 */
    @GetMapping("/internal/listing-orders/sold")
    public ApiResponse<PageResult<ListingSoldOrderView>> soldOrders(
            @RequestParam("sellerId") Long sellerId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam("page") int page,
            @RequestParam("pageSize") int pageSize) {
        return ApiResponse.ok(orderService.listListingSold(sellerId, status, page, pageSize));
    }

    /**
     * 闲置订单发货：归属（sellerId == operatorId）与状态机（PAID → SHIPPED）都在服务层校验。
     * 路径刻意用 {@code listing-ship} 而不是 {@code /ship} 结尾 ——
     * 本域 InternalTokenFilter 对 {@code /ship} 后缀一律要求内部凭据（历史遗留的宽匹配），
     * 这里反正已在前缀墙内，命名上避开歧义即可。
     */
    @PostMapping("/internal/{orderNo}/listing-ship")
    public ApiResponse<Void> shipListingOrder(@PathVariable("orderNo") String orderNo,
                                             @RequestParam("operatorId") Long operatorId,
                                             @RequestParam("logisticsCompany") String logisticsCompany,
                                             @RequestParam("logisticsNo") String logisticsNo) {
        orderService.shipListingOrder(orderNo, operatorId, logisticsCompany, logisticsNo);
        return ApiResponse.ok();
    }
}
