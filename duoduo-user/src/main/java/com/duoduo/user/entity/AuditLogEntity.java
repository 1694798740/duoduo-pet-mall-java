package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 后台操作审计日志（A7 建表 / E18 扩列）。
 *
 * <p>记录「谁在哪个租户上、对什么、做了什么、结果如何、从哪来」。
 * 只追加、不更新、不删除（无 updated_at）—— 审计日志的价值在于
 * 不可篡改的顺序历史，提供 UPDATE 语义只会诱使人去「修正历史」。
 *
 * <p><b>★ E18 起这张表的写入与修改纪律（有断言兜底）</b>：
 * <ul>
 *   <li>写入只有两个入口：{@code AuditLogService.record(...)}（本地 / 内部端点 /
 *       定时任务）与 {@code AuditInterceptor} 经 {@code AuditSink} 的自动留痕。
 *       业务服务类**不得**再自己 new 一个实体去 insert。</li>
 *   <li><b>不存在任何 update / delete 代码路径</b>（{@code audit-log-smoke} 里有一条
 *       静态断言全文扫描仓库）。列上没有 updated_at 也是同一层意思。</li>
 *   <li>类目 {@code SECURITY} / {@code FUNDS} 属「不可删改」档
 *       （唯一判据 {@code AuditCategory.immutable()}）：将来若做归档清理，
 *       这两档必须整体保留。</li>
 * </ul>
 */
@TableName("t_audit_log")
public class AuditLogEntity {

    @TableId
    private Long id;
    /** 操作人用户 ID */
    private Long actorId;
    /** 操作人手机号（审计追责用；库表非日志通道，不进应用日志） */
    private String actorPhone;
    /** 动作标识，如 USER_ROLE_CHANGE（取值见 duoduo-common 的 AuditAction） */
    private String action;
    /** 动作类目：SECURITY / FUNDS / TENANT / CONTENT / OTHER */
    private String category;
    /** 结果：SUCCESS / FAILED */
    private String outcome;
    /** 目标对象 ID；**0 = 平台级操作、无单一目标**（如全站索引重建） */
    private Long targetId;
    /** 目标类型：USER / MERCHANT / PRODUCT / STAFF / ORDER / … */
    private String targetType;
    /** 租户（店铺主体 userId）；0 = 平台级 / 自营 */
    private Long tenantId;
    /** 变更前值（机读）；null = 该动作不适用 */
    private String beforeValue;
    /** 变更后值（机读）；null = 该动作不适用 */
    private String afterValue;
    /** 调用方 IP（网关 X-Real-IP 透传） */
    private String ip;
    /** 链路 ID，用于与访问日志对账 */
    private String requestId;
    /** 变更明细（人读摘要，如 USER -> OPERATOR） */
    private String detail;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getActorPhone() {
        return actorPhone;
    }

    public void setActorPhone(String actorPhone) {
        this.actorPhone = actorPhone;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public void setBeforeValue(String beforeValue) {
        this.beforeValue = beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public void setAfterValue(String afterValue) {
        this.afterValue = afterValue;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
