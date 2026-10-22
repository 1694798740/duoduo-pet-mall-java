-- ============================================================================
-- 多多宠物商店 · 增量 38：客服已读回执 + 未读筛选（第七十八轮 A 批）
--
-- 目的：
--   ① 会话记录「双方各自读到的最大消息 id」→ 已读/未读无需逐条查询；
--   ② 商家工作台可按未读筛选；点开会话即清零。
--
-- 回填：老会话按历史 read_at 初始化游标。不做这一步，上线瞬间所有历史消息
--       都会被算成「未读 N 条」——数据没坏，但用户会以为系统在说谎。
-- 幂等：ALTER 用 information_schema 判重（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_social`;

SET @has_user_cursor := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_social' AND TABLE_NAME='t_support_session'
      AND COLUMN_NAME='user_last_read_id');
SET @sql := IF(@has_user_cursor = 0,
    'ALTER TABLE t_support_session ADD COLUMN user_last_read_id BIGINT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_seller_cursor := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='duoduo_social' AND TABLE_NAME='t_support_session'
      AND COLUMN_NAME='seller_last_read_id');
SET @sql := IF(@has_seller_cursor = 0,
    'ALTER TABLE t_support_session ADD COLUMN seller_last_read_id BIGINT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填用户侧游标（只回填仍为 0 的，重复执行不会倒退）
UPDATE t_support_session s
    JOIN (
        SELECT m.session_id, MAX(m.id) AS mid
        FROM t_support_message m
            JOIN t_support_session ss ON ss.id = m.session_id
        -- 客服消息表没有 receiver_id：接收方由「谁发的」反推
        -- （用户读的是商家发的，商家读的是用户发的）
        WHERE m.sender_id = ss.seller_id AND m.read_at IS NOT NULL
        GROUP BY m.session_id
    ) x ON x.session_id = s.id
SET s.user_last_read_id = x.mid
WHERE s.user_last_read_id = 0;

-- 回填商家侧游标
UPDATE t_support_session s
    JOIN (
        SELECT m.session_id, MAX(m.id) AS mid
        FROM t_support_message m
            JOIN t_support_session ss ON ss.id = m.session_id
        WHERE m.sender_id = ss.user_id AND m.read_at IS NOT NULL
        GROUP BY m.session_id
    ) x ON x.session_id = s.id
SET s.seller_last_read_id = x.mid
WHERE s.seller_last_read_id = 0;

-- 对账：游标不应大于该会话的最大消息 id
-- SELECT s.id, s.user_last_read_id, s.seller_last_read_id,
--        (SELECT MAX(id) FROM t_support_message m WHERE m.session_id=s.id) AS max_id
-- FROM t_support_session s;
