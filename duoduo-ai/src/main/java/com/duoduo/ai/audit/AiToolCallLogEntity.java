package com.duoduo.ai.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 工具调用审计（表 {@code t_ai_tool_call_log}）。
 *
 * <p>一次工具调用一行，记录入参、结果、状态、耗时与红点补丁。
 * 这张表回答的是「模型当时到底查了什么、拿到什么、为什么回复成这样」——
 * 没有它，线上「AI 说订单已发货、页面显示待发货」这类问题只能靠猜。
 *
 * <p><b>唯一键已改为普通索引</b>（见 74 增量 DDL）：同一轮里模型完全可能
 * 用不同筛选条件调同一个工具两次，原唯一键会把第二条审计静默挡掉。
 */
@TableName("t_ai_tool_call_log")
public class AiToolCallLogEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String requestId;

    private String sessionId;

    private Long userId;

    private String toolName;

    /** JSON：工具入参 */
    private String argsJson;

    /** JSON：ToolResult（含 data / pageUrl / badgePatch） */
    private String resultJson;

    /** OK / NEED_LOGIN / INVALID_ARGS / TOOL_ERROR … */
    private String status;

    private Integer latencyMs;

    private String pageUrl;

    /** JSON：本轮红点补丁（无变化为 null） */
    private String badgePatch;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public void setArgsJson(String argsJson) {
        this.argsJson = argsJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getBadgePatch() {
        return badgePatch;
    }

    public void setBadgePatch(String badgePatch) {
        this.badgePatch = badgePatch;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
