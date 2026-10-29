package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.CsvExport;
import com.duoduo.user.dto.CoinDtos.CoinBalanceView;
import com.duoduo.user.dto.CoinDtos.CoinCampaignView;
import com.duoduo.user.dto.CoinDtos.CoinLedgerView;
import com.duoduo.user.dto.CoinDtos.CoinSigninMonthView;
import com.duoduo.user.dto.CoinDtos.CoinSigninResult;
import com.duoduo.user.dto.CoinDtos.CoinTaskReportRequest;
import com.duoduo.user.dto.CoinDtos.CoinTaskView;
import com.duoduo.user.service.CoinCampaignService;
import com.duoduo.user.service.CoinService;
import com.duoduo.user.service.CoinTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多多币买家接口（R-P1 / R-P2 / R-P3）：「我的多多币」页。
 *
 * <p>归属校验只按 {@link UserContext} 的当前用户查 —— 别人的账户和流水
 * 根本不在查询条件里，不存在「猜 id 看别人余额」的入口。
 */
@RestController
@RequestMapping("/api/coins")
public class CoinController {

    private final CoinService coinService;
    private final CoinTaskService coinTaskService;
    private final CoinCampaignService coinCampaignService;

    public CoinController(CoinService coinService,
                          CoinTaskService coinTaskService,
                          CoinCampaignService coinCampaignService) {
        this.coinService = coinService;
        this.coinTaskService = coinTaskService;
        this.coinCampaignService = coinCampaignService;
    }

    /** 我的账户概览：余额 / 累计 / 30 天内到期批次提示 */
    @GetMapping("/me")
    public ApiResponse<CoinBalanceView> me() {
        return ApiResponse.ok(coinService.balanceOf(UserContext.requireUserId()));
    }

    /** 我的流水（分页）：返回 PageResult，前端据此渲染页码与空态 */
    @GetMapping("/ledger")
    public ApiResponse<PageResult<CoinLedgerView>> ledger(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(coinService.ledgerOf(UserContext.requireUserId(), page, pageSize));
    }

    /**
     * 我的多多币流水导出（矩阵 · 买家侧「我的多多币」）：只导本人，最多 5 万条。
     *
     * <p>与列表不同，导出<b>不带 userId 列</b>（自己的文件里没必要重复自己的 id），
     * 方向翻成「收入 / 支出」—— 导出的文件是给人看的，不是给程序读的。
     */
    @GetMapping("/ledger/export")
    public void exportLedger(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        Long userId = UserContext.requireUserId();
        long total = coinService.countLedgerOf(userId);
        if (total > MY_EXPORT_LIMIT) {
            throw BizException.validation("流水共 " + total + " 条，超过单次导出上限 "
                    + MY_EXPORT_LIMIT + " 条；请稍后再试或联系客服");
        }
        List<CoinLedgerView> views = coinService.exportLedgerOf(userId);
        CsvExport.write(response, "我的多多币流水-" + CsvExport.stamp(), MY_LEDGER_HEADERS,
                views.stream().map(view -> List.of(
                        CsvExport.beijingTime(view.createdAt()),
                        directionLabel(view.direction()),
                        String.valueOf(view.amount()),
                        String.valueOf(view.balanceAfter()),
                        text(view.bizType()),
                        text(view.bizNo()),
                        text(view.remark()))).toList());
    }

    /** 买家侧流水导出行数上限 */
    private static final long MY_EXPORT_LIMIT = 50_000L;

    private static final List<String> MY_LEDGER_HEADERS = List.of(
            "时间", "方向", "变动(币)", "变动后余额(币)", "业务类型", "业务单号", "备注");

    /** 方向文案与「我的多多币」页面同一套：导出文件和页面看到的不应该有两种说法 */
    private static String directionLabel(String direction) {
        if (direction == null) return "";
        return switch (direction.toUpperCase()) {
            case "EARN" -> "获得";
            case "SPEND" -> "使用";
            case "REFUND" -> "退回";
            case "ADJUST" -> "调整";
            case "EXPIRE" -> "过期";
            case "CLAWBACK" -> "扣回";
            default -> direction;
        };
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** 每日签到（R-P2）：一天一次，唯一索引兜底；连签 3/7 天当天加发 */
    @PostMapping("/signin")
    public ApiResponse<CoinSigninResult> signin() {
        return ApiResponse.ok(coinService.signin(UserContext.requireUserId()));
    }

    /** 当月签到月历：已签日期 / 连签天数 / 今日是否已签（?ym=2026-10 可查历史月） */
    @GetMapping("/signin/month")
    public ApiResponse<CoinSigninMonthView> signinMonth(
            @RequestParam(required = false) String ym) {
        return ApiResponse.ok(coinService.signinMonth(UserContext.requireUserId(), ym));
    }

    /** 任务中心（R-P3）：日常任务取今天进度，新人任务取一次性进度（未开始也返回） */
    @GetMapping("/tasks")
    public ApiResponse<List<CoinTaskView>> tasks() {
        return ApiResponse.ok(coinTaskService.tasksOf(UserContext.requireUserId()));
    }

    /**
     * 浏览类任务上报（浏览商品 ≥15 秒 / 逛分享圈 ≥10 秒）。
     * 停留时长与任务编码都由服务端校验；同一 bizId 当天重复上报不重复计数。
     */
    @PostMapping("/tasks/report")
    public ApiResponse<CoinTaskView> reportTask(@RequestBody CoinTaskReportRequest request) {
        return ApiResponse.ok(coinTaskService.report(UserContext.requireUserId(), request));
    }

    /** 进行中的节日活动（无活动时 data=null，前端按空态处理） */
    @GetMapping("/campaign/active")
    public ApiResponse<CoinCampaignView> activeCampaign() {
        return ApiResponse.ok(coinCampaignService.activeView());
    }
}
