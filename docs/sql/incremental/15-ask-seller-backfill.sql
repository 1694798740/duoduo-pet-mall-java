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

UPDATE t_spu
SET seller_id = (SELECT id FROM duoduo_user.t_user WHERE phone = '13550931548' AND role = 'SELLER')
WHERE id IN (1009, 1010);

UPDATE t_spu
SET seller_id = (SELECT id FROM duoduo_user.t_user WHERE phone = '13765569756' AND role = 'SELLER')
WHERE id IN (1015, 1017, 1018);
