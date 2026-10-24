package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品审核历史（A-08，append-only）。
 *
 * <p>t_spu 上的 audit_* 四列只保留「当前值」，本表保留「怎么走到当前值」：
 * 改判、多次驳回、谁在什么时候把哪一版内容推回审核。行一旦写入不更新、不删除。
 *
 * <p>{@code changes} 是字段级 diff 的 JSON（不是整行快照）：只留真正变化的字段，
 * 每个值最多 2000 个码点，超长截断并打标。整行快照会让「改了一个标题」淹没在
 * 未变字段里，且存储随编辑次数线性膨胀。
 */
@TableName("t_spu_audit_log")
public class SpuAuditLogEntity {

    @TableId
    private Long id;
    private Long spuId;
    /** 归属店铺主体 user id；0 = 平台自营（自营商品不写本表） */
    private Long sellerId;
    /** SUBMIT / EDIT / RESUBMIT / APPROVE / REJECT / SAMPLE_REJECT */
    private String event;
    /** SELLER / STAFF / OPERATOR / SYSTEM */
    private String actorType;
    /** 操作人 user id；SYSTEM 为空 */
    private Long actorId;
    /** 操作人手机号快照（人可能改号，历史保留当时的值） */
    private String actorPhone;
    private String fromAuditStatus;
    private String toAuditStatus;
    private String auditSource;
    private String reason;
    private Integer versionBefore;
    private Integer versionAfter;
    /** 抽查判违规时运营看到的那一版；普通事件为空 */
    private Integer sampledVersion;
    /** 字段级变更 JSON：[{field,label,before,after,truncated}] */
    private String changes;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
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

    public String getFromAuditStatus() {
        return fromAuditStatus;
    }

    public void setFromAuditStatus(String fromAuditStatus) {
        this.fromAuditStatus = fromAuditStatus;
    }

    public String getToAuditStatus() {
        return toAuditStatus;
    }

    public void setToAuditStatus(String toAuditStatus) {
        this.toAuditStatus = toAuditStatus;
    }

    public String getAuditSource() {
        return auditSource;
    }

    public void setAuditSource(String auditSource) {
        this.auditSource = auditSource;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getVersionBefore() {
        return versionBefore;
    }

    public void setVersionBefore(Integer versionBefore) {
        this.versionBefore = versionBefore;
    }

    public Integer getVersionAfter() {
        return versionAfter;
    }

    public void setVersionAfter(Integer versionAfter) {
        this.versionAfter = versionAfter;
    }

    public Integer getSampledVersion() {
        return sampledVersion;
    }

    public void setSampledVersion(Integer sampledVersion) {
        this.sampledVersion = sampledVersion;
    }

    public String getChanges() {
        return changes;
    }

    public void setChanges(String changes) {
        this.changes = changes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
