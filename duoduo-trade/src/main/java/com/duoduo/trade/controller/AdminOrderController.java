package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.AdminDtos.ForceCloseRequest;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台（M10）订单干预。
 *
 * <p>只开放「强制关单」：针对待付款异常单（恶意占库存、超时未付却未触发补偿等）。
 * 已付款 / 已发货订单的干预属于资金动作，一律走已有的退款 / 售后路径，不在这里开新口子 ——
 * 状态机与资金账本必须只走合法流转。
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 运营全平台订单列表（按状态 / 订单号前缀过滤） */
    @GetMapping
    public ApiResponse<PageResult<OrderView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(orderService.adminList(status, orderNo, page, pageSize));
    }

    /** 运营按订单号查详情（无归属限制） */
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderView> detail(@PathVariable String orderNo) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(orderService.adminDetail(orderNo));
    }

    /** 运营强制关单（仅待付款订单）；已付款/已发货会被状态机拒绝 */
    @PostMapping("/{orderNo}/force-close")
    public ApiResponse<OrderView> forceClose(@PathVariable String orderNo, @RequestBody ForceCloseRequest request) {
        OperatorGuard.requireOperator();
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.forceClose(orderNo, reason));
    }
}
