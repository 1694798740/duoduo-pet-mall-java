package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.AdminDtos.AdminReport;
import com.duoduo.trade.dto.AnalyticsDtos.AdminCharts;
import com.duoduo.trade.service.AdminOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台（M10）运营报表：GMV / 订单量 / 退款率 / 转化率。
 *
 * <p>报表数据全部在 SQL 侧聚合，不捞全量订单回内存 ——
 * 概览是每次进后台都要走的路径，订单量随时间单调增长，内存聚合迟早拖垮数据库。
 */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final AdminOpsService adminOpsService;

    public AdminReportController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminReport> overview() {
        return ApiResponse.ok(adminOpsService.report());
    }

    /**
     * 运营概览的图表数据（第六十六轮）：分布图 / 折线图 / 柱状图 / 饼图。
     *
     * <p>与 {@code /overview} 分开：报表卡比图表重要，图表取数异常不该把卡片一起拖下水。
     */
    @GetMapping("/charts")
    public ApiResponse<AdminCharts> charts() {
        return ApiResponse.ok(adminOpsService.charts());
    }
}
