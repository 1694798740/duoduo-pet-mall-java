-- 43 · 客服会话生命周期（2026-10-23）
--
-- 目的：
--   1. 会话「关闭时刻」需要独立字段 —— 30 天清理的判据是「关闭时间 + 30 天且期间无消息」，
--      不能拿 last_message_at 代替（关会话这个动作本身不产生消息，两者必然不等）；
--   2. 自动关闭（双方 24h 无消息）与清理扫描都按 (status, last_message_at) 过滤，补一条复合索引。
--
-- 幂等：可重复执行（列存在则跳过 ADD COLUMN 会报错，所以用 information_schema 判断后动态执行）。

SET @has_closed_at := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'duoduo_social' AND TABLE_NAME = 't_support_session' AND COLUMN_NAME = 'closed_at'
);
SET @ddl := IF(@has_closed_at = 0,
  'ALTER TABLE `duoduo_social`.`t_support_session` ADD COLUMN `closed_at` timestamp NULL DEFAULT NULL COMMENT ''关闭时刻（仅客服侧可见；买家发消息会清空）'' AFTER `status`',
  'SELECT ''closed_at already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'duoduo_social' AND TABLE_NAME = 't_support_session'
    AND INDEX_NAME = 'idx_support_session_status_last'
);
SET @ddl2 := IF(@has_idx = 0,
  'ALTER TABLE `duoduo_social`.`t_support_session` ADD KEY `idx_support_session_status_last` (`status`, `last_message_at`)',
  'SELECT ''idx already exists''');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
