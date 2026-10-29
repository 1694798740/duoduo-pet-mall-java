package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.user.dto.CoinDtos.CoinAdminSummary;
import com.duoduo.user.dto.CoinDtos.CoinAdjustRequest;
import com.duoduo.user.dto.CoinDtos.CoinCampaignSaveRequest;
import com.duoduo.user.dto.CoinDtos.CoinCampaignStatusRequest;
import com.duoduo.user.dto.CoinDtos.CoinCampaignView;
import com.duoduo.user.dto.CoinDtos.CoinLedgerView;
import com.duoduo.user.dto.CoinDtos.CoinMutationResult;
import com.duoduo.user.dto.CoinDtos.CoinTaskDefAdminView;
import com.duoduo.user.dto.CoinDtos.CoinTaskDefUpdateRequest;
import com.duoduo.user.service.CoinCampaignService;
import com.duoduo.user.service.CoinService;
import com.duoduo.user.service.CoinTaskService;
import com.duoduo.common.web.CsvExport;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理后台 · 多多币（R-P1 三轮）。
 *
 * <p>三个问题一屏可答：<b>平台负债多少（未过期余额合计）、钱怎么流的（全量流水）、
 * 异常账户怎么救（人工调整，必须写原因并留审计）</b>；R-P3 起再加两件事：
 * <b>发币规则怎么配（任务定义）、活动预算烧到哪了（节日活动）</b>。
 * 全部运营动作走 {@link OperatorGuard}；改发钱规则的动作带 {@link Audited} 留痕 ——
 * 币是钱，改账本 / 改发钱规则的动作不记名等于没改。
 */
@RestController
@RequestMapping("/api/admin/coins")
public class AdminCoinController {

    private final CoinService coinService;
    private final CoinTaskService coinTaskService;
    private final CoinCampaignService coinCampaignService;

    public AdminCoinController(CoinService coinService,
                               CoinTaskService coinTaskService,
                               CoinCampaignService coinCampaignService) {
        this.coinService = coinService;
        this.coinTaskService = coinTaskService;
        this.coinCampaignService = coinCampaignService;
    }

