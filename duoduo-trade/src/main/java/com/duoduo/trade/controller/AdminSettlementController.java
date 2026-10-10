package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.trade.dto.AdminDtos.AbnormalSettlement;
import com.duoduo.trade.dto.AdminDtos.BackfillResult;
import com.duoduo.trade.dto.AdminDtos.CompensateRequest;
import com.duoduo.trade.dto.AdminDtos.SellerDebtView;
import com.duoduo.trade.dto.AdminDtos.SettlementSummary;
import com.duoduo.trade.service.AdminOpsService;
import com.duoduo.trade.service.SellerDebtService;
import com.duoduo.trade.service.SettlementBackfillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final SellerDebtService sellerDebtService;

    public AdminSettlementController(AdminOpsService adminOpsService,
                                     SettlementBackfillService settlementBackfillService,
                                     SellerDebtService sellerDebtService) {
        this.adminOpsService = adminOpsService;
        this.settlementBackfillService = settlementBackfillService;
        this.sellerDebtService = sellerDebtService;
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
    @Audited(action = AuditAction.SETTLEMENT_BACKFILL)
    public ApiResponse<BackfillResult> backfill(@RequestParam(required = false) Boolean dryRun) {
        return ApiResponse.ok(settlementBackfillService.backfill(Boolean.TRUE.equals(dryRun)));
    }

    // ---------- 平台垫付与卖家追偿（第五十八轮 · B-01） ----------

    /**
     * 平台垫付退款：订单已完成（钱已放款给卖家）但仍需退给买家时，由平台先垫付，
     * 并在 {@code t_seller_debt} 记一笔卖家欠款。
     *
     * <p>为什么单开一个端点而不是放宽 {@code refundByOperator} 的状态限制：
     * 垫付与退款是**两笔不同的钱** —— 退款动的是「还在平台上的买家的钱」，
     * 垫付动的是「平台的钱」并额外产生一笔债。混在一个动作里，资金轨迹就说不清了。
     */
    @PostMapping("/compensate")
    @Audited(action = AuditAction.SETTLEMENT_COMPENSATE)
    public ApiResponse<SellerDebtView> compensate(@Valid @RequestBody CompensateRequest request) {
        AuditTrail.note("订单号 " + request.orderNo());
        return ApiResponse.ok(sellerDebtService.compensate(request.orderNo(), request.reason()));
    }

    /** 欠款列表（可按 sellerId / status 过滤） */
    @GetMapping("/debts")
    public ApiResponse<List<SellerDebtView>> debts(@RequestParam(required = false) Long sellerId,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.ok(sellerDebtService.list(sellerId, status));
    }

    /** 收回欠款（卖家已还） */
    @PostMapping("/debts/{id}/settle")
    @Audited(action = AuditAction.SETTLEMENT_DEBT_SETTLE)
    public ApiResponse<SellerDebtView> settleDebt(@PathVariable Long id) {
        AuditTrail.target(id, "SELLER_DEBT");
        return ApiResponse.ok(sellerDebtService.settle(id));
    }

    /** 核销欠款（确认收不回，走坏账） */
    @PostMapping("/debts/{id}/write-off")
    @Audited(action = AuditAction.SETTLEMENT_DEBT_WRITE_OFF)
    public ApiResponse<SellerDebtView> writeOffDebt(@PathVariable Long id) {
        AuditTrail.target(id, "SELLER_DEBT");
        return ApiResponse.ok(sellerDebtService.writeOff(id));
    }
}
