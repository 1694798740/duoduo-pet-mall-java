SET NAMES utf8mb4;
USE `duoduo_product`;

-- A-17 · 商品曝光 / 点击每日统计（2026-10-26）
-- 为什么独立成表而不是在 t_spu 上加两列：
--   1) 诊断需要「近 N 天」窗口，加列只能记累计值或最近一天，窗口一旦变化就失真；
--   2) 曝光/点击是高频写、低频读，独立表按 (spu_id, stat_date) 主键做 UPSERT，
--      不把高频计数写进商品主档，避免每次浏览都去争抢同一行、顺带刷新 updated_at。
-- 口径：
--   exposure = 商品卡出现在公开列表 / 搜索 / 店铺页 / 首页（同一请求同一商品只记 1 次）；
--   clicks   = 公开商品详情打开成功（过审 + 在售）后才记，404 不计入。
CREATE TABLE IF NOT EXISTS t_product_daily_stat (
    spu_id     BIGINT NOT NULL COMMENT 't_spu.id',
    stat_date  DATE   NOT NULL COMMENT '统计日期（UTC）',
    exposure   BIGINT NOT NULL DEFAULT 0 COMMENT '当日曝光次数',
    clicks     BIGINT NOT NULL DEFAULT 0 COMMENT '当日点击次数',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近写入时间（UTC）',
    PRIMARY KEY (spu_id, stat_date),
    KEY idx_stat_date (stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品每日曝光/点击统计（A-17）';
