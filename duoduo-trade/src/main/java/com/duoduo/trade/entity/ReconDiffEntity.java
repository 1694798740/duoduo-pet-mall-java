package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对账差异明细（S3）。
 *
 * <p>{@code rule} 存的是 {@link com.duoduo.trade.support.ReconRule} 的**枚举名**而不是文案：
 * 文案改一个字不该让「连续 3 天出现同类差异」这条统计断掉，而按文案分组时它一定会断。
 *
 * <p>{@code unique(run_id, rule, ref_type, ref_id)}：同一个对象上同一条规则只留一条。
 * 少了这个约束，一次取数重复（或重跑没删干净）会让明细翻倍 ——
 * 而"差异变多了"会被读成"问题变严重了"，把一次程序缺陷伪装成一次业务恶化。
 */
@TableName("t_recon_diff")
public class ReconDiffEntity {

    @TableId
    private Long id;
    private Long runId;
    private String rule;
    /** HIGH / MEDIUM */
    private String severity;
    /** ORDER / SKU / SELLER */
    private String refType;
    private Long refId;
    private String expected;
    private String actual;
    private String detail;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getActual() { return actual; }
    public void setActual(String actual) { this.actual = actual; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
