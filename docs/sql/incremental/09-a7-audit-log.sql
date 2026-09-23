-- A7 · 运营审计日志 建表（幂等，可重复执行）
-- 判据（T-46 教训）：t_audit_log 为全新表，00~05 从零建库脚本均未创建过它，
-- 与从零 DDL（01-duoduo-user.sql，已同步）不会冲突，可安全进入 initdb.d 挂载目录。
SET NAMES utf8mb4;
USE `duoduo_user`;

CREATE TABLE IF NOT EXISTS `t_audit_log` (
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `actor_id`    BIGINT       NOT NULL COMMENT '操作人用户 ID',
    `actor_phone` VARCHAR(20)  NOT NULL COMMENT '操作人手机号（审计追责用）',
    `action`      VARCHAR(50)  NOT NULL COMMENT '动作标识（如 USER_ROLE_CHANGE）',
    `target_id`   BIGINT       NOT NULL COMMENT '目标对象 ID（如被改角色的用户）',
    `detail`      VARCHAR(500) NOT NULL COMMENT '变更明细（old -> new）',
    `created_at`  TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_audit_actor` (`actor_id`),
    KEY `idx_audit_target` (`target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运营审计日志';

SELECT 'DDL-OK' AS result, COUNT(*) AS audit_table_rows
FROM information_schema.tables
WHERE table_schema = 'duoduo_user' AND table_name = 't_audit_log';
