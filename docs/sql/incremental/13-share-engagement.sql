-- ============================================================================
-- 第四十四轮 · 分享圈互动（点赞 / 收藏）：履约库增量 DDL
-- （对已有环境执行；全新环境直接跑 04 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- 计数列：冗余在分享行上（信息流是高频读路径，不逐行 COUNT）。
-- 唯一键表是事实源，计数列只是读优化；回退扣减用 GREATEST(0, n-1) 防负漂移。
ALTER TABLE t_product_share
    ADD COLUMN like_count     INT NOT NULL DEFAULT 0,
    ADD COLUMN favorite_count INT NOT NULL DEFAULT 0;

-- 点赞（一人一分享一票：复合主键即唯一约束，并发重复插入撞键幂等）
CREATE TABLE IF NOT EXISTS t_share_like (
    share_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (share_id, user_id)
);
CREATE INDEX idx_share_like_user ON t_share_like (user_id);

-- 收藏（与点赞同构；收藏属本人资产，按 user 查「我的收藏」走 idx_user）
CREATE TABLE IF NOT EXISTS t_share_favorite (
    share_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (share_id, user_id)
);
CREATE INDEX idx_share_fav_user ON t_share_favorite (user_id);