    /** 平台汇总：负债（全站余额合计）/ 累计发放 / 累计消耗 / 累计过期 / 30 天内到期 */
    @GetMapping("/summary")
    public ApiResponse<CoinAdminSummary> summary() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(coinService.adminSummary());
    }

    /** 全量流水（按用户 / 方向筛选） */
    @GetMapping("/ledger")
    public ApiResponse<PageResult<CoinLedgerView>> ledger(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(coinService.ledgerForAdmin(userId, direction, page, pageSize));
    }

    /**
     * 多多币流水导出（矩阵第二批）：与列表同一筛选（userId / direction），单次上限 5 万行。
     * 币值本身是整数币，不套「分转元」口径。
     */
    @GetMapping("/ledger/export")
    public void exportLedger(@RequestParam(required = false) Long userId,
                             @RequestParam(required = false) String direction,
                             HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        java.util.List<CoinLedgerView> views = coinService.exportLedgerForAdmin(userId, direction);
        CsvExport.write(response, "多多币流水-" + CsvExport.stamp(), HEADERS,
                views.stream().map(AdminCoinController::row).toList());
    }

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "流水ID", "用户ID", "方向", "变动(币)", "变动后余额(币)", "业务类型", "业务单号", "备注", "时间");

    private static java.util.List<String> row(CoinLedgerView view) {
        return java.util.List.of(
                text(view.id()),
                view.userId() == null ? "" : String.valueOf(view.userId()),
                text(view.direction()),
                String.valueOf(view.amount()),
                String.valueOf(view.balanceAfter()),
                text(view.bizType()),
                text(view.bizNo()),
                text(view.remark()),
                CsvExport.beijingTime(view.createdAt()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 人工调整（amount 正 = 加币，负 = 调减，可调成负余额）。必须写原因，走审计留痕。
     */
    @PostMapping("/adjust")
    @Audited(action = AuditAction.COIN_ADMIN_ADJUST)
    public ApiResponse<CoinMutationResult> adjust(@RequestBody CoinAdjustRequest request) {
        OperatorGuard.requireOperator();
        if (request.userId() == null || request.amount() == null) {
            return ApiResponse.fail("VALIDATION", "userId 与 amount 必填");
        }
        AuditTrail.target(request.userId(), "COIN_ACCOUNT");
        AuditTrail.note("调整 " + request.amount() + " 币：" + request.reason());
        CoinMutationResult result = coinService.adjust(request.userId(), request.amount(), request.reason());
        AuditTrail.change(null, String.valueOf(result.balance()));
        return ApiResponse.ok(result);
    }

    // ==================== 任务定义（R-P3） ====================

    /** 任务定义全量（含已停用），运营配置页用 */
    @GetMapping("/tasks")
    public ApiResponse<List<CoinTaskDefAdminView>> tasks() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(coinTaskService.defsForAdmin());
    }

    /** 更新任务：启停 / 奖励币数 / 每日上限（null 字段表示不改） */
    @PutMapping("/tasks/{code}")
    @Audited(action = AuditAction.COIN_TASK_UPDATE)
    public ApiResponse<CoinTaskDefAdminView> updateTask(@PathVariable String code,
                                                       @RequestBody CoinTaskDefUpdateRequest request) {
        OperatorGuard.requireOperator();
        if (request == null) {
            return ApiResponse.fail("VALIDATION", "任务更新内容不能为空");
        }
        AuditTrail.note("更新任务 " + code + "：enabled=" + request.enabled()
                + " rewardCoins=" + request.rewardCoins() + " dailyCap=" + request.dailyCap());
        return ApiResponse.ok(coinTaskService.updateDef(code, request));
    }

    // ==================== 节日活动（R-P3） ====================

    /** 活动全量列表（含已停用 / 已过期），带预算进度 */
    @GetMapping("/campaigns")
    public ApiResponse<List<CoinCampaignView>> campaigns() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(coinCampaignService.listForAdmin());
    }

    /** 新建活动（默认 ENABLED；预算 / 每人上限填 0 表示不限） */
    @PostMapping("/campaigns")
    @Audited(action = AuditAction.COIN_CAMPAIGN_UPDATE)
    public ApiResponse<CoinCampaignView> createCampaign(@RequestBody CoinCampaignSaveRequest request) {
        OperatorGuard.requireOperator();
        if (request == null) {
            return ApiResponse.fail("VALIDATION", "活动参数不能为空");
        }
        AuditTrail.note("新建活动 " + request.code() + "：" + request.startAt() + " ~ " + request.endAt());
        return ApiResponse.ok(coinCampaignService.create(request));
    }

    /** 编辑活动（时间 / 倍率 / 预算 / 每人上限；编码可一并修正） */
    @PutMapping("/campaigns/{id}")
    @Audited(action = AuditAction.COIN_CAMPAIGN_UPDATE)
    public ApiResponse<CoinCampaignView> updateCampaign(@PathVariable Long id,
                                                       @RequestBody CoinCampaignSaveRequest request) {
        OperatorGuard.requireOperator();
        if (request == null) {
            return ApiResponse.fail("VALIDATION", "活动参数不能为空");
        }
        AuditTrail.target(id, "COIN_CAMPAIGN");
        AuditTrail.note("编辑活动：" + request.code() + "：" + request.startAt() + " ~ " + request.endAt());
        return ApiResponse.ok(coinCampaignService.update(id, request));
    }

    /** 启停活动：只改运营开关，不删历史（已发出的加成留在流水与每人累计里） */
    @PostMapping("/campaigns/{id}/status")
    @Audited(action = AuditAction.COIN_CAMPAIGN_UPDATE)
    public ApiResponse<CoinCampaignView> changeCampaignStatus(@PathVariable Long id,
                                                             @RequestBody CoinCampaignStatusRequest request) {
        OperatorGuard.requireOperator();
        if (request == null || request.enabled() == null) {
            return ApiResponse.fail("VALIDATION", "enabled 必填");
        }
        AuditTrail.target(id, "COIN_CAMPAIGN");
        AuditTrail.note("活动启停：enabled=" + request.enabled());
        return ApiResponse.ok(coinCampaignService.changeStatus(id, request.enabled()));
    }
}
