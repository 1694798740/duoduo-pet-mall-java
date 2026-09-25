-- ============================================================================
-- A14 · 限时秒杀：交易库增量 DDL（对已有环境执行；全新环境直接跑 03 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_trade`;

CREATE TABLE IF NOT EXISTS t_seckill_activity (
    id          BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL,
    start_at    TIMESTAMP    NOT NULL,
    end_at      TIMESTAMP    NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_seckill_act_start ON t_seckill_activity (start_at);

CREATE TABLE IF NOT EXISTS t_seckill_sku (
    id              BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    seckill_price   BIGINT       NOT NULL,
    original_price  BIGINT       NOT NULL,
    total_stock     INT          NOT NULL,
    available_stock INT          NOT NULL,
    per_limit       INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_seckill_sku_act_sku ON t_seckill_sku (activity_id, sku_id);
CREATE INDEX idx_seckill_sku_sku ON t_seckill_sku (sku_id);

CREATE TABLE IF NOT EXISTS t_seckill_order (
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    seckill_sku_id  BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    order_no        VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_seckill_order_no ON t_seckill_order (order_no);
CREATE INDEX idx_seckill_order_user ON t_seckill_order (user_id, activity_id);
