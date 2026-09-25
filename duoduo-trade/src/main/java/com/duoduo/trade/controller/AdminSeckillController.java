package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.SeckillDtos.ActivityView;
import com.duoduo.trade.dto.SeckillDtos.CreateActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.ToggleRequest;
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
 * 秒杀运营端（A14）：建活动 + 上下架。运营角色由服务端 OperatorGuard 校验。
 */
@RestController
@RequestMapping("/api/admin/seckill")
public class AdminSeckillController {

    private final SeckillService seckillService;

    public AdminSeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /** 活动全量列表（运营视角，含已下架） */
    @GetMapping("/activities")
    public ApiResponse<List<ActivityView>> list() {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(seckillService.listActivities());
    }

    /** 创建秒杀活动（原价缺省取商品当前售价；余量自动装载进 Redis） */
    @PostMapping("/activities")
    public ApiResponse<ActivityView> create(@Valid @RequestBody CreateActivityRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(seckillService.createActivity(request));
    }

    /** 上下架（enabled=false 即对 C 端隐藏） */
    @PostMapping("/activities/{id}/status")
    public ApiResponse<ActivityView> toggle(@PathVariable Long id, @Valid @RequestBody ToggleRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(seckillService.toggle(id, request.enabled()));
    }
}
