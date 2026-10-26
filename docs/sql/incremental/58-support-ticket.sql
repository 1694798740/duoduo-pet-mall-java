-- 58 · 客服工单认领 / 转交 / 审计（C-04，2026-10-26）
--
-- 目的：多店员共用一个店铺客服工作台时，「谁在接待这个买家」必须可见、可认领、可转交、可追溯。
--   * t_support_session 增加 assignee_id / assigned_at：当前处理人（NULL = 未认领）。
--     认领用条件 UPDATE（assignee_id IS NULL）保证并发只有一个人成功；
--   * t_support_ticket_log 追加式记录 CLAIM / TRANSFER / RELEASE 三类动作，
--     带动作前后的处理人、操作者与备注 —— 「转交留痕、审计可查」的落点。
--
-- 幂等：列存在则跳过 ADD COLUMN（与 43 同一写法）。

SET @has_assignee := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'duoduo_social' AND TABLE_NAME = 't_support_session' AND COLUMN_NAME = 'assignee_id'
);
SET @ddl := IF(@has_assignee = 0,
  'ALTER TABLE `duoduo_social`.`t_support_session` ADD COLUMN `assignee_id` BIGINT NULL DEFAULT NULL COMMENT ''当前处理人 userId；NULL=未认领（C-04）'' AFTER `closed_at`',
  'SELECT ''assignee_id already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_assigned_at := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'duoduo_social' AND TABLE_NAME = 't_support_session' AND COLUMN_NAME = 'assigned_at'
);
SET @ddl2 := IF(@has_assigned_at = 0,
  'ALTER TABLE `duoduo_social`.`t_support_session` ADD COLUMN `assigned_at` timestamp NULL DEFAULT NULL COMMENT ''最近一次认领 / 转交时间（UTC）'' AFTER `assignee_id`',
  'SELECT ''assigned_at already exists''');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

CREATE TABLE IF NOT EXISTS `duoduo_social`.`t_support_ticket_log` (
    id              BIGINT      NOT NULL COMMENT '雪花 id（同时是时间线顺序）',
    session_id      BIGINT      NOT NULL COMMENT 't_support_session.id',
    action          VARCHAR(20) NOT NULL COMMENT 'CLAIM / TRANSFER / RELEASE',
    from_assignee   BIGINT      NULL COMMENT '动作前处理人；NULL=未认领',
    to_assignee     BIGINT      NULL COMMENT '动作后处理人；RELEASE 为 NULL',
    actor_id        BIGINT      NOT NULL COMMENT '操作者 userId',
    actor_role      VARCHAR(20) NOT NULL COMMENT 'OWNER / STAFF',
    note            VARCHAR(200) NULL COMMENT '操作备注（转交原因等）',
    created_at      TIMESTAMP   NOT NULL COMMENT '动作时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_ticket_log_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服工单处理记录（C-04，append-only）';
