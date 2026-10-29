package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.SeckillDtos.ActivityView;
import com.duoduo.trade.dto.SeckillDtos.BatchToggleRequest;
import com.duoduo.trade.dto.SeckillDtos.CreateActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.EditActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.ToggleRequest;
import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.dto.SeckillDtos.SkuView;
import com.duoduo.trade.service.SeckillService;
import jakarta.servlet.http.HttpServletResponse;
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

    /**
     * 秒杀活动导出（矩阵第二批）：一行一个 SKU。
     *
     * <p>列表是「活动 + 内嵌 SKU」结构，CSV 只能平铺 —— 一场活动有 N 个 SKU 就出 N 行，
     * 活动级字段重复出现；宁可冗余也不要把 SKU 塞进一个单元格（那样无法再筛选/透视）。
     */
    @GetMapping("/activities/export")
    public void export(HttpServletResponse response) throws java.io.IOException {
        OperatorGuard.requireOperator();
        List<ActivityView> activities = seckillService.listActivitiesForAdmin();
        List<List<String>> rows = activities.stream().flatMap(a -> {
            String status = a.enabled() ? "已上架" : "已下架";
            if (a.skus() == null || a.skus().isEmpty()) {
                return java.util.stream.Stream.of(row(a, status, null));
            }
            return a.skus().stream().map(s -> row(a, status, s));
        }).toList();
        CsvExport.write(response, "秒杀活动-" + CsvExport.stamp(), HEADERS, rows);
    }

    private static final List<String> HEADERS = List.of(
            "活动ID", "活动标题", "上架状态", "时段", "开始时间", "结束时间",
            "商品ID", "SKU ID", "商品标题", "规格", "秒杀价(元)", "原价(元)",
            "活动配额", "剩余", "限购");

    private static List<String> row(ActivityView activity, String status, SkuView sku) {
        return List.of(
                activity.id() == null ? "" : String.valueOf(activity.id()),
                text(activity.title()),
                status,
                text(activity.phase()),
                CsvExport.beijingTime(activity.startAt()),
                CsvExport.beijingTime(activity.endAt()),
                sku == null || sku.spuId() == null ? "" : String.valueOf(sku.spuId()),
                sku == null || sku.skuId() == null ? "" : String.valueOf(sku.skuId()),
                sku == null ? "" : text(sku.spuTitle()),
                sku == null ? "" : text(sku.specText()),
                sku == null || sku.seckillPrice() == null ? "" : CsvExport.yuan(sku.seckillPrice()),
                sku == null || sku.originalPrice() == null ? "" : CsvExport.yuan(sku.originalPrice()),
                sku == null || sku.totalStock() == null ? "" : String.valueOf(sku.totalStock()),
                sku == null || sku.availableStock() == null ? "" : String.valueOf(sku.availableStock()),
                sku == null || sku.perLimit() == null ? "" : String.valueOf(sku.perLimit()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
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
     * 批量上下架（2026-10-28 · 批量操作第一批）。
     *
     * <p>逐条结果：某场活动不存在只让该条失败，其余照常上/下架。
     * 审计只记一条总数的摘要（目标由 {@code SeckillService#toggle} 逐条写）。
     */
    @PostMapping("/activities/batch-status")
    @Audited(action = AuditAction.SECKILL_STATUS)
    public ApiResponse<BatchDtos.Result> batchStatus(@Valid @RequestBody BatchToggleRequest request) {
        OperatorGuard.requireOperator();
        AuditTrail.note("批量" + (request.enabled() ? "上架" : "下架") + " "
                + request.ids().size() + " 场秒杀活动");
        return ApiResponse.ok(seckillService.toggleBatch(request.ids(), request.enabled()));
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
