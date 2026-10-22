-- ============================================================================
-- 多多宠物商店 · 增量 40：收藏夹与浏览足迹（第七十九轮 C 批）
-- 用途：客服/私信的「发送商品」菜单需要四个来源：当前浏览 / 购物车 / 收藏夹 / 已购买。
--       购物车与已购买已有数据，收藏与足迹本轮新建。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS t_product_favorite (
    id         BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    spu_id     BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_favorite (user_id, spu_id),
    KEY idx_favorite_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品收藏夹';

CREATE TABLE IF NOT EXISTS t_product_view (
    id        BIGINT    NOT NULL,
    user_id   BIGINT    NOT NULL,
    spu_id    BIGINT    NOT NULL,
    viewed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_view (user_id, spu_id),
    KEY idx_view_time (user_id, viewed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品浏览足迹（同一商品只留最近一次）';
