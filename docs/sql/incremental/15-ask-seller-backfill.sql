-- ============================================================================
-- 多多宠物商店 · 增量 15（第四十七轮）：商家商品回填卖家归属
-- 背景：t_spu.seller_id 列默认 0（= 平台自营），种子 INSERT 未包含该列，
--       导致 merchant 供货类型的商品也没有归属卖家 —— 「问一问」（ASK 消息）
--       依赖商品归属卖家定收件人，seller_id=0 时一律 422「该商品暂无卖家」。
-- 方案：把 5 个 merchant 商品的 seller_id 回填到种子卖家（按手机号定位，
--       不硬编码雪花 ID，跨环境可重放；执行前提：用户域种子已入库）。
-- 对已有环境执行；全新环境跑完 01（用户种子）+ 02（商品种子）后同样执行本文件。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_product`;

-- ★ 用 JOIN 而不是子查询：子查询查不到人返回 NULL，而 seller_id 是 NOT NULL，
--   会把「全新初始化」直接打断（ERROR 1048 Column 'seller_id' cannot be null）。
--   JOIN 的语义是「有账号就回填、没有就跳过」，幂等可重放。与 02-…product.sql 同一口径。
UPDATE t_spu s
JOIN duoduo_user.t_user u ON u.phone = '13550931548' AND u.role = 'SELLER'
SET s.seller_id = u.id
WHERE s.id IN (1009, 1010);

UPDATE t_spu s
JOIN duoduo_user.t_user u ON u.phone = '13765569756' AND u.role = 'SELLER'
SET s.seller_id = u.id
WHERE s.id IN (1015, 1017, 1018);
