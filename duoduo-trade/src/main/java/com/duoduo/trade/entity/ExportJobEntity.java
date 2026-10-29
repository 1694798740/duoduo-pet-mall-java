package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 异步导出任务（矩阵第 9 项 · 2026-10-28）。
 *
 * <p>同步导出有 5 万行上限：超过后 HTTP 请求会长时间挂住、网关超时后前端看到的是
 * 「下载失败」而不是「请稍后再来」。任务把「生成」与「下载」拆成两步：
 * 创建立刻返回 jobId，后台分页写盘，完成后推站内通知，用户随时回来下载。
 *
 * <p>状态只前进：{@code PENDING → RUNNING → SUCCESS / FAILED / EXPIRED}。
 * 不接受「把 FAILED 改回 PENDING」—— 重试应当是新建一个任务，这样历史失败原因不会被覆盖。
 *
 * <p>{@code params} 是创建时的筛选快照（JSON）：列表口径以后怎么改，
 * 都不影响历史任务「当时是按什么条件导的」。
 */
@TableName("t_export_job")
public class ExportJobEntity {

    @TableId
    private Long id;
    /** 入口域：ADMIN / SELLER（当前只做 ADMIN） */
    private String scope;
    /** 导出模块：ORDERS（后续可扩展） */
    private String module;
    /** 筛选参数 JSON 快照（如 {"status":"PAID","keyword":"猫粮"}） */
    private String params;
    /** PENDING / RUNNING / SUCCESS / FAILED / EXPIRED */
    private String status;
    /** 创建时按同一筛选 count 的预估总行数（进度百分比的分母） */
    private Integer totalRows;
    /** 已写出行数（每页更新一次） */
    private Integer rowCount;
    private String fileName;
    private String filePath;
    private Long fileSize;
    /** 失败原因（可读文案，给运营自助排障） */
    private String errorMessage;
    /** 创建人 userId：下载归属的唯一判据 */
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 文件过期时间（成功起 retentionDays 天） */
    private LocalDateTime expiresAt;
    private Integer downloadCount;
    private LocalDateTime lastDownloadedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer totalRows) {
        this.totalRows = totalRows;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getDownloadCount() {
        return downloadCount;
    }

    public void setDownloadCount(Integer downloadCount) {
        this.downloadCount = downloadCount;
    }

    public LocalDateTime getLastDownloadedAt() {
        return lastDownloadedAt;
    }

    public void setLastDownloadedAt(LocalDateTime lastDownloadedAt) {
        this.lastDownloadedAt = lastDownloadedAt;
    }
}
