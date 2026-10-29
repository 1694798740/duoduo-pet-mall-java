package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.client.ExportJobNotifier;
import com.duoduo.trade.dto.ExportJobDtos.CreateRequest;
import com.duoduo.trade.dto.ExportJobDtos.JobView;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.entity.ExportJobEntity;
import com.duoduo.trade.mapper.ExportJobMapper;
import com.duoduo.trade.support.OrderCsv;
import com.duoduo.trade.support.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异步导出任务（矩阵第 9 项 · 2026-10-28）。
 *
 * <p><b>为什么要有它</b>：同步导出有 5 万行上限。超过之后，请求会在网关超时前挂很久，
 * 用户看到的是「下载失败」，而服务端其实还在生成 —— 既浪费 IO，又让人重试加剧拥堵。
 * 任务把「生成」和「下载」分开：创建立刻返回任务，后台分页写盘，完成后推站内通知。
 *
 * <p><b>为什么复用同步导出的筛选与列定义</b>：{@link OrderCsv} 与
 * {@code OrderService#adminExportPage} 就是同步端点用的那套。另写一份查询，
 * 就会出现「同步导出的行数和异步任务的差几条」这种没人能解释的问题。
 *
 * <p><b>失败必须留原因</b>：运营不需要看日志才知道「是数据脏了还是服务抖了」。
 * 但原因要脱敏、截断：异常消息可能带 SQL，直接回显给前端属于信息泄露。
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    public static final String SCOPE_ADMIN = "ADMIN";
    public static final String MODULE_ORDERS = "ORDERS";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * 写盘分页大小：游标分页（{@code created_at, id}）下每页只扫索引，
     * 500 行一批既摊薄数据库往返、又让进度条有可见的跳动。
     */
    private static final int PAGE_SIZE = 500;
    /** 列表默认 / 最大页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** 任务超过这个时长仍是 PENDING / RUNNING，视为中断（服务重启后没人推进它） */
    private static final int STALE_MINUTES = 30;
    /** 失败原因截断：给用户看的是可读文案，不是完整堆栈 */
    private static final int MAX_ERROR = 300;

    private final ExportJobMapper jobMapper;
    private final OrderService orderService;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ExportJobNotifier notifier;
    private final Executor exportExecutor;
    private final String exportDir;
    private final int retentionDays;

    public ExportJobService(ExportJobMapper jobMapper,
                            OrderService orderService,
                            SnowflakeIdGenerator idGenerator,
                            ObjectMapper objectMapper,
                            ExportJobNotifier notifier,
                            @Qualifier("exportJobExecutor") Executor exportExecutor,
                            @Value("${duoduo.export.dir:}") String exportDir,
                            @Value("${duoduo.export.retention-days:7}") int retentionDays) {
        this.jobMapper = jobMapper;
        this.orderService = orderService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.notifier = notifier;
        this.exportExecutor = exportExecutor;
        this.exportDir = exportDir == null || exportDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "duoduo-exports").toString()
                : exportDir;
        this.retentionDays = retentionDays;
    }

    // ---------------- 创建 / 查询 ----------------

    /**
     * 创建导出任务：校验筛选 → 落一行 PENDING → 交给线程池。
     *
     * <p>空结果直接拒绝（400）而不是生成一个 0 行文件：运营点「导出」却没拿到数据时，
     * 需要的是「你这个筛选没命中」这句话，而不是一个只有表头的 CSV。
     */
    public JobView create(CreateRequest request, Long userId) {
        String module = normalizeModule(request == null ? null : request.module());
        String status = trimToNull(request.status());
        String keyword = trimToNull(request.keyword());

        // 先走一遍与同步导出完全相同的计数：状态非法会在这里 400，不会被拖到后台才失败
        long total = orderService.adminExportCount(status, keyword);
        if (total <= 0) {
            throw BizException.validation("没有符合条件的订单，无需导出");
        }

        LocalDateTime now = utcNow();
        ExportJobEntity job = new ExportJobEntity();
        job.setId(idGenerator.nextId());
        job.setScope(SCOPE_ADMIN);
        job.setModule(module);
        job.setParams(writeParams(new OrderFilters(status, keyword)));
        job.setStatus(STATUS_PENDING);
        job.setTotalRows((int) Math.min(total, Integer.MAX_VALUE));
        job.setRowCount(0);
        job.setFileSize(0L);
        job.setCreatedBy(userId);
        job.setCreatedAt(now);
        job.setDownloadCount(0);
        jobMapper.insert(job);

        try {
            exportExecutor.execute(() -> runJob(job.getId()));
        } catch (RejectedExecutionException e) {
            jobMapper.markFailed(job.getId(), "导出队列已满，请稍后重试", utcNow());
            throw BizException.validation("导出任务排队已满（最多 2 个并行、20 个排队），请稍后重试");
        }
        log.info("导出任务已创建 jobId={} module={} totalRows={} createdBy={}", job.getId(), module, total, userId);
        return toView(job, now);
    }

    /** 我的任务（只返回自己创建的 —— 任务含筛选条件，能看到别人的任务等于看到别人的查询意图） */
    public PageResult<JobView> list(Long userId, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<ExportJobEntity> wrapper = new LambdaQueryWrapper<ExportJobEntity>()
                .eq(ExportJobEntity::getCreatedBy, userId)
                .orderByDesc(ExportJobEntity::getCreatedAt)
                .orderByDesc(ExportJobEntity::getId);
        var result = jobMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, size),
                wrapper);
        LocalDateTime now = utcNow();
        List<JobView> items = result.getRecords().stream().map(job -> toView(job, now)).toList();
        return PageResult.of(items, result.getTotal(), pageNo, size);
    }

    /** 任务详情（同样只允许创建人读，跨人按 404 —— 不区分「不存在」与「无权限」） */
    public JobView detail(Long id, Long userId) {
        return toView(requireMine(id, userId), utcNow());
    }

    /**
     * 下载前置校验：创建人 + 成功 + 未过期 + 文件存在。
     *
     * <p>文件按任务 id 命名且只落本地卷，没有公开路径 —— 唯一入口就是这个端点。
     * 用 404 回应「别人的任务」：不泄漏「这个 id 存在」这一事实。
     */
    public DownloadFile prepareDownload(Long id, Long userId) {
        ExportJobEntity job = requireMine(id, userId);
        LocalDateTime now = utcNow();
        if (STATUS_EXPIRED.equals(job.getStatus())) {
            throw BizException.notFound("导出文件已过期，请重新导出");
        }
        if (!STATUS_SUCCESS.equals(job.getStatus())) {
            throw BizException.businessRule("任务尚未完成，当前状态：" + statusLabel(job.getStatus()));
        }
        if (job.getExpiresAt() != null && !job.getExpiresAt().isAfter(now)) {
            jobMapper.markExpired(job.getId());
            throw BizException.notFound("导出文件已过期，请重新导出");
        }
        if (job.getFilePath() == null || job.getFileName() == null) {
            throw BizException.notFound("导出文件不存在，请重新导出");
        }
        Path path = Path.of(job.getFilePath());
        if (!Files.isReadable(path)) {
            // 文件被手工清理 / 换机丢卷：状态置 EXPIRED，用户看到的是「过期」而不是「服务器错误」
            jobMapper.markExpired(job.getId());
            throw BizException.notFound("导出文件已不存在，请重新导出");
        }
        jobMapper.markDownloaded(job.getId(), now);
        long size = job.getFileSize() == null ? 0L : job.getFileSize();
        return new DownloadFile(job.getFileName(), path, size);
    }

    // ---------------- 后台生成 ----------------

    /**
     * 真正写文件（线程池里执行）。
     *
     * <p>先写 {@code .tmp} 再 rename：下载端点只认 SUCCESS + 最终文件名，
     * 但断点进程若留下半个文件，人工排查时看到「有文件但状态是 FAILED」会以为是下载分支的问题。
     * 临时文件在失败时一并删除。
     */
    void runJob(Long jobId) {
        ExportJobEntity job = jobMapper.selectById(jobId);
        if (job == null || !STATUS_PENDING.equals(job.getStatus())) {
            return;
        }
        if (jobMapper.markRunning(jobId, utcNow()) == 0) {
            return; // 已被别的线程抢走
        }

        OrderFilters filters = readParams(job.getParams());
        String moduleLabel = moduleLabel(job.getModule());
        Path tmp = null;
        try {
            Path dir = Path.of(exportDir);
            Files.createDirectories(dir);
            String fileName = moduleLabel + "-" + CsvExport.stamp() + "-" + jobId + ".csv";
            tmp = dir.resolve(fileName + ".tmp");
            Path target = dir.resolve(fileName);

            long rows = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                writeRow(writer, OrderCsv.HEADERS);
                OrderService.ExportCursor cursor = null;
                while (true) {
                    OrderService.ExportPage batch = orderService.adminExportPage(
                            filters.status(), filters.keyword(), cursor, PAGE_SIZE);
                    if (batch.items().isEmpty()) {
                        break;
                    }
                    for (OrderView order : batch.items()) {
                        writeRow(writer, OrderCsv.row(order));
                    }
                    rows += batch.items().size();
                    cursor = new OrderService.ExportCursor(batch.lastCreatedAt(), batch.lastId());
                    writer.flush();
                    jobMapper.updateProgress(jobId, (int) Math.min(rows, Integer.MAX_VALUE));
                }
            }

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            long size = Files.size(target);
            LocalDateTime finishedAt = utcNow();
            jobMapper.markSuccess(jobId, (int) Math.min(rows, Integer.MAX_VALUE), fileName,
                    target.toString(), size, finishedAt, finishedAt.plusDays(retentionDays));
            log.info("导出任务完成 jobId={} rows={} size={}B", jobId, rows, size);
            notifier.notifyReady(job.getCreatedBy(), jobId, (int) rows, moduleLabel, retentionDays);
        } catch (Exception e) {
            log.error("导出任务失败 jobId={} module={}", jobId, job.getModule(), e);
            deleteQuietly(tmp);
            String message = safeError(e);
            jobMapper.markFailed(jobId, message, utcNow());
            notifier.notifyFailed(job.getCreatedBy(), jobId, moduleLabel, message);
        }
    }

    /**
     * 定时清理：中断任务判定失败、过期文件删除、30 天前的历史行清理。
     *
     * <p>由调度调用（带选主锁）。吞异常由调度侧做 —— 这里抛出去反而会打断后续清理。
     */
    public void sweep() {
        LocalDateTime now = utcNow();
        int stale = jobMapper.failStale(now.minusMinutes(STALE_MINUTES), now);
        if (stale > 0) {
            log.warn("导出任务：{} 个长时间未推进的任务已判定为中断", stale);
        }

        List<ExportJobEntity> expired = jobMapper.selectList(new LambdaQueryWrapper<ExportJobEntity>()
                .eq(ExportJobEntity::getStatus, STATUS_SUCCESS)
                .isNotNull(ExportJobEntity::getExpiresAt)
                .lt(ExportJobEntity::getExpiresAt, now)
                .last("LIMIT 200"));
        for (ExportJobEntity job : expired) {
            if (job.getFilePath() != null) {
                deleteQuietly(Path.of(job.getFilePath()));
            }
            jobMapper.markExpired(job.getId());
        }

        // 30 天前的行：文件早已过期，行本身只占位；保留太久会让「我的任务」淹没在历史里
        jobMapper.delete(new LambdaQueryWrapper<ExportJobEntity>()
                .lt(ExportJobEntity::getCreatedAt, now.minusDays(30)));
    }

    // ---------------- 视图 / 参数 ----------------

    private JobView toView(ExportJobEntity job, LocalDateTime now) {
        boolean downloadable = STATUS_SUCCESS.equals(job.getStatus())
                && job.getFilePath() != null
                && job.getExpiresAt() != null
                && job.getExpiresAt().isAfter(now);
        return new JobView(
                String.valueOf(job.getId()),
                job.getModule(),
                moduleLabel(job.getModule()),
                job.getStatus(),
                statusLabel(job.getStatus()),
                filtersLabel(job.getParams()),
                job.getRowCount() == null ? 0 : job.getRowCount(),
                job.getTotalRows() == null ? 0 : job.getTotalRows(),
                job.getFileSize() == null ? 0L : job.getFileSize(),
                job.getErrorMessage(),
                iso(job.getCreatedAt()),
                iso(job.getFinishedAt()),
                iso(job.getExpiresAt()),
                job.getDownloadCount() == null ? 0 : job.getDownloadCount(),
                downloadable);
    }

    private ExportJobEntity requireMine(Long id, Long userId) {
        ExportJobEntity job = id == null ? null : jobMapper.selectById(id);
        if (job == null || !Objects.equals(job.getCreatedBy(), userId)) {
            throw BizException.notFound("导出任务");
        }
        return job;
    }

    private static String normalizeModule(String module) {
        String normalized = module == null ? "" : module.trim().toUpperCase();
        if (MODULE_ORDERS.equals(normalized)) {
            return normalized;
        }
        throw BizException.validation("暂不支持该导出模块，目前只支持 ORDERS（全平台订单）");
    }

    private String writeParams(OrderFilters filters) {
        try {
            return objectMapper.writeValueAsString(filters);
        } catch (IOException e) {
            throw new IllegalStateException("导出任务参数序列化失败", e);
        }
    }

    private OrderFilters readParams(String json) {
        if (json == null || json.isBlank()) {
            return new OrderFilters(null, null);
        }
        try {
            OrderFilters filters = objectMapper.readValue(json, OrderFilters.class);
            return filters == null ? new OrderFilters(null, null) : filters;
        } catch (IOException e) {
            // 参数快照解析失败属于数据损坏：明确失败，不要让运营拿到一份「条件错了」的文件
            throw new IllegalStateException("导出任务参数已损坏", e);
        }
    }

    private String filtersLabel(String paramsJson) {
        OrderFilters filters;
        try {
            filters = readParams(paramsJson);
        } catch (RuntimeException e) {
            return "筛选条件已损坏";
        }
        String statusText = filters.status() == null
                ? "全部订单"
                : "状态：" + safeLabel(filters.status());
        return filters.keyword() == null ? statusText : statusText + " · 关键词：" + filters.keyword();
    }

    private static String safeLabel(String status) {
        try {
            return OrderStatus.labelOf(status);
        } catch (RuntimeException e) {
            return status;
        }
    }

    private static String moduleLabel(String module) {
        return MODULE_ORDERS.equals(module) ? "全平台订单" : module;
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PENDING -> "排队中";
            case STATUS_RUNNING -> "生成中";
            case STATUS_SUCCESS -> "已完成";
            case STATUS_FAILED -> "失败";
            case STATUS_EXPIRED -> "已过期";
            default -> status;
        };
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null
                : DateTimeFormatter.ISO_INSTANT.format(time.toInstant(ZoneOffset.UTC));
    }

    private static void writeRow(BufferedWriter writer, List<String> row) throws IOException {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(CsvExport.escape(row.get(i)));
        }
        writer.write("\r\n");
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR - 1) + "…";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("导出临时文件删除失败 path={}", path);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 任务参数快照（只允许这两个字段：不要存整个请求体，将来加的字段不会悄悄进入历史任务） */
    public record OrderFilters(String status, String keyword) {
    }

    /** 下载文件描述（控制器只负责拼响应头） */
    public record DownloadFile(String fileName, Path path, long size) {
    }
}
