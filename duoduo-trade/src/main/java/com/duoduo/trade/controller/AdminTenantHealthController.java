package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthPanel;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthRow;
import com.duoduo.trade.service.TenantHealthService;
import com.duoduo.trade.support.TenantHealthJudgement;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台管理后台 · 租户健康度面板（E24）。
 *
 * <h2>权限档位：与「全平台资金汇总」同一档（{@code OperatorGuard.requireOperator()}）</h2>
 * 这是一份**巡检视图** —— 它回答"哪家店需要人管"，是运营（含非管理员）日常要看的。
 * 与 E22 的租户钱包 / 账单刻意分开：那两个回答"某个租户能拿走多少钱"，
 * 暴露的是平台抽佣总额与卖家欠款规模，因此只给管理员（{@code finance:settlement}）。
 * 本面板里出现的金额是**异常金额**（未收回欠款 / 超期托管 / 缺托管单），
 * 语义是"出问题的钱有多少"，不是平台的收入口径，因此不构成同级别的财务视图。
 *
 * <h2>为什么只有两个端点、且都是 GET</h2>
 * 面板是只读视图，没有任何写动作 —— 需要对某一行动手时，
 * 应该去它对应的处置页（欠款在 {@code /api/admin/settlements/debts}、
 * 挂账在 {@code /api/admin/settlements/abnormal}、售后在 {@code /api/admin/aftersales}）。
 * 在面板上直接给"处置"按钮，等于把几个域的状态机塞进一个页面，
 * 而每个动作的权限档位并不相同（欠款核销只给管理员）。
 */
@RestController
@RequestMapping("/api/admin/tenant-health")
public class AdminTenantHealthController {

    private final TenantHealthService tenantHealthService;

    public AdminTenantHealthController(TenantHealthService tenantHealthService) {
        this.tenantHealthService = tenantHealthService;
    }

    /**
     * 租户健康度面板。
     *
     * @param days  窗口天数，默认 30、上限 30（上限由接口指标的键存活期决定，见服务注释）
     * @param limit 返回的租户行数，默认 50、上限 200；被截断时响应里 {@code truncated=true}
     */
    @GetMapping
    public ApiResponse<TenantHealthPanel> panel(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(tenantHealthService.panel(days, limit));
    }

    /** 租户健康导出列（四维：交易 / 售后 / 结算异常 / 接口错误率） */
    private static final List<String> EXPORT_HEADERS = List.of(
            "租户ID", "店铺", "档位", "下单数", "成交单数", "成交额(元)", "售后立案数",
            "已退款", "未结案", "卖家败诉", "售后率", "未收回欠款笔数", "未收回金额(元)",
            "超期托管笔数", "超期托管金额(元)", "缺托管单数", "结算异常合计",
            "接口调用数", "5xx", "4xx", "接口错误率");

    /**
     * 导出当前窗口的租户健康 CSV（2026-10-28 · 矩阵 P3）。
     *
     * <p>与面板同一次聚合（最多 {@link TenantHealthJudgement#MAX_LIMIT} 家），
     * 比例字段原样导出；无样本时为空白，不写成 0。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Integer days,
                       HttpServletResponse response) throws java.io.IOException {
        TenantHealthPanel panel = tenantHealthService.panel(days, TenantHealthJudgement.MAX_LIMIT);
        List<List<String>> rows = panel.tenants().stream().map(AdminTenantHealthController::row).toList();
        CsvExport.write(response, "租户健康-" + panel.days() + "天-" + CsvExport.stamp(), EXPORT_HEADERS, rows);
    }

    private static List<String> row(TenantHealthRow row) {
        return List.of(
                String.valueOf(row.tenantId()),
                row.tenantName() == null ? "" : row.tenantName(),
                row.level(),
                String.valueOf(row.orderCount()),
                String.valueOf(row.paidCount()),
                CsvExport.yuan(row.gmv()),
                String.valueOf(row.afterSaleCount()),
                String.valueOf(row.afterSaleRefundedCount()),
                String.valueOf(row.afterSaleOpenCount()),
                String.valueOf(row.afterSaleAdverseCount()),
                row.afterSaleRate() == null ? "" : row.afterSaleRate(),
                String.valueOf(row.debtOutstandingCount()),
                CsvExport.yuan(row.debtOutstandingAmount()),
                String.valueOf(row.staleCustodyCount()),
                CsvExport.yuan(row.staleCustodyAmount()),
                String.valueOf(row.missingSettlementCount()),
                String.valueOf(row.settlementAbnormalCount()),
                String.valueOf(row.apiTotal()),
                String.valueOf(row.apiServerError()),
                String.valueOf(row.apiClientError()),
                row.apiErrorRate() == null ? "" : row.apiErrorRate());
    }

    /**
     * 运行时口径（阈值 / 窗口上限 / 判级档位清单）。
     *
     * <p>存在的理由与 S3 的 {@code /api/admin/recon/rules} 完全一样：
     * **把"阈值是多少、一共几个档位"变成一个服务能回答的问题** ——
     * 否则冒烟脚本只能去把数字抄一遍，而"源码里写着"与"服务里跑着的"是两回事
     * （本项目已经踩过一次「改了代码、测试全绿、容器里跑的是旧版本」）。
     * 抄数字的断言在阈值被调整后，要么假红、要么悄然失效。
     */
    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> limits() {
        return ApiResponse.ok(tenantHealthService.limits());
    }
}
