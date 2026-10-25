package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditRequest;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditView;
import com.duoduo.product.dto.SpuAuditDtos.SpuAuditLogView;
import com.duoduo.product.service.SellerProductService;
import com.duoduo.product.service.SpuAuditHistoryService;
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
    public ApiResponse<RecyclePurgeResult> purgeRecycleBin() {
        return ApiResponse.ok(new RecyclePurgeResult(sellerProductService.purgeExpiredByOperator()));
    }
}
