package com.duoduo.ai.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 会话权威记录（表 {@code t_ai_chat_session}）。
 *
 * <p>Redis 里的会话是**热状态**（2 小时过期、只保留最近 10 条）；
 * 这张表是**权威存在性**：会话是否发生过、属于谁、聊了多少轮、最后活跃时间。
 * 两者不是备份关系：Redis 丢了可以重建上下文，这张表丢了就无法回答「这个会话存在过吗」。
 */
@TableName("t_ai_chat_session")
public class AiChatSessionEntity {

    @TableId(type = IdType.INPUT)
    private String sessionId;

    private Long userId;

    private String summary;

    private Integer turnCount;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(Integer turnCount) {
        this.turnCount = turnCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
