-- A5 优惠券 · 增量迁移（对已存在的 duoduo_trade 库执行）
--
-- 为什么单独一个文件而不是只改 03-duoduo-trade.sql：
--   docs/sql/03 是「从零建库」的 DDL，只对空库生效；而开发环境的数据在 Docker 卷里，
--   重跑 CREATE TABLE 不会改动已经存在的表。增量列必须显式 ALTER，
--   否则本地跑起来会报 Unknown column 'coupon_code' —— 而 03 文件看起来「明明有这一列」，
--   排查方向会被彻底带偏。
--
-- 幂等写法说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，
--  因此重复执行会报 1060 Duplicate column name。这是**预期**的：
--  迁移脚本宁可「第二次执行报错并停下」，也不要静默做别的事。
--  需要判断是否已执行时，先跑：
--    SELECT COLUMN_NAME FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA='duoduo_trade' AND TABLE_NAME='t_order' AND COLUMN_NAME='coupon_code';

USE duoduo_trade;

ALTER TABLE t_order
    ADD COLUMN coupon_code VARCHAR(32) NULL COMMENT '本单使用的优惠券券码（未用券为 NULL）' AFTER discount_amount;
