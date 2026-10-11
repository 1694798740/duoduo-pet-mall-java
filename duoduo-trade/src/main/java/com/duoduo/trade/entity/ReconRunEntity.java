package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账轮次（S3）。
 *
 * <p>一轮对账 = (业务日 × 范围)。一天一个范围内只有一轮结果 ——
 * 同一天重跑**覆盖**上一轮，而不是累积成两轮，
 * 靠 {@code uk_recon_run_day_scope} 兜底而不是靠应用层先查后插。
 *
 * <p>{@code status} 的三态语义见 {@link com.duoduo.trade.support.ReconStatus}，
 * 其中 {@code FAILED}（没跑成）**必须**与 {@code CLEAN}（跑完了且干净）分开 ——
 * 把失败记成"全绿"是本功能最危险的失效方式：报表可信，而事实未知。
 *
 * <p>{@code checkedCount} 是刻意留的一个**可疑信号**：一轮对账如果校验了 0 个对象，
 * 它必然"没有差异"。没有这个字段就无法把「真的没问题」与「什么都没看」分开。
 */
@TableName("t_recon_run")
public class ReconRunEntity {

    @TableId
    private Long id;
    /** UTC 业务日 */
    private LocalDate bizDate;
    /** MONEY / STOCK */
    private String scope;
    /** CLEAN / DIRTY / FAILED */
    private String status;
    private Integer checkedCount;
    private Integer diffCount;
    private Integer highCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String message;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCheckedCount() { return checkedCount; }
    public void setCheckedCount(Integer checkedCount) { this.checkedCount = checkedCount; }
    public Integer getDiffCount() { return diffCount; }
    public void setDiffCount(Integer diffCount) { this.diffCount = diffCount; }
    public Integer getHighCount() { return highCount; }
    public void setHighCount(Integer highCount) { this.highCount = highCount; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
