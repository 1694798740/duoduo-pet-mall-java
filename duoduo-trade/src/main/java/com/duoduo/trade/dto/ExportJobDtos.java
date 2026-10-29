package com.duoduo.trade.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 异步导出任务的接口形态（矩阵第 9 项 · 2026-10-28）。
 *
 * <p>任务与同步导出共用同一套筛选口径：创建时把参数存成快照，
 * 后台按快照分页写 CSV —— 不是「另一条查询路径」，所以不会有
 * 「同步导出的行数和异步导出的行数对不上」这种问题。
 */
public final class ExportJobDtos {

    private ExportJobDtos() {
    }

    /**
     * 创建任务。
     *
     * @param module  导出模块，当前只支持 ORDERS（全平台订单）
     * @param status  订单状态（可选，与列表同名）
     * @param keyword 订单号或商品标题（可选，与列表同名）
     */
    public record CreateRequest(
            @NotBlank(message = "缺少导出模块")
            String module,
            String status,
            String keyword
    ) {
    }

    /**
     * 任务视图。
     *
     * @param id          任务 id（雪花，字符串口径）
     * @param filters     筛选摘要（给列表展示，不需要前端再解析 params）
     * @param rowCount    已写出行数
     * @param totalRows   预估总行数（创建时 count）
     * @param downloadable 当前是否可下载（SUCCESS + 未过期 + 文件存在）
     */
    public record JobView(
            String id,
            String module,
            String moduleLabel,
            String status,
            String statusLabel,
            String filters,
            int rowCount,
            int totalRows,
            long fileSize,
            String errorMessage,
            String createdAt,
            String finishedAt,
            String expiresAt,
            int downloadCount,
            boolean downloadable
    ) {
    }

    /** 任务列表（我的任务） */
    public record JobPage(List<JobView> items, long total, int page, int pageSize, int pageCount) {
    }
}
