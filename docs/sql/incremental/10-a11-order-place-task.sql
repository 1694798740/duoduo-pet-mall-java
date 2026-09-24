-- A11 · 削峰下单任务台账（存量库增量脚本）
-- 幂等风格（T-46 判据）：CREATE TABLE IF NOT EXISTS，全新表，与从零 DDL 不冲突。
-- 重复执行报 1060/1050 属预期（幂等语义见 08 篇 T-46）。
USE duoduo_trade;

CREATE TABLE IF NOT EXISTS t_order_place_task (
    id             BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    idempotent_key VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    fail_reason    VARCHAR(200) NULL,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_place_task_user_idem (user_id, idempotent_key),
    KEY idx_place_task_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
