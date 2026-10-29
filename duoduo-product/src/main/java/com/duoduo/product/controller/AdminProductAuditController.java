package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.CsvExport;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditItem;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditRequest;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditView;
import com.duoduo.product.dto.ProductDtos.AdminProductBatchAuditRequest;
import com.duoduo.product.dto.SpuAuditDtos.SpuAuditLogView;
import com.duoduo.product.dto.SpuAuditDtos.SpuChangeHistoryItem;
import com.duoduo.product.service.SellerProductService;
import com.duoduo.product.service.SpuAuditHistoryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营侧 · 第三方商品上架审核台（E14，平台管理后台）。
 *
 * <p>只暴露<b>第三方</b>商品：平台自营商品免审（{@code auditStatus} 恒 APPROVED），
 * 混进审核队列只会让运营在「与自己无关的商品」里翻找真正的待办。
 *
 * <p>准入由服务层 {@code OperatorGuard.requireOperator()} 把关 ——
 * <b>不靠网关</b>：网关只做登录与入口收窄（{@code /api/admin/**} 要求 admin 入口），
 * 而「登录了 / 从后台站点来的」不等于「有运营权限」。
 * 这一层是真正的权限边界，网关那层是纵深防御。
 *
 * <p>与闲置审核台（{@code AdminListingController}）同一形状、同一套状态口径
 * （PENDING / APPROVED / REJECTED + 驳回必填原因），运营在两处不需要学两套规则。
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductAuditController {

    private final SellerProductService sellerProductService;
    private final SpuAuditHistoryService auditHistoryService;

    public AdminProductAuditController(SellerProductService sellerProductService,
                                       SpuAuditHistoryService auditHistoryService) {
        this.sellerProductService = sellerProductService;
        this.auditHistoryService = auditHistoryService;
    }

    /** 审核列表：按审核状态过滤（不传＝全部），可按店铺归属筛，可按商品标题搜 */
    @GetMapping
    public ApiResponse<PageResult<AdminProductAuditView>> list(
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerProductService.listForAudit(auditStatus, sellerId, keyword, page, pageSize));
    }

    /**
     * 商品审核台导出（矩阵第二批）。
     *
     * <p>与列表接口同名同义：auditStatus / sellerId / keyword；单次上限 5 万行。
     * 路径是字面量 {@code /export}，不会被 {@code /{spuId}} 捕获。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String auditStatus,
                       @RequestParam(required = false) Long sellerId,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        List<AdminProductAuditView> views = sellerProductService.exportForAudit(auditStatus, sellerId, keyword);
        CsvExport.write(response, "商品审核-" + CsvExport.stamp(), AUDIT_HEADERS,
                views.stream().map(AdminProductAuditController::auditRow).toList());
    }

    /** 审核详情（含全部背审材料：详情正文、全部图片、类目 / 品牌名、SKU 明细） */
    @GetMapping("/{spuId}")
    public ApiResponse<AdminProductAuditView> detail(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.auditDetail(spuId));
    }

    /** 审核历史（A-08）：最新在前；limit 缺省 50、上限 100 */
    @GetMapping("/{spuId}/history")
    public ApiResponse<List<SpuAuditLogView>> history(@PathVariable Long spuId,
                                                      @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(auditHistoryService.history(spuId, limit));
    }

    /**
     * H-03 · 跨商品变更历史：按商品 / 标题关键词 / 事件 / 店铺过滤，分页返回改前改后。
     *
     * <p>路径是字面量而不是 {@code /{spuId}}：本控制器已有 {@code GET /{spuId}}，
     * Spring 会优先匹配更具体的字面量路径，不会把 {@code change-history} 当成商品 id。
     */
    @GetMapping("/change-history")
    public ApiResponse<PageResult<SpuChangeHistoryItem>> changeHistory(
            @RequestParam(required = false) String spuId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(auditHistoryService.changeHistory(
                spuId, keyword, event, sellerId, page, pageSize));
    }

    /** 变更历史导出（矩阵第二批）：与列表同一筛选，单次上限 5 万行 */
    @GetMapping("/change-history/export")
    public void exportChangeHistory(@RequestParam(required = false) String spuId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String event,
                                    @RequestParam(required = false) Long sellerId,
                                    HttpServletResponse response) throws java.io.IOException {
        List<SpuChangeHistoryItem> items = auditHistoryService.exportChangeHistory(spuId, keyword, event, sellerId);
        CsvExport.write(response, "商品变更历史-" + CsvExport.stamp(), CHANGE_HEADERS,
                items.stream().map(AdminProductAuditController::changeRow).toList());
    }

    /** 审核结论：{@code {"action":"APPROVE"|"REJECT","reason":"...","version":0}}（驳回时 reason 必填；version 必带） */
    @PostMapping("/{spuId}/audit")
    @Audited(action = AuditAction.PRODUCT_AUDIT)
    public ApiResponse<AdminProductAuditView> audit(@PathVariable Long spuId,
                                                    @RequestBody AdminProductAuditRequest request) {
        return ApiResponse.ok(sellerProductService.audit(spuId,
                request == null ? null : request.action(),
                request == null ? null : request.reason(),
                request == null ? null : request.version()));
    }

    /**
     * 批量审核（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条经代理调用 {@link SellerProductService#audit}：商品审核是事务方法，
     * 且带乐观锁（version），单条版本冲突只让该条失败，其余继续。
     * 运营商品审核角标是「待审数」（不是已读集合），审完自然减少，故 counts 为空。
     */
    @PostMapping("/batch-audit")
    @Audited(action = AuditAction.PRODUCT_AUDIT)
    public ApiResponse<BatchDtos.Result> batchAudit(@RequestBody AdminProductBatchAuditRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw BizException.validation("items 不能为空");
        }
        if (request.items().size() > 100) {
            throw BizException.validation("单次最多批量审核 100 件商品");
        }
        AuditTrail.note("批量审核 " + request.items().size() + " 件：" + request.action());
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        java.util.List<BatchDtos.Item> items = new java.util.ArrayList<>(request.items().size());
        int success = 0;
        for (AdminProductAuditItem item : request.items()) {
            Long spuId = item == null ? null : item.spuId();
            if (spuId == null || !seen.add(spuId)) {
                items.add(new BatchDtos.Item(spuId == null ? "" : String.valueOf(spuId), false,
                        "VALIDATION", spuId == null ? "spuId 不能为空" : "重复的商品 id"));
                continue;
            }
            try {
                sellerProductService.audit(spuId, request.action(), request.reason(), item.version());
                success++;
                items.add(new BatchDtos.Item(String.valueOf(spuId), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(spuId), false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                items.add(new BatchDtos.Item(String.valueOf(spuId), false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return ApiResponse.ok(new BatchDtos.Result(request.items().size(), success,
                request.items().size() - success, items, java.util.List.of()));
    }

    /** 回收站清理结果：{@code purged} = 本轮物理删除的商品数 */
    public record RecyclePurgeResult(int purged) {
    }

    /**
     * 立即清理一次回收站（A-09）。
     *
     * <p>与每日定时任务走同一条服务路径，只是多一道运营权限校验；
     * 存在的意义是验收与事故排查需要「现在就跑一轮看效果」，而不是等第二天。
     */
    @PostMapping("/recycle/purge")
    @Audited(action = AuditAction.PRODUCT_AUDIT)
    public ApiResponse<RecyclePurgeResult> purgeRecycleBin() {
        return ApiResponse.ok(new RecyclePurgeResult(sellerProductService.purgeExpiredByOperator()));
    }

    // ---------------- 导出列定义（与审核台 / 变更历史页展示同源） ----------------

    private static final List<String> AUDIT_HEADERS = List.of(
            "商品ID", "店铺ID", "标题", "类目", "品牌", "审核状态", "商品状态",
            "最低价(元)", "最高价(元)", "版本", "创建时间", "审核时间", "驳回原因");

    private static final List<String> CHANGE_HEADERS = List.of(
            "时间", "商品ID", "商品标题", "店铺ID", "事件", "操作人", "操作人ID",
            "原审核状态", "现审核状态", "版本变化", "原因", "字段变更");

    private static List<String> auditRow(AdminProductAuditView view) {
        return List.of(
                text(view.id()),
                text(view.sellerId()),
                text(view.title()),
                text(view.categoryName()),
                text(view.brandName()),
                text(view.auditStatus()),
                text(view.status()),
                CsvExport.yuan(view.minPrice()),
                CsvExport.yuan(view.maxPrice()),
                text(view.version()),
                CsvExport.beijingTime(view.createdAt()),
                CsvExport.beijingTime(view.auditAt()),
                text(view.auditReason()));
    }

    private static List<String> changeRow(SpuChangeHistoryItem item) {
        String diff = item.changes() == null ? "" : item.changes().stream()
                .map(c -> c.label() + ": " + text(c.before()) + " → " + text(c.after()))
                .collect(java.util.stream.Collectors.joining("；"));
        return List.of(
                CsvExport.beijingTime(item.createdAt()),
                text(item.spuId()),
                text(item.spuTitle()),
                text(item.sellerId()),
                text(item.event()),
                text(item.actorLabel()),
                text(item.actorId()),
                text(item.fromAuditStatus()),
                text(item.toAuditStatus()),
                text(item.versionBefore()) + " → " + text(item.versionAfter()),
                text(item.reason()),
                diff);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
