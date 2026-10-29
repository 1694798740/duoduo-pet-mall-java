SET NAMES utf8mb4;
USE `duoduo_user`;

-- E-06 · 商家端 IP 白名单（2026-10-26）
--
-- 为什么挂在店铺（t_merchant）而不是账号（t_user）：白名单是「这家店的经营场所网络」，
-- 店主与店员在同一间办公室办公，按账号各配一套只会让新店员默认被挡在门外。
-- 平台自营（owner 为运营）同样可以用，不影响买家与运营后台。
--
-- 权威值在这里（可审计、重启不丢、与变更同事务），网关读的是 Redis 副本
-- （键 duoduo:merchant:ipwl:{merchantId}）：网关不连业务库，每个商家端请求查库会把网关绑死。
-- 副本在保存时写入、在服务启动时重发一次，避免「Redis 被清空后保护静默消失」。
ALTER TABLE `t_merchant`
    ADD COLUMN `ip_whitelist_enabled` TINYINT NOT NULL DEFAULT 0
        COMMENT '是否启用商家端 IP 白名单（E-06）；0=不限制（默认）'
        AFTER `violation_count`,
    ADD COLUMN `ip_whitelist` VARCHAR(500) NULL
        COMMENT '允许访问商家端的来源地址，逗号分隔；支持 1.2.3.4 / 10.0.0.0/8 / 192.168.1.*（E-06）'
        AFTER `ip_whitelist_enabled`;
