-- E26 · 全部删号清理（2026-10-08，用户裁定口径）
--
-- 范围：
--   删：t_user 中除平台主运营账号 13684295254 外的全部账号（321 个，
--       全部为历轮 e2e 以「随机手机号 + 库提权」创建的夹具账号，含 77 个遗留 OPERATOR）
--       及其全部关联数据（下列各表均为夹具测试数据，无真实业务留档）。
--   留：主运营账号本体；基础主数据（SPU/SKU/类目/品牌/入库日志、券模板、秒杀活动、
--       话题字典 t_share_topic、调度锁表）。
-- 前置：六库已全量备份至 scripts/backup/e26-clean-*-20261008.sql 并抽验通过；
--       库间无物理外键（information_schema.referential_constraints = 0），可安全 TRUNCATE。
-- 执行：逐表执行并记录影响行数；删后双向复核（目标表为 0，保留表与执行前一致）。

-- ---------- user 库 ----------
TRUNCATE TABLE duoduo_user.t_refresh_token;   -- 910
TRUNCATE TABLE duoduo_user.t_user_address;    -- 16
TRUNCATE TABLE duoduo_user.t_pet_profile;     -- 0
TRUNCATE TABLE duoduo_user.t_audit_log;       -- 0
DELETE FROM duoduo_user.t_user WHERE phone <> '13684295254' OR phone IS NULL;  -- 321

-- ---------- trade 库 ----------
TRUNCATE TABLE duoduo_trade.t_cart_item;          -- 6
TRUNCATE TABLE duoduo_trade.t_stock_freeze;       -- 160
TRUNCATE TABLE duoduo_trade.t_order_place_task;   -- 28
TRUNCATE TABLE duoduo_trade.t_order_status_log;   -- 550
TRUNCATE TABLE duoduo_trade.t_payment;            -- 124
TRUNCATE TABLE duoduo_trade.t_settlement;         -- 121
TRUNCATE TABLE duoduo_trade.t_seckill_order;      -- 0
TRUNCATE TABLE duoduo_trade.t_order_item;         -- 201
TRUNCATE TABLE duoduo_trade.t_order;              -- 198

-- ---------- product 库 ----------
TRUNCATE TABLE duoduo_product.t_listing_want;     -- 0
TRUNCATE TABLE duoduo_product.t_listing;          -- 20（闲置，卖家全是夹具账号）

-- ---------- fulfilment 库 ----------
TRUNCATE TABLE duoduo_fulfilment.t_logistics_track;     -- 138624（全部挂在测试订单下）
TRUNCATE TABLE duoduo_fulfilment.t_share_comment_like;  -- 0
TRUNCATE TABLE duoduo_fulfilment.t_share_favorite;      -- 0
TRUNCATE TABLE duoduo_fulfilment.t_share_like;          -- 6
TRUNCATE TABLE duoduo_fulfilment.t_share_comment;       -- 6
TRUNCATE TABLE duoduo_fulfilment.t_share_topic_rel;     -- 40
TRUNCATE TABLE duoduo_fulfilment.t_share_report;        -- 8
TRUNCATE TABLE duoduo_fulfilment.t_product_share;       -- 14
TRUNCATE TABLE duoduo_fulfilment.t_review;              -- 0
TRUNCATE TABLE duoduo_fulfilment.t_after_sale;          -- 10

-- ---------- social 库 ----------
TRUNCATE TABLE duoduo_social.t_social_notice_read;  -- 352
TRUNCATE TABLE duoduo_social.t_social_notice;       -- 691
TRUNCATE TABLE duoduo_social.t_social_message;      -- 334
TRUNCATE TABLE duoduo_social.t_social_follow;       -- 36

-- ---------- marketing 库 ----------
TRUNCATE TABLE duoduo_marketing.t_coupon;           -- 62（用户领券记录；券模板保留）

-- ---------- 复核 ----------
SELECT '复核：剩余账号数（应为 1）' AS item, COUNT(*) AS v FROM duoduo_user.t_user
UNION ALL SELECT '复核：主运营在位（应为 1）', COUNT(*) FROM duoduo_user.t_user WHERE phone='13684295254'
UNION ALL SELECT '复核：刷新令牌（应为 0）', COUNT(*) FROM duoduo_user.t_refresh_token
UNION ALL SELECT '复核：订单（应为 0）', COUNT(*) FROM duoduo_trade.t_order
UNION ALL SELECT '复核：物流轨迹（应为 0）', COUNT(*) FROM duoduo_fulfilment.t_logistics_track
UNION ALL SELECT '复核：私信（应为 0）', COUNT(*) FROM duoduo_social.t_social_message
UNION ALL SELECT '复核：闲置（应为 0）', COUNT(*) FROM duoduo_product.t_listing
UNION ALL SELECT '复核：领券（应为 0）', COUNT(*) FROM duoduo_marketing.t_coupon
UNION ALL SELECT '复核：售后（应为 0）', COUNT(*) FROM duoduo_fulfilment.t_after_sale
UNION ALL SELECT '保留：自营 SPU（应为 2）', COUNT(*) FROM duoduo_product.t_spu
UNION ALL SELECT '保留：类目（应为 24）', COUNT(*) FROM duoduo_product.t_category
UNION ALL SELECT '保留：券模板（应为 2）', COUNT(*) FROM duoduo_marketing.t_coupon_template
UNION ALL SELECT '保留：秒杀活动（应为 1）', COUNT(*) FROM duoduo_trade.t_seckill_activity
UNION ALL SELECT '保留：话题字典（应为 24）', COUNT(*) FROM duoduo_fulfilment.t_share_topic;
