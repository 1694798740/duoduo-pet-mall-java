-- ============================================================================
-- 增量 74 · AI 管家（B2 审计补齐）：会话消息卡片引用 + 工具日志唯一键修正
--
-- 执行说明：
--   * 面向存量库；必须先执行 73（page_url / badge_patch 已由 73 添加）。
--   * 幂等：MySQL 8 不支持 ADD COLUMN / DROP INDEX IF EXISTS，
--     统一用 information_schema 判重 + 动态 SQL，可重复执行。
--
-- 变更内容：
--   ① t_ai_chat_message 增加 card_refs：
--      记录模型本轮声明的卡片引用（工具名数组）。审计要能回答
--      「这句话配了哪几张卡」，只存 reply 是不够的。
--   ② t_ai_tool_call_log 的唯一键 uk_request_tool(request_id, tool_name)
--      会挡掉「同一轮用不同筛选条件调同一个工具两次」——审计静默丢一条，
--      且插入报错会被异步线程吞掉。改为普通索引，保留查询能力、去掉误伤。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_ai`;

-- ---------------------------------------------------------
-- ① 会话消息：模型声明的卡片引用
-- ---------------------------------------------------------
SET @has_card_refs := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_chat_message'
      AND COLUMN_NAME='card_refs');
SET @sql := IF(@has_card_refs = 0,
    'ALTER TABLE duoduo_ai.t_ai_chat_message ADD COLUMN card_refs JSON NULL COMMENT ''本轮卡片引用（工具名数组，模型声明、服务端校验）'' AFTER page_url',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------
-- ② 工具调用日志：唯一键 → 普通索引
-- ---------------------------------------------------------
SET @has_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_tool_call_log'
      AND INDEX_NAME='uk_request_tool');
SET @sql := IF(@has_uk > 0,
    'ALTER TABLE duoduo_ai.t_ai_tool_call_log DROP INDEX uk_request_tool',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_tool_call_log'
      AND INDEX_NAME='idx_request_tool');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE duoduo_ai.t_ai_tool_call_log ADD INDEX idx_request_tool (request_id, tool_name)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
