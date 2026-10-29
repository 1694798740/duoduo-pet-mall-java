package com.duoduo.ai.audit;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * AI 审计落库（2026-10-29 · B2）。
 *
 * <h2>为什么必须异步</h2>
 * 审计是**旁路事实**，不是对话的前置条件：让用户在等模型回复的同时再等两次 INSERT，
 * 等于用可用性给审计买单。这里提交到独立有界队列，主链路只做一次入队。
 *
 * <h2>为什么失败只记日志</h2>
 * 与「写记忆」同一条纪律：审计写失败不能把已经生成的回复变成 500 ——
 * 用户看到的是「AI 挂了」，而真实原因只是日志表插入失败。
 * 反过来，审计写不进去必须**可被发现**：所有失败都有 WARN 日志 + 丢弃计数，
 * 并配合启动自检（{@code AiSchemaGuard}）把「表结构缺失」提前到启动阶段。
 *
 * <h2>记录什么</h2>
 * <ul>
 *   <li>每轮：USER 消息（含 page_url）+ ASSISTANT 回复（含 card_refs）；</li>
 *   <li>每次工具调用：入参、结果、状态、耗时、page_url、badge_patch。</li>
 * </ul>
 * 这两类记录合起来才能回答「模型当时看到了什么、做了什么、为什么这么回」。
 */
@Service
public class AiAuditService {

    private static final Logger log = LoggerFactory.getLogger(AiAuditService.class);

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";

    private final AiChatMessageMapper messageMapper;
    private final AiToolCallLogMapper toolCallLogMapper;
    private final AiChatSessionMapper sessionMapper;
    private final ObjectMapper mapper;
    private final Executor executor;

    public AiAuditService(AiChatMessageMapper messageMapper,
                          AiToolCallLogMapper toolCallLogMapper,
                          AiChatSessionMapper sessionMapper,
                          ObjectMapper mapper,
                          @Qualifier("aiAuditExecutor") Executor executor) {
        this.messageMapper = messageMapper;
        this.toolCallLogMapper = toolCallLogMapper;
        this.sessionMapper = sessionMapper;
        this.mapper = mapper;
        this.executor = executor;
    }

    /** 一轮对话结束后调用：异步写 USER + ASSISTANT 两条消息。 */
    public void recordTurn(TurnRecord record) {
        if (record == null || blank(record.sessionId())) {
            return;
        }
        submit(() -> writeTurn(record), "turn", record.sessionId());
    }

    /** 每次工具执行后调用：异步写一行工具日志。 */
    public void recordTool(ToolRecord record) {
        if (record == null || blank(record.sessionId())) {
            return;
        }
        submit(() -> writeTool(record), "tool:" + record.toolName(), record.sessionId());
    }

    private void writeTurn(TurnRecord record) {
        // 会话权威行：Redis 只负责热状态，这张表负责「会话存在过」。
        // 先 upsert 会话，再写两条消息 —— 顺序反了会留下没有归属的消息。
        upsertSession(record);
        insertMessage(record.sessionId(), ROLE_USER, record.userMessage(),
                null, null, null, null, record.pageUrl(), null);
        insertMessage(record.sessionId(), ROLE_ASSISTANT, record.reply(),
                null, null, null, null, record.pageUrl(), json(record.cardRefs()));
    }

    /**
     * 会话 upsert。
     *
     * <p>会话锁保证同一 session 不会并发写，所以「先查再插/更新」在这里是安全的；
     * 不用 ON DUPLICATE KEY 是为了让代码对「哪一次是首次」有明确认知（首次要写入 started_at）。
     */
    private void upsertSession(TurnRecord record) {
        AiChatSessionEntity existing = sessionMapper.selectById(record.sessionId());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (existing == null) {
            AiChatSessionEntity entity = new AiChatSessionEntity();
            entity.setSessionId(record.sessionId());
            entity.setUserId(record.userId());
            entity.setSummary(record.summary());
            entity.setTurnCount(record.turnCount());
            entity.setStatus("ACTIVE");
            entity.setStartedAt(now);
            entity.setLastActiveAt(now);
            sessionMapper.insert(entity);
            return;
        }
        // 登录后 userId 可能从 null 变成真实值：只在有值时覆盖，不清空历史归属
        if (record.userId() != null) {
            existing.setUserId(record.userId());
        }
        if (record.summary() != null && !record.summary().isBlank()) {
            existing.setSummary(record.summary());
        }
        existing.setTurnCount(record.turnCount());
        existing.setStatus("ACTIVE");
        existing.setLastActiveAt(now);
        sessionMapper.updateById(existing);
    }

    private void writeTool(ToolRecord record) {
        AiToolCallLogEntity entity = new AiToolCallLogEntity();
        entity.setId(IdWorker.getId());
        entity.setRequestId(record.requestId());
        entity.setSessionId(record.sessionId());
        entity.setUserId(record.userId());
        entity.setToolName(record.toolName());
        entity.setArgsJson(record.argsJson());
        entity.setResultJson(record.resultJson());
        entity.setStatus(record.status());
        entity.setLatencyMs(record.latencyMs());
        entity.setPageUrl(record.pageUrl());
        entity.setBadgePatch(record.badgePatchJson());
        toolCallLogMapper.insert(entity);
    }

    private void insertMessage(String sessionId, String role, String content,
                               String toolName, String toolCallId, String toolArgs,
                               String toolResult, String pageUrl, String cardRefs) {
        AiChatMessageEntity entity = new AiChatMessageEntity();
        entity.setId(IdWorker.getId());
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setToolName(toolName);
        entity.setToolCallId(toolCallId);
        entity.setToolArgs(toolArgs);
        entity.setToolResult(toolResult);
        entity.setPageUrl(pageUrl);
        entity.setCardRefs(cardRefs);
        messageMapper.insert(entity);
    }

    private void submit(Runnable task, String kind, String sessionId) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    // 单条审计失败不影响后续；日志里带 kind/session 便于定位是「表缺列」还是「数据超长」
                    log.warn("AI 审计写入失败 kind={} session={}: {}", kind, sessionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            // 队列满 / 线程池已关闭：审计是旁路，绝不反压对话
            log.warn("AI 审计任务被拒绝 kind={} session={}: {}", kind, sessionId, e.getMessage());
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("AI 审计 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 一轮对话审计记录。
     *
     * @param pageUrl   用户所在页面（已过白名单；null 表示未提供）
     * @param cardRefs  模型声明的卡片引用（已与真实工具调用比对过）
     */
    public record TurnRecord(
            String sessionId,
            Long userId,
            String pageUrl,
            String userMessage,
            String reply,
            List<String> cardRefs,
            String intent,
            int turnCount,
            String summary
    ) {
    }

    /**
     * 一次工具调用审计记录。
     *
     * @param status         OK / NEED_LOGIN / INVALID_ARGS / TOOL_ERROR …
     * @param latencyMs      服务端执行耗时（不含模型思考）
     * @param badgePatchJson 红点补丁 JSON；null 表示本轮没有角标变化
     */
    public record ToolRecord(
            String requestId,
            String sessionId,
            Long userId,
            String pageUrl,
            String toolName,
            String argsJson,
            String resultJson,
            String status,
            int latencyMs,
            String badgePatchJson
    ) {
    }
}
