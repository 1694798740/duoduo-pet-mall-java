package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.OrderDtos.PaymentRefundView;
import com.duoduo.trade.service.PaymentRefundService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台 · 重复收款自动退款台账（B-04）。
 *
 * <p>只读。自动退款是系统必达的补偿动作，运营在这里回答三个问题：
 * <b>多收了哪些钱、退出去没有、没退出去的原因是什么</b>。
 * 失败的台账会保持 INIT 由补偿调度自动重试，无需人工干预；
 * 只有 {@code fail_reason} 长期不变（如渠道配置缺失）才需要人来介入。
 */
@RestController
@RequestMapping("/api/admin/payments")
public class AdminPaymentRefundController {

    private final PaymentRefundService paymentRefundService;

    public AdminPaymentRefundController(PaymentRefundService paymentRefundService) {
        this.paymentRefundService = paymentRefundService;
    }

    /** 运营查询退款台账：状态 / 支付单号 / 订单号筛选，分页 */
    @GetMapping("/refunds")
    public ApiResponse<PageResult<PaymentRefundView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payNo,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(paymentRefundService.listForAdmin(status, payNo, orderNo, page, pageSize));
    }
}
