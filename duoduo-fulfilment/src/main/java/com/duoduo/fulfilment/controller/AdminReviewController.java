package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.fulfilment.dto.ReviewDtos.AdminReviewView;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewAuditRequest;
import com.duoduo.fulfilment.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
