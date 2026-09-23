-- =============================================================================
-- A13 · 微信登录绑定（增量 DDL，可重放）
-- 说明：
--   * MySQL 不支持 ADD COLUMN IF NOT EXISTS —— 重复执行报 1060「Duplicate column name」
--     属预期（与 06-a5-coupon-columns.sql 同一口径），按幂等迁移工具或忽略 1060 处理。
--   * t_user 已在 01-duoduo-user.sql 建表；本脚本用于**已初始化的存量库**增量升级。
-- 安全：
--   * wx_openid 可空：手机号注册的用户在绑定前为 NULL；
--   * 唯一索引 uk_user_wxopenid 兜底「并发把同一微信绑到两个账号」——INV-1（幂等/唯一性靠索引）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `t_user`
    ADD COLUMN `wx_openid` VARCHAR(64) DEFAULT NULL COMMENT '微信 openid（A13，绑定后写入；未绑定为 NULL）' AFTER `id_card_masked`;

ALTER TABLE `t_user`
    ADD UNIQUE KEY `uk_user_wxopenid` (`wx_openid`);
