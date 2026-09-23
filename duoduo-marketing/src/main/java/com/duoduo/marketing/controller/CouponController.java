package com.duoduo.marketing.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.marketing.dto.CouponDtos.ClaimResult;
import com.duoduo.marketing.dto.CouponDtos.CouponView;
import com.duoduo.marketing.dto.CouponDtos.UsableResult;
import com.duoduo.marketing.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家侧券接口：领券、我的券包、结算页可用券。
 *
 * <p><b>全部需要登录</b>（网关白名单只放行 {@code /api/coupons/templates}）。
 * 身份从网关透传的 {@code X-User-Id} 取，接口不接受 userId 参数 ——
 * 一旦接受，就等于给「代别人领券」留了一个入口。
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 领取一张券。
     *
     * <p>重复领取返回 {@code replay=true} 而不是报错：用户双击、网络重试、多端并发
     * 都是正常行为，把它们报成错误只会让用户以为自己操作失败了。
     * 真正的约束（一人一张）由唯一索引保证，不靠这里「先查有没有」。
     */
    @PostMapping("/templates/{templateId}/claim")
    public ApiResponse<ClaimResult> claim(@PathVariable Long templateId) {
        return ApiResponse.ok(couponService.claim(templateId));
    }

    /**
     * 我的券包。
     *
     * @param status 可选筛选：UNUSED / LOCKED / USED / EXPIRED；空表示全部
     */
    @GetMapping("/mine")
    public ApiResponse<List<CouponView>> mine(@RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(couponService.mine(status));
    }

    /**
     * 结算页可用券：给定本次订单的商品总额（分），返回每张券能不能用与最优券。
     *
     * <p>为什么做成「传金额查」而不是「前端自己拿券包算」：
     * 门槛、有效期、是否已被锁定这三件事都在服务端，前端算出来的结果
     * 与服务端锁券时的判断迟早会不一致，表现出来就是「页面说能用、下单说不能用」。
     */
    @GetMapping("/mine/usable")
    public ApiResponse<UsableResult> usable(@RequestParam(name = "amount", defaultValue = "0") long amount) {
        return ApiResponse.ok(couponService.usable(amount));
    }
}
