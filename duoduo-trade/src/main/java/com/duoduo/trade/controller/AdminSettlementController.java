package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.AdminDtos.AbnormalSettlement;
import com.duoduo.trade.dto.AdminDtos.BackfillResult;
import com.duoduo.trade.dto.AdminDtos.SettlementSummary;
import com.duoduo.trade.service.AdminOpsService;
import com.duoduo.trade.service.SettlementBackfillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SettlementBackfillService settlementBackfillService;

    public AdminSettlementController(AdminOpsService adminOpsService,
                                     SettlementBackfillService settlementBackfillService) {
        this.adminOpsService = adminOpsService;
        this.settlementBackfillService = settlementBackfillService;
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

    /**
     * 历史订单托管单回补（A1 / I-14）。
     *
     * <p>参数走查询串而不是请求体：{@code POST .../backfill?dryRun=true} 只返回计划、一行都不写库；
     * 不带参数即执行。首轮自测暴露过真问题 —— 用 {@code @RequestBody} 时，
     * 「无请求体 + 客户端默认 Content-Type」会被判成 {@code 415}，
     * 运营用 curl 直接调这个运维接口就会踩到；查询串没有这个歧义，
     * 也与本项目既有的布尔开关写法（{@code /categories/{id}/status?active=false}）一致。
     */
    @PostMapping("/backfill")
    public ApiResponse<BackfillResult> backfill(@RequestParam(required = false) Boolean dryRun) {
        return ApiResponse.ok(settlementBackfillService.backfill(Boolean.TRUE.equals(dryRun)));
    }
}
