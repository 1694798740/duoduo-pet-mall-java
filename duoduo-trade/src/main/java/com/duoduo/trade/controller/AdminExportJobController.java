package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.trade.dto.ExportJobDtos.CreateRequest;
import com.duoduo.trade.dto.ExportJobDtos.JobView;
import com.duoduo.trade.service.ExportJobService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 异步导出任务接口（矩阵第 9 项 · 2026-10-28）。
 *
 * <p>为什么独立于各模块的 {@code /export}：任务是「跨模块的一类资源」——
 * 订单、售后、审计将来都能创建任务，前端只需要一个「导出任务」页。
 * 模块差异放进 {@code module} 字段，而不是让前端拼 N 个接口。
 *
 * <p>准入：全部 {@link OperatorGuard#requireOperator()}；且任务只能由<b>创建人</b>查看 / 下载
 * （跨人按 404）。导出文件里是全量收货信息，链接一旦可共享，归属校验就形同虚设。
 *
 * <p>导出是只读动作，不写审计日志；但下载次数会记在任务行上，用于事后回答「这份文件被取过几次」。
 */
@RestController
@RequestMapping("/api/admin/export-jobs")
public class AdminExportJobController {

    private final ExportJobService exportJobService;

    public AdminExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    /** 创建任务：返回任务本身（含 jobId），前端据此轮询状态 */
    @Audited(action = AuditAction.EXPORT_JOB_CREATE)
    @PostMapping
    public ApiResponse<JobView> create(@Valid @RequestBody CreateRequest request) {
        LoginUser user = OperatorGuard.requireOperator();
        return ApiResponse.ok(exportJobService.create(request, user.userId()));
    }

    /** 我的任务（按创建时间倒序；别人的任务不在结果里） */
    @GetMapping
    public ApiResponse<PageResult<JobView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser user = OperatorGuard.requireOperator();
        return ApiResponse.ok(exportJobService.list(user.userId(), page, pageSize));
    }

    /** 任务状态（轮询用；与列表同一套视图） */
    @GetMapping("/{id}")
    public ApiResponse<JobView> detail(@PathVariable Long id) {
        LoginUser user = OperatorGuard.requireOperator();
        return ApiResponse.ok(exportJobService.detail(id, user.userId()));
    }

    /**
     * 下载（仅创建人、仅成功且未过期）。
     *
     * <p>文件名走 RFC 5987 编码（{@code filename*=UTF-8''…}）：中文文件名在部分浏览器上
     * 只用 {@code filename="..."} 会变成乱码或直接丢失扩展名。
     */
    @Audited(action = AuditAction.EXPORT_JOB_DOWNLOAD)
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        LoginUser user = OperatorGuard.requireOperator();
        ExportJobService.DownloadFile file = exportJobService.prepareDownload(id, user.userId());
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(file.size())
                .body(new FileSystemResource(file.path()));
    }
}
