package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.dto.AdminDtos.BatchForceCloseRequest;
import com.duoduo.trade.dto.AdminDtos.ForceCloseRequest;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.service.OrderService;
import com.duoduo.trade.support.OrderCsv;
import jakarta.servlet.http.HttpServletResponse;
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

    /**
     * 运营全平台订单列表。
     *
     * @param keyword 订单号或商品标题（包含匹配）；{@code orderNo} 为旧参数名，保留兼容
     */
    @GetMapping
    public ApiResponse<PageResult<OrderView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OperatorGuard.requireOperator();
        String kw = (keyword == null || keyword.isBlank()) ? orderNo : keyword;
        return ApiResponse.ok(orderService.adminList(status, kw, page, pageSize));
    }

    /**
     * 全平台订单导出（2026-10-28 · 导入导出第一批；矩阵第 9 项调整上限口径）。
     *
     * <p>运营可见**完整收货信息**（脱敏是给公开页面用的，运营排障需要真值）；
     * 因此这个端点只允许运营角色，且筛选与列表接口同名。
     * 路径是字面量 {@code /export}，不会被 {@code /{orderNo:\d+}} 捕获。
     *
     * <p><b>超过 5 万行直接 409</b>，而不是截断：被截断的 CSV 看起来和完整文件一模一样，
     * 财务按它算会少钱、运营按它复盘会漏单。前端据此提示「请用异步导出」，
     * 异步任务见 {@code POST /api/admin/export-jobs}。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String orderNo,
                       HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        String kw = (keyword == null || keyword.isBlank()) ? orderNo : keyword;
        long total = orderService.adminExportCount(status, kw);
        if (total > SYNC_EXPORT_LIMIT) {
            throw BizException.conflict("导出结果共 " + total + " 行，超过同步导出上限 "
                    + SYNC_EXPORT_LIMIT + " 行；请改用「异步导出」，生成完成后在「导出任务」页下载");
        }
        java.util.List<OrderView> orders = orderService.adminExportList(status, kw);
        CsvExport.write(response, "全平台订单-" + CsvExport.stamp(), OrderCsv.HEADERS,
                orders.stream().map(OrderCsv::row).toList());
    }

    /** 运营按订单号查详情（无归属限制） */
    @GetMapping("/{orderNo:\\d+}")
    public ApiResponse<OrderView> detail(@PathVariable String orderNo) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(orderService.adminDetail(orderNo));
    }

    /** 运营强制关单（仅待付款订单）；已付款/已发货会被状态机拒绝 */
    @PostMapping("/{orderNo}/force-close")
    @Audited(action = AuditAction.ORDER_FORCE_CLOSE)
    public ApiResponse<OrderView> forceClose(@PathVariable String orderNo, @RequestBody ForceCloseRequest request) {
        OperatorGuard.requireOperator();
        // 强制关闭已付款订单会触发退款 —— 属于资金事实变更，因此归入不可删改的 FUNDS 档。
        AuditTrail.target(0L);
        AuditTrail.note("订单号 " + orderNo);
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.forceClose(orderNo, reason));
    }

    /**
     * 批量强制关单（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条调用 {@link OrderService#forceClose}（经代理调用，每条各自独立事务）：
     * 单条失败不影响其它条，例如批量里混了已付款订单，它会被状态机拒绝并带回原因。
     * 运营后台订单没有「未读」角标，counts 恒为空列表。
     */
    @PostMapping("/batch-force-close")
    @Audited(action = AuditAction.ORDER_FORCE_CLOSE)
    public ApiResponse<BatchDtos.Result> batchForceClose(@RequestBody BatchForceCloseRequest request) {
        OperatorGuard.requireOperator();
        java.util.List<String> orderNos = request == null ? null : request.orderNos();
        if (orderNos == null || orderNos.isEmpty()) {
            throw BizException.validation("orderNos 不能为空");
        }
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        for (String orderNo : orderNos) {
            if (orderNo == null || orderNo.isBlank()) {
                throw BizException.validation("orderNos 不能包含空值");
            }
            distinct.add(orderNo.trim());
        }
        if (distinct.size() > 100) {
            throw BizException.validation("单次最多批量操作 100 笔订单");
        }
        AuditTrail.target(0L);
        AuditTrail.note("批量关单 " + distinct.size() + " 笔：" + String.join(",", distinct));
        String reason = request.reason();
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(distinct.size());
        int success = 0;
        for (String orderNo : distinct) {
            try {
                orderService.forceClose(orderNo, reason);
                success++;
                items.add(new BatchDtos.Item(orderNo, true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(orderNo, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(orderNo, false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(distinct.size(), success, distinct.size() - success,
                items, java.util.List.of()));
    }

    // ---------------- CSV 导出列定义 ----------------

    /** 同步导出行数上限；超过走异步任务（矩阵第 9 项） */
    private static final long SYNC_EXPORT_LIMIT = 50_000L;
}
