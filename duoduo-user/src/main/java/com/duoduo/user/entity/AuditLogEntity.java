package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 运营审计日志（A7）。
 *
 * <p>记录「谁给谁改了什么」：角色变更等运营敏感操作逐条落库。
 * 只追加、不更新、不删除（无 updated_at）—— 审计日志的价值在于
 * 不可篡改的顺序历史，提供 UPDATE 语义只会诱使人去「修正历史」。
 */
@TableName("t_audit_log")
public class AuditLogEntity {

    @TableId
    private Long id;
    /** 操作人用户 ID */
    private Long actorId;
    /** 操作人手机号（审计追责用；库表非日志通道，不进应用日志） */
    private String actorPhone;
    /** 动作标识，如 USER_ROLE_CHANGE */
    private String action;
    /** 目标对象 ID，如被改角色的用户 */
    private Long targetId;
    /** 变更明细，如 USER -> OPERATOR */
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

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
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
