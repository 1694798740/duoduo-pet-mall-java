package com.duoduo.marketing.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.marketing.dto.CouponDtos.CreateTemplateRequest;
import com.duoduo.marketing.dto.CouponDtos.TemplateView;
import com.duoduo.marketing.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 券模板接口：领券中心（公开只读）+ 运营建券（后台）。
 *
 * <p>为什么「公开只读」：领券中心是营销落地页，未登录访客也应看到有什么券 ——
 * 这正是它的作用（用券把人留住）。网关白名单因此只放行
 * {@code /api/coupons/templates} 这一条精确路径，
 * 领券（{@code /api/coupons/{id}/claim}）与我的券包都不在白名单内。
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponTemplateController {

    private final CouponService couponService;

    public CouponTemplateController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 领券中心：当前可领取的券模板（公开只读；是否已领取需登录态，未登录一律显示未领取） */
    @GetMapping("/templates")
    public ApiResponse<List<TemplateView>> templates() {
        return ApiResponse.ok(couponService.claimableTemplates());
    }

    /**
     * 运营创建券模板（管理后台）。
     *
     * <p>路径挂在 {@code /api/coupons/admin/**} 而不是 {@code /api/coupons/templates}：
     * 网关白名单放行了 {@code /api/coupons/templates} 这条精确路径，而白名单是
     * <b>按路径而非按方法</b>匹配的 —— 把写接口放进同一条路径就等于把它一起放行。
     * 因此「公开只读」与「需要登录的写」必须落在不同路径上，
     * 这与评价（{@code /api/reviews/product/**} 放行、发表评价不放行）是同一条规则。
     */
    @PostMapping("/admin/templates")
    public ApiResponse<TemplateView> create(@Valid @RequestBody CreateTemplateRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(couponService.createTemplate(request));
    }
}
