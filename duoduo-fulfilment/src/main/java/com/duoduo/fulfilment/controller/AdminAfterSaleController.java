package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.api.StatusCount;
import com.duoduo.common.web.CsvExport;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleArbitrateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleBatchArbitrateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.service.AfterSaleService;
import com.duoduo.fulfilment.support.AfterSaleCsv;
import jakarta.servlet.http.HttpServletResponse;
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
 * 平台管理后台（运营）售后仲裁工作台。
 *
 * <p>与买家 / 卖家侧的接口分开：可见范围不同（这里是全平台）、权限不同（运营准入）、
 * 且未来运营侧要加的动作（批量裁定、导出、备注）不该混进用户端接口。
 * 准入由服务层的 {@code OperatorGuard.requireOperator()} 把关。
 */
@RestController
@RequestMapping("/api/admin/aftersales")
public class AdminAfterSaleController {

    private final AfterSaleService afterSaleService;

    public AdminAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    /**
     * 全平台售后列表（按状态过滤，分页）。
     *
     * @param keyword 售后单号 / 订单号（包含匹配）
     */
    @GetMapping
    public ApiResponse<PageResult<AfterSaleView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(afterSaleService.listAll(status, keyword, page, pageSize));
    }

    /** 全平台售后导出（2026-10-28 · 导入导出第一批）：与列表同筛选、同列定义 */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        java.util.List<AfterSaleView> rows = afterSaleService.exportAll(status, keyword);
        CsvExport.write(response, "全平台售后-" + CsvExport.stamp(), AfterSaleCsv.HEADERS,
                rows.stream().map(AfterSaleCsv::row).toList());
    }

    /** 运营导航角标：未读待处理售后数（2026-10-27 · 点击详情即消除） */
    @GetMapping("/unread-count")
    public ApiResponse<List<StatusCount>> unreadCount() {
        return ApiResponse.ok(afterSaleService.adminUnreadCount());
    }

    /** 点开售后详情 = 已读；返回最新未读数（前端用它覆盖本地乐观值） */
    @PostMapping("/{id}/read")
    public ApiResponse<List<StatusCount>> markRead(@PathVariable Long id) {
        return ApiResponse.ok(afterSaleService.markReadForAdmin(id));
    }

    /** 裁定：APPROVE_REFUND（支持退款，会真的执行退款）/ REJECT（驳回），**必须填裁定说明** */
    @PostMapping("/{id}/arbitrate")
    @Audited(action = AuditAction.AFTERSALE_ARBITRATE)
    public ApiResponse<AfterSaleView> arbitrate(@PathVariable Long id,
                                               @Valid @RequestBody AfterSaleArbitrateRequest request) {
        return ApiResponse.ok(afterSaleService.arbitrate(id, request));
    }

    /**
     * 批量裁定（2026-10-27 · 批量操作即已读）：结论与说明对全部单共用。
     *
     * <p>支持退款会逐单调用交易域退款，某一单因「已完成订单已放款」失败只影响该条；
     * 成功条目写运营已读，返回最新未读分桶。
     */
    @PostMapping("/batch-arbitrate")
    @Audited(action = AuditAction.AFTERSALE_ARBITRATE)
    public ApiResponse<BatchDtos.Result> batchArbitrate(@Valid @RequestBody AfterSaleBatchArbitrateRequest request) {
        AuditTrail.note("批量裁定 " + request.ids().size() + " 笔：" + request.result());
        return ApiResponse.ok(afterSaleService.arbitrateBatch(request.ids(),
                new AfterSaleArbitrateRequest(request.result(), request.note())));
    }
}
