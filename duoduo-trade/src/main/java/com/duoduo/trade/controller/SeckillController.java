package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.trade.dto.SeckillDtos.ActivityView;
import com.duoduo.trade.dto.SeckillDtos.PlaceSeckillRequest;
import com.duoduo.trade.dto.SeckillDtos.PlaceSeckillResult;
import com.duoduo.trade.service.SeckillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 限时秒杀 C 端（A14）。列表与详情开放浏览；下单要求登录（网关注入 X-User-*）。
 * 支付不在本域：前端拿 orderNo 走既有的 POST /api/orders/{no}/pay 沙箱收银台。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /** 活动列表（只含运营开关开启的活动，含进行中 / 预告 / 已结束） */
    @GetMapping("/activities")
    public ApiResponse<List<ActivityView>> activities() {
        return ApiResponse.ok(seckillService.listActivities());
    }

    /** 活动详情 */
    @GetMapping("/activities/{id}")
    public ApiResponse<ActivityView> detail(@PathVariable Long id) {
        return ApiResponse.ok(seckillService.detail(id));
    }

    /** 秒杀下单（Redis 预扣挡量 + DB 原子扣减兜底，见 SeckillService） */
    @PostMapping("/orders")
    public ApiResponse<PlaceSeckillResult> place(@Valid @RequestBody PlaceSeckillRequest request) {
        Long userId = UserContext.requireUserId();
        return ApiResponse.ok(seckillService.place(userId, request));
    }
}
