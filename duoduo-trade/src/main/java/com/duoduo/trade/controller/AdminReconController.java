package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.ReconDtos.ReconDiffView;
import com.duoduo.trade.dto.ReconDtos.ReconRuleView;
import com.duoduo.trade.dto.ReconDtos.ReconRunView;
import com.duoduo.trade.dto.ReconDtos.TriggerRequest;
import com.duoduo.trade.dto.ReconDtos.TriggerResult;
import com.duoduo.trade.entity.ReconRunEntity;
import com.duoduo.trade.service.ReconService;
import com.duoduo.trade.support.ReconScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 平台管理后台 · 钱与库存对账（S3）。
 *
 * <p><b>权限档位：与「全平台资金汇总」同一档（{@code OperatorGuard.requireOperator()}）。</b>
 * 理由：这是一份**巡检视图** —— 它回答"账对不对得上"，是运营日常要看的。
 * 与 E22 的租户钱包 / 账单刻意分开：那两个回答"某个租户能拿走多少钱"，
 * 暴露的是平台抽佣总额与卖家欠款规模，因此只给管理员。
 * 对账差异明细里出现的是订单号与金额不等（买家实付），不含佣金口径，
 * 不构成同级别的财务视图。
 *
 * <p><b>手动触发也走留痕</b>（{@link AuditAction#RECON_RUN_MANUAL}）：
 * 它对业务数据完全只读，但会**覆盖**当日已落库的结论（同日同范围只保留一轮）。
 * 「昨天报表上那批差异怎么没了」这个问题，唯一能回答它的就是这条审计记录。
 */
@RestController
@RequestMapping("/api/admin/recon")
public class AdminReconController {

    private static final int DEFAULT_RUN_LIMIT = 30;
    private static final int DEFAULT_DIFF_LIMIT = 200;

    private final ReconService reconService;

    public AdminReconController(ReconService reconService) {
        this.reconService = reconService;
    }

    /** 最近几轮对账（新在上）：一行一轮，status 一眼看出"干净 / 有差异 / 没跑成" */
    @GetMapping("/runs")
    public ApiResponse<List<ReconRunView>> runs(@RequestParam(required = false) Integer limit) {
        OperatorGuard.requireOperator();
        int n = limit == null ? DEFAULT_RUN_LIMIT : limit;
        return ApiResponse.ok(reconService.recent(n).stream().map(ReconRunView::of).toList());
    }

    /** 某一轮的差异明细（HIGH 在前） */
    @GetMapping("/runs/{runId}/diffs")
    public ApiResponse<List<ReconDiffView>> diffs(@PathVariable Long runId,
                                                  @RequestParam(required = false) Integer limit) {
        OperatorGuard.requireOperator();
        // 轮次不存在时明确报 404：返回空列表会让"这一轮没有差异"与"这一轮不存在"混在一起
        if (reconService.findRun(runId) == null) {
            throw BizException.notFound("对账轮次不存在：" + runId);
        }
        int n = limit == null ? DEFAULT_DIFF_LIMIT : limit;
        return ApiResponse.ok(reconService.diffsOf(runId, n).stream().map(ReconDiffView::of).toList());
    }

    /**
     * 规则清单（运行时口径）。
     *
     * <p>把「一共声明了几条规则」变成一个**服务能回答的问题** —— 否则脚本只能去读源码数枚举，
     * 而"源码里写着"与"服务里跑着的"是两回事（打包没重跑时它们会不一致，
     * 本项目已经踩过一次「改了代码、测试全绿、容器里跑的是旧版本」）。
     */
    @GetMapping("/rules")
    public ApiResponse<List<ReconRuleView>> rules(@RequestParam(required = false) String scope) {
        OperatorGuard.requireOperator();
        List<com.duoduo.trade.support.ReconRule> rules;
        if (scope == null || scope.isBlank()) {
            rules = List.copyOf(com.duoduo.trade.support.ReconRule.ALL);
        } else {
            ReconScope parsed = ReconScope.of(scope);
            if (parsed == null) {
                throw BizException.validation("对账范围只能是 MONEY 或 STOCK");
            }
            rules = com.duoduo.trade.support.ReconRule.ofScope(parsed);
        }
        return ApiResponse.ok(rules.stream().map(ReconRuleView::of).toList());
    }

    /**
     * 手动跑一轮（验收与应急入口）。
     *
     * <p>不传 {@code bizDate} = 今天（UTC）；传了就按指定业务日重跑并**覆盖**该日结论。
     * 允许指定历史日期是刻意的：「昨天那批差异到底有没有」只能靠重跑昨天回答，
     * 不能靠"今天跑得干净"推断。
     *
     * <p>不传 {@code scope} 时两个范围都跑（钱 + 库存），返回其中**结论最严重**的那一轮
     * （FAILED &gt; DIRTY &gt; CLEAN）—— 手动触发通常是为了确认"现在到底有没有问题"，
     * 返回一个"钱是干净的"而把"库存没跑成"藏起来，正好违背这个目的。
     */
    @PostMapping("/runs")
    @Audited(action = AuditAction.RECON_RUN_MANUAL)
    public ApiResponse<TriggerResult> trigger(@RequestBody(required = false) TriggerRequest request) {
        OperatorGuard.requireOperator();

        String scopeRaw = request == null ? null : request.scope();
        LocalDate day;
        try {
            day = com.duoduo.trade.dto.ReconDtos.parseBizDate(request == null ? null : request.bizDate());
        } catch (DateTimeParseException e) {
            throw BizException.validation("业务日格式应为 yyyy-MM-dd（UTC），例如 2026-10-11");
        }

        List<ReconScope> scopes;
        if (scopeRaw == null || scopeRaw.isBlank()) {
            scopes = List.of(ReconScope.values());
        } else {
            ReconScope scope = ReconScope.of(scopeRaw);
            if (scope == null) {
                throw BizException.validation("对账范围只能是 MONEY 或 STOCK");
            }
            scopes = List.of(scope);
        }

        AuditTrail.note("对账 " + day + " " + scopes);

        ReconRunEntity worst = null;
        for (ReconScope scope : scopes) {
            ReconRunEntity run = reconService.run(scope, day);
            if (worst == null || severity(run) > severity(worst)) {
                worst = run;
            }
        }
        return ApiResponse.ok(TriggerResult.of(worst));
    }

    /** FAILED > DIRTY > CLEAN（越靠前越需要人看） */
    private static int severity(ReconRunEntity run) {
        if (run == null) {
            return -1;
        }
        return switch (String.valueOf(run.getStatus())) {
            case "FAILED" -> 2;
            case "DIRTY" -> 1;
            default -> 0;
        };
    }
}
