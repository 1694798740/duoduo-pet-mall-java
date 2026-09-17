package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.AdminDtos.AbnormalSettlement;
import com.duoduo.trade.dto.AdminDtos.SettlementSummary;
import com.duoduo.trade.service.AdminOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理后台（M10）资金对账。
 *
 * <p>与商家端对账（{@code /api/seller/settlements}）刻意分开：商家端回答「我这个卖家有多少钱」，
 * 这里回答「整个平台的钱对不对得上」——全量托管汇总 + 异常挂账排查。
 * 鉴权在 {@link AdminOpsService} 各方法内的 {@code OperatorGuard.requireOperator()}。
 */
@RestController
@RequestMapping("/api/admin/settlements")
public class AdminSettlementController {

    private final AdminOpsService adminOpsService;

    public AdminSettlementController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    /** 全平台托管资金汇总（托管中 / 已放款 / 已退回） */
    @GetMapping("/summary")
    public ApiResponse<SettlementSummary> summary() {
        return ApiResponse.ok(adminOpsService.settlementSummary());
    }

    /** 异常挂账：已付款却缺托管单的订单 */
    @GetMapping("/abnormal")
    public ApiResponse<List<AbnormalSettlement>> abnormal() {
        return ApiResponse.ok(adminOpsService.abnormalSettlements());
    }
}
