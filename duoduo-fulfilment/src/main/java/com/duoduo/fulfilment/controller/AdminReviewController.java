package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.api.StatusCount;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.web.CsvExport;
import com.duoduo.fulfilment.dto.ReviewDtos.AdminReviewView;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewAuditRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewBatchAuditRequest;
import com.duoduo.fulfilment.service.ReviewService;
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
 * 平台管理后台 · 评价管理（H-02）。
 *
 * <p>与公开评价接口分开：可见范围不同（全平台，含待审与已下架）、权限不同（运营准入）、
 * 且后续运营动作（批量下架、导出、申诉复核）不该混进买家/卖家接口。
 *
 * <p>准入由服务层 {@code OperatorGuard.requireOperator()} 把关；
 * 下架 / 恢复是后台写操作，按 E18 纪律加 {@link Audited} 留痕。
 */
@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {

    private final ReviewService reviewService;

    public AdminReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 全平台评价列表。
     *
     * @param status   PENDING / APPROVED / REJECTED；不传 = 全部
     * @param rating   1-5；不传 = 全部星级
     * @param keyword  评价正文 / 追评 / 商品标题 / 订单号（包含匹配）
     */
    @GetMapping
    public ApiResponse<PageResult<AdminReviewView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.listAllForAdmin(status, rating, keyword, page, pageSize));
    }

    /**
     * 评价导出（2026-10-28 · 导入导出第一批）：按状态 / 星级 / 关键词导出 CSV。
     *
     * <p>路径 {@code /export} 是字面量，不会被 {@code /{id}/audit} 之类的行为路径捕获。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) Integer rating,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        java.util.List<AdminReviewView> rows = reviewService.exportForAdmin(status, rating, keyword);
        CsvExport.write(response, "评价管理-" + CsvExport.stamp(), HEADERS,
                rows.stream().map(AdminReviewController::row).toList());
    }

    /**
     * 运营导航角标：未读待审评价数（2026-10-27 · 点击详情即消除）。
     *
     * <p>字面量路径放在 {@code /{id}/audit} 之前：前者是静态路径，后者带动作后缀，
     * 不会互相捕获；按项目惯例仍先声明静态路径。
     */
    @GetMapping("/unread-count")
    public ApiResponse<List<StatusCount>> unreadCount() {
        return ApiResponse.ok(reviewService.adminUnreadCount());
    }

    /**
     * 点开评价详情 = 已读；返回最新未读数（前端用它覆盖本地乐观值）。
     *
     * <p>只写已读集合，不改审核状态 —— 看过不代表已审。
     */
    @PostMapping("/{id}/read")
    public ApiResponse<List<StatusCount>> markRead(@PathVariable Long id) {
        return ApiResponse.ok(reviewService.markReadForAdmin(id));
    }

    /**
     * 人工审核：REJECT = 下架（原因必填），APPROVE = 恢复 / 通过。
     *
     * <p>已处于目标态时幂等成功且不写库；并发状态变化返回 409。
     */
    @PostMapping("/{id}/audit")
    @Audited(action = AuditAction.REVIEW_AUDIT)
    public ApiResponse<AdminReviewView> audit(@PathVariable Long id,
                                              @Valid @RequestBody ReviewAuditRequest request) {
        return ApiResponse.ok(reviewService.audit(id, request));
    }

    /**
     * 批量审核（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条经代理调用 {@link ReviewService#audit} —— 每条各自一个事务，
     * 单条失败只影响该条，且「下架后重算商品评分」的提交后事件不会被整批拖住。
     * 成功条目写运营已读，返回最新未读分桶。
     */
    @PostMapping("/batch-audit")
    @Audited(action = AuditAction.REVIEW_AUDIT)
    public ApiResponse<BatchDtos.Result> batchAudit(@Valid @RequestBody ReviewBatchAuditRequest request) {
        // 准入先判一次：避免批量里每条都抛同一个 403（也避免空 ids 时根本没触发准入）
        OperatorGuard.requireOperator();
        AuditTrail.note("批量审核 " + request.ids().size() + " 条：" + request.action());
        java.util.LinkedHashSet<Long> distinct = new java.util.LinkedHashSet<>();
        for (Long id : request.ids()) {
            if (id == null) {
                throw BizException.validation("ids 不能包含空值");
            }
            distinct.add(id);
        }
        if (distinct.size() > 100) {
            throw BizException.validation("单次最多批量操作 100 条评价");
        }
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(distinct.size());
        int success = 0;
        ReviewAuditRequest single = new ReviewAuditRequest(request.action(), request.reason());
        for (Long id : distinct) {
            try {
                reviewService.audit(id, single);
                success++;
                reviewService.markReadForAdmin(id);
                items.add(new BatchDtos.Item(String.valueOf(id), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(distinct.size(), success, distinct.size() - success,
                items, reviewService.adminUnreadCount()));
    }

    // ---------------- CSV 导出列定义 ----------------

    private static final java.util.List<String> HEADERS = java.util.List.of(
            "评价ID", "订单号", "商品", "评分", "内容", "追评", "用户ID", "昵称", "匿名",
            "审核状态", "审核来源", "审核说明", "审核时间", "提交时间");

    private static java.util.List<String> row(AdminReviewView view) {
        return java.util.List.of(
                String.valueOf(view.id()),
                text(view.orderNo()),
                text(view.productTitle()),
                String.valueOf(view.rating()),
                text(view.content()),
                text(view.appendContent()),
                view.userId() == null ? "" : String.valueOf(view.userId()),
                text(view.nickname()),
                view.anonymous() ? "是" : "否",
                text(view.auditStatus()),
                text(view.auditSource()),
                text(view.auditReason()),
                CsvExport.beijingTime(view.auditedAt()),
                CsvExport.beijingTime(view.createdAt()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
