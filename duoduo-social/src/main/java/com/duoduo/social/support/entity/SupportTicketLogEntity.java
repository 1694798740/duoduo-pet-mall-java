package com.duoduo.social.support.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 客服工单处理记录（C-04，append-only）。
 *
 * <p>为什么独立成表而不是在会话上记「最近一次操作」：一个会话会被认领、转交、释放多次，
 * 加列只能保留最后一次；而「这个买家上次是谁接的、为什么转走」恰恰是交接与审计要回答的问题。
 *
 * <p>只记工单动作（CLAIM / TRANSFER / RELEASE），不记普通消息 ——
 * 消息本身已有独立表，混进来会让「处理记录」变成第二份聊天流水。
 */
@TableName("t_support_ticket_log")
public class SupportTicketLogEntity {

    @TableId
    private Long id;
    private Long sessionId;
    /** CLAIM / TRANSFER / RELEASE */
    private String action;
    /** 动作前处理人；NULL = 未认领 */
    private Long fromAssignee;
    /** 动作后处理人；RELEASE 为 NULL */
    private Long toAssignee;
    private Long actorId;
    /** OWNER / STAFF */
    private String actorRole;
    private String note;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getFromAssignee() { return fromAssignee; }
    public void setFromAssignee(Long fromAssignee) { this.fromAssignee = fromAssignee; }
    public Long getToAssignee() { return toAssignee; }
    public void setToAssignee(Long toAssignee) { this.toAssignee = toAssignee; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
