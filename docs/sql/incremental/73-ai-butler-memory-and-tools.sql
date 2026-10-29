-- ============================================================================
-- 增量 73 · AI 管家（阶段一）：记忆快照 + 页面上下文 + 记忆变更事件
--
-- 执行说明：
--   * 本文件面向**存量库**执行（72-ai-rag-agent.sql 已建过 t_ai_user_memory /
--     t_ai_chat_message / t_ai_tool_call_log 等表），本文件不重复 72 的建表语句；
--     全新库请先执行 72，再执行本文件。
--   * 目标版本：MySQL 8.0（JSON 列、DATETIME(3) 默认值）。
--   * 幂等：MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，这里统一用 information_schema
--     判重 + 动态 SQL；重复执行不会报 Duplicate column，也不会改已有数据。
--
-- 变更内容：
--   ① t_ai_user_memory 增加 memory_json：长期记忆的结构化快照（键值对），
--      供提示词注入与用户「记忆查看/删除」使用；
--   ② t_ai_chat_message 增加 page_url：记录这一轮对话发生时用户所在页面，
--      让「刚才那个页面」这类指代有事实依据，而不是靠模型猜；
--   ③ t_ai_tool_call_log 增加 page_url 与 badge_patch：工具调用时所在页面 +
--      本次返回的红点补丁，便于回放「为什么角标变了」；
--   ④ 新建 t_ai_memory_event：记忆的**变更流水**（谁、在什么来源下、把哪个键
--      从什么值改成了什么值）。记忆是会被注入提示词的敏感派生数据，
--      只存最终态等于放弃了审计能力。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_ai`;

-- ---------------------------------------------------------
-- ① 长期记忆：结构化快照列
-- ---------------------------------------------------------
SET @has_memory_json := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_user_memory'
      AND COLUMN_NAME='memory_json');
SET @sql := IF(@has_memory_json = 0,
    'ALTER TABLE duoduo_ai.t_ai_user_memory ADD COLUMN memory_json JSON NULL COMMENT ''长期记忆快照（结构化键值对，供提示词注入与用户查看/删除）'' AFTER consent_version',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------
-- ② 会话消息：该轮对话发生时的页面地址
-- ---------------------------------------------------------
SET @has_message_page_url := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_chat_message'
      AND COLUMN_NAME='page_url');
SET @sql := IF(@has_message_page_url = 0,
    'ALTER TABLE duoduo_ai.t_ai_chat_message ADD COLUMN page_url VARCHAR(500) NULL COMMENT ''该轮对话发生时用户所在页面（Web 路径或小程序路径）'' AFTER tool_result',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------
-- ③ 工具调用审计：页面上下文 + 角标补丁
-- ---------------------------------------------------------
SET @has_tool_page_url := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_tool_call_log'
      AND COLUMN_NAME='page_url');
SET @sql := IF(@has_tool_page_url = 0,
    'ALTER TABLE duoduo_ai.t_ai_tool_call_log ADD COLUMN page_url VARCHAR(500) NULL COMMENT ''工具调用发生时用户所在页面'' AFTER result_json',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_badge_patch := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_tool_call_log'
      AND COLUMN_NAME='badge_patch');
SET @sql := IF(@has_badge_patch = 0,
    'ALTER TABLE duoduo_ai.t_ai_tool_call_log ADD COLUMN badge_patch JSON NULL COMMENT ''本次工具调用返回的红点补丁（notification/order/afterSale/support）'' AFTER page_url',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------
-- ④ 记忆变更流水
--     每次写入/覆盖/删除记忆都追加一条，旧值与新值都留档；
--     索引 (user_id, created_at) 支撑「某用户最近的记忆变更」查询。
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ai_memory_event` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID（由应用显式赋值）',
  `user_id` BIGINT NOT NULL COMMENT '记忆归属用户',
  `memory_type` VARCHAR(32) NOT NULL COMMENT '记忆类型：PREFERENCE/PET/PROFILE/BUDGET 等',
  `memory_key` VARCHAR(64) NOT NULL COMMENT '记忆键，如 preferred_species',
  `old_value` JSON NULL COMMENT '变更前的值（首次写入为 NULL）',
  `new_value` JSON NULL COMMENT '变更后的值（删除时为 NULL）',
  `source` VARCHAR(32) NOT NULL COMMENT '来源：CHAT/PROFILE/TOOL/ADMIN/MIGRATION',
  `actor` VARCHAR(32) NOT NULL COMMENT '操作者：USER/SYSTEM/ADMIN',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 记忆变更流水（审计与用户可见的「记忆为什么变了」）';
