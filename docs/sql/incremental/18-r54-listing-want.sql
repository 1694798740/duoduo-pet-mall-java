-- ============================================================
-- 18 · 闲置「想要」统计（第五十四轮）
--
-- 背景：卡片上的计数过去只有一个 view_count（浏览次数）。而「想要」是**意向**，
-- 与「看过的次数」不是一回事 —— 拿浏览量当想要人数会把「路过看一眼」也算成需求，
-- 卖家据此判断热度会失真。本轮把两个口径彻底分开：
--   * view_count  = 浏览次数：页面每被打开或刷新一次 +1，同一人可累加（已有）
--   * want_count  = 想要人数：按**咨询行为**统计，每位买家对同一件闲置只计 1 次（本轮新增）
--
-- 去重不做在应用层：应用层「先查有没有、没有再加一」在并发下会重复计数。
-- 这里交给数据库唯一键 —— t_listing_want 的主键 (listing_id, buyer_id) 就是「一人一次」的
-- 硬约束，上报走 INSERT IGNORE：只有真的插进去了才给 t_listing.want_count 加 1，天然幂等。
--
-- 执行方式（不用重建数据卷）：
--   docker cp docs/sql/incremental/18-r54-listing-want.sql duoduo-mysql:/tmp/18.sql
--   docker exec duoduo-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -pduoduo_root < /tmp/18.sql'
-- 从零重建则由 02-duoduo-product.sql 直接带上（两处必须保持一致）。
-- ============================================================

USE `duoduo_product`;

ALTER TABLE t_listing
    ADD COLUMN want_count INT NOT NULL DEFAULT 0 AFTER view_count;

CREATE TABLE IF NOT EXISTS t_listing_want (
    listing_id BIGINT    NOT NULL,
    buyer_id   BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    -- 主键即去重键：既然要求「同一买家对同一闲置只记一次」，就不要给应用层留判断空间
    PRIMARY KEY (listing_id, buyer_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
