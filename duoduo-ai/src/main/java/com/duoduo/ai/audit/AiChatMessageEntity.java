package com.duoduo.ai.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 会话消息审计（表 {@code t_ai_chat_message}）。
 *
 * <p>与 Redis 会话的区别：Redis 只保留「模型下一轮需要的最近 10 条」且 2 小时过期；
 * 这张表是**事实留存**——客服复盘、badcase 回流、合规抽查都靠它。
 * 两者不能互相替代：把审计放 Redis 会随缓存一起消失，把会话放 MySQL 会让每轮对话多一次磁盘写。
 */
@TableName("t_ai_chat_message")
public class AiChatMessageEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String sessionId;

    /** USER / ASSISTANT / TOOL / SYSTEM */
    private String role;

    private String content;

    /** 工具消息才有值 */
    private String toolName;

    private String toolCallId;

    /** JSON：工具入参 */
    private String toolArgs;

    /** JSON：工具结果（已裁剪的 ToolResult） */
    private String toolResult;

    /** 该轮对话发生时用户所在页面（B2） */
    private String pageUrl;

    /** JSON 数组：模型声明的卡片引用（B2） */
    private String cardRefs;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(String toolArgs) {
        this.toolArgs = toolArgs;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getCardRefs() {
        return cardRefs;
    }

    public void setCardRefs(String cardRefs) {
        this.cardRefs = cardRefs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
