package com.duoduo.ai.client;

import com.duoduo.ai.client.dto.ButlerCouponDtos.CouponView;
import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 营销域客户端（AI 全能管家 · 阶段二）：只读「我的券包」。
 *
 * <p>领券、锁券、核销都不在这里：按最终落地文档 Q7，交易类动作 AI 只给导航入口，
 * 绝不代用户执行 —— 领券要经过用户的确认与营销域自己的并发控制。
 */
@FeignClient(name = "duoduo-marketing", contextId = "butlerCouponClient",
        configuration = AiFeignConfig.class)
public interface CouponClient {

    /**
     * 我的券包。
     *
     * @param status UNUSED / LOCKED / USED / EXPIRED；null = 全部
     */
    @GetMapping("/api/coupons/mine")
    ApiResponse<List<CouponView>> mine(@RequestHeader("X-User-Id") Long userId,
                                       @RequestParam(value = "status", required = false) String status);
}
