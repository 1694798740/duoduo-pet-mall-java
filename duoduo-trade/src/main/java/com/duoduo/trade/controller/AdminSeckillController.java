package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
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
    @Audited(action = AuditAction.SECKILL_CREATE)
    public ApiResponse<ActivityView> create(@Valid @RequestBody CreateActivityRequest request) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(seckillService.createActivity(request));
    }

    /** 上下架（enabled=false 即对 C 端隐藏） */
    @PostMapping("/activities/{id}/status")
    @Audited(action = AuditAction.SECKILL_STATUS)
    public ApiResponse<ActivityView> toggle(@PathVariable Long id, @Valid @RequestBody ToggleRequest request) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "SECKILL");
        AuditTrail.change("enabled=" + (!request.enabled()), "enabled=" + request.enabled());
        return ApiResponse.ok(seckillService.toggle(id, request.enabled()));
    }

    /**
     * 编辑活动（R74）：只改标题与时间 —— 商品 / 价格 / 配额不改（理由见 {@code EditActivityRequest}）。
     * 已成交的活动不允许把开始时间改到未来。
     */
    @PutMapping("/activities/{id}")
    @Audited(action = AuditAction.SECKILL_UPDATE)
    public ApiResponse<ActivityView> edit(@PathVariable Long id, @Valid @RequestBody EditActivityRequest request) {
        OperatorGuard.requireOperator();
        // 编辑只放标题与时间（商品/秒杀价/配额刻意不给改，见 R74）
        AuditTrail.target(id, "SECKILL");
        AuditTrail.note("编辑标题/时间");
        return ApiResponse.ok(seckillService.editActivity(id, request));
    }

    /**
     * 删除活动（R74）：**只要还有未关闭的秒杀订单就拒绝**（待付款 / 已付款 / 已发货 / 已完成都算）。
     * 订单**全部已关闭**（从未付过钱的终态单）的活动可以删 —— 否则「自测建一场、下单再取消」
     * 会让活动只增不减，运营列表迟早堆满清不掉的垃圾。
     * 删除时连带清理秒杀 SKU 行、已关闭订单的链接行与 Redis 余量键；`t_order` 一律留档。
     */
    @DeleteMapping("/activities/{id}")
    @Audited(action = AuditAction.SECKILL_DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        OperatorGuard.requireOperator();
        AuditTrail.target(id, "SECKILL");
        seckillService.deleteActivity(id);
        return ApiResponse.ok();
    }
}
