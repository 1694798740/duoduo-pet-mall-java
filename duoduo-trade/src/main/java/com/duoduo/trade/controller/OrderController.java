package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.trade.dto.OrderDtos.CancelRequest;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.PayRequest;
import com.duoduo.trade.dto.OrderDtos.PayResult;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderResult;
import com.duoduo.trade.dto.OrderDtos.SweepResult;
import com.duoduo.trade.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（需登录）。
 *
 * <p>路径由网关按 {@code /api/orders/**} 路由到本服务；网关已完成鉴权，
 * 服务端从 {@code X-User-*} 取身份，<b>请求体与路径里都没有 userId</b>。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 提交订单：把购物车中**已勾选**的商品下单。
     *
     * <p>必须带 {@code idempotentKey}：同一用户用同一个键重复提交只会产生一个订单
     * （网络重试、用户连点两次都靠它兜住）。命中幂等键时返回首次的订单，
     * 响应里的 {@code idempotentReplay=true} 可用于区分。
     */
    @PostMapping
    public ApiResponse<PlaceOrderResult> place(@Valid @RequestBody PlaceOrderRequest request) {
        return ApiResponse.ok(orderService.placeOrder(request));
    }

    /** 我的订单列表；status 可按状态过滤（PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CLOSED） */
    @GetMapping
    public ApiResponse<PageResult<OrderView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(orderService.listMine(status, page, pageSize));
    }

    /** 订单详情（含明细快照） */
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderView> detail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(orderNo));
    }

    /**
     * 取消订单（仅待付款可取消），并释放已冻结的库存。
     *
     * <p>取消原因可选，请求体可为空 —— 用 {@code required=false} 允许前端不带 body 直接调用。
     */
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable String orderNo,
                                         @Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.cancel(orderNo, reason));
    }

    /**
     * 支付订单（本阶段为模拟渠道，即时成功）。
     *
     * <p>必须带 {@code idempotentKey}：同一渠道同一幂等键只会入账一次，
     * 重复提交返回首次结果（{@code idempotentReplay=true}）。
     */
    @PostMapping("/{orderNo}/pay")
    public ApiResponse<PayResult> pay(@PathVariable String orderNo,
                                      @Valid @RequestBody PayRequest request) {
        return ApiResponse.ok(orderService.pay(orderNo, request));
    }

    /**
     * 发货（已付款 → 已发货）。
     *
     * <p><b>内部接口</b>：由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}，
     * 调用方是卖家侧 / 运营侧系统。卖家账号体系在卖家中心（M5-9）落地后，
     * 这里会改为校验「调用者是否为该订单的卖家」。
     */
    @PostMapping("/{orderNo}/ship")
    public ApiResponse<OrderView> ship(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.ship(orderNo));
    }

    /** 确认收货（已发货 → 已完成） */
    @PostMapping("/{orderNo}/confirm")
    public ApiResponse<OrderView> confirmReceipt(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.confirmReceipt(orderNo));
    }

    /**
     * 手动触发一次超时关单补偿调度（<b>内部接口</b>，同样需要内部凭据）。
     *
     * <p>定时任务每 {@code duoduo.trade.sweep-interval-ms} 自动跑一次；
     * 这个入口是给运维排障与自动化测试用的 —— 否则「等 30 分钟」既不可测也不好排查。
     */
    @PostMapping("/internal/sweep")
    public ApiResponse<SweepResult> sweep() {
        return ApiResponse.ok(orderService.sweepExpiredOrders());
    }
}
