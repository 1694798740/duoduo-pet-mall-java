package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.SeckillDtos.ActivityView;
import com.duoduo.trade.dto.SeckillDtos.CreateActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.EditActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.ToggleRequest;
import com.duoduo.trade.service.SeckillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 秒杀运营端（A14）：建活动 + 上下架 + 编辑 + 删除。运营角色由服务端 OperatorGuard 校验。
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
        return ApiResponse.ok(seckillService.listActivitiesForAdmin());
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

    /**
     * 编辑活动（R74）：只改标题与时间 —— 商品 / 价格 / 配额不改（理由见 {@code EditActivityRequest}）。
     * 已成交的活动不允许把开始时间改到未来。
     */
    @PutMapping("/activities/{id}")
    public ApiResponse<ActivityView> edit(@PathVariable Long id, @Valid @RequestBody EditActivityRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(seckillService.editActivity(id, request));
    }

    /**
     * 删除活动（R74）：**只允许删除从未成交的活动**（自测残留 / 建错的草稿）。
     * 已有秒杀订单返回 422 并提示改用「下架」—— 订单是资金事实，删活动会把这条链的根拔掉。
     */
    @DeleteMapping("/activities/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        seckillService.deleteActivity(id);
        return ApiResponse.ok();
    }
}
