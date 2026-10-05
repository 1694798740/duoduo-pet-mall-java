package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.trade.dto.AnalyticsDtos.SellerAnalytics;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.RefundRequest;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.SellerDtos.SellerSummary;
import com.duoduo.trade.dto.SellerDtos.SettlementPage;
import com.duoduo.trade.dto.SellerDtos.ShipRequest;
import com.duoduo.trade.service.SellerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卖家中心接口（需登录 + 卖家角色）。
 *
 * <p>路径由网关按 {@code /api/seller/**} 路由到本服务；网关已完成鉴权，
 * 服务端从 {@code X-User-*} 取身份与角色，<b>请求体与路径里都没有 sellerId</b>。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」；准入与归属校验都在
 * {@link SellerService} 里 —— 校验与业务动作必须相邻，否则新增入口时极易漏掉。
 */
@RestController
@RequestMapping("/api/seller")
public class SellerController {

    private final SellerService sellerService;

    public SellerController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    /**
     * 商家端概览：订单状态分布 + 资金三栏。
     *
     * <p>同时充当「我有没有权限进商家端」的探测接口：非卖家会拿到 403，
     * 前端据此引导去申请成为卖家，而不是进了一个到处都是 403 的空页面。
     */
    @GetMapping("/summary")
    public ApiResponse<SellerSummary> summary() {
        return ApiResponse.ok(sellerService.summary());
    }

    /**
     * 商家端多维度统计（图表用）。
     *
     * <p>与 {@code /summary} 分开是刻意的：统计卡比图表重要得多，
     * 图表取数异常不该把卡片一起拖下水。前端两个请求各自降级。
     */
    @GetMapping("/analytics")
    public ApiResponse<SellerAnalytics> analytics() {
        return ApiResponse.ok(sellerService.analytics());
    }

    /** 卖出的订单列表；status 可按状态过滤（PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / REFUNDED / CLOSED） */
    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderView>> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerService.listOrders(status, page, pageSize));
    }

    /** 订单详情（卖家视角） */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderView> orderDetail(@PathVariable String orderNo) {
        return ApiResponse.ok(sellerService.detail(orderNo));
    }

    /**
     * 卖家发货（已付款 → 已发货），物流信息可选。
     *
     * <p>发货后资金<b>仍在托管中</b>，放款要等买家确认收货。
     * 这个接口与运营侧的内部凭据接口共用同一段状态机，区别只在准入方式。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public ApiResponse<OrderView> ship(@PathVariable String orderNo,
                                       @Valid @RequestBody(required = false) ShipRequest request) {
        return ApiResponse.ok(sellerService.ship(orderNo, request));
    }

    /**
     * 卖家售后退款：已付款 / 已发货的订单都可以（前提是卖家已确认收到退货）。
     *
     * <p>已完成的订单退不了 —— 钱已经放款给卖家，
     * 那属于售后追偿 / 平台垫付，是另一条尚未实现的流程。
     */
    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<RefundResult> refund(@PathVariable String orderNo,
                                            @Valid @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(sellerService.refund(orderNo, reason));
    }

    /**
     * 我的资金托管单（对账），附全量汇总。
     *
     * <p>status 可选 CUSTODY（托管中）/ RELEASED（已放款）/ REFUNDED（已退回买家）；
     * 汇总不受该过滤影响，理由见服务层注释。
     */
    @GetMapping("/settlements")
    public ApiResponse<SettlementPage> settlements(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerService.settlements(status, page, pageSize));
    }
}
