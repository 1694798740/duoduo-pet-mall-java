-- T2b 清理脚本（一次性，2026-10-08）
-- 目的：按用户要求删除**全部测试账号**（买家 USER + 测试运营 OPERATOR），只保留平台主运营账号。
--
-- 保留（必须）：
--   · 平台主运营账号：phone = '13684295254'（userId 363903492038987776，进后台的唯一入口）
--   · 平台自营商品/订单/售后、类目/品牌/券/秒杀活动等平台配置
--   · 审计日志 t_audit_log（留痕，不随账号删除）
--
-- 一并清理（因为这些账号被删后会变成"作者已不存在"的悬空内容）：
--   买家发布的分享、其评论/点赞/收藏、举报记录、购物车；以及其令牌/宠物档案/收货地址。
--   ⚠️ 订单（含收货人快照 receiver_name/phone/address）**保留** —— 订单是交易凭证，且快照可独立渲染。
--
-- 前置：本批备份已完成（scripts/backup/t2b_<TS>_*.sql）并通过抽验。
-- ⚠️ 不可逆。

USE duoduo_user;

-- ① 圈定"待删账号"（买家全部 + 除平台主账号外的运营）到临时表，供跨库使用
DROP TABLE IF EXISTS t_doomed_ids;
CREATE TABLE t_doomed_ids AS
  SELECT id FROM t_user
  WHERE role = 'USER'
     OR (role = 'OPERATOR' AND phone <> '13684295254');
SELECT '待删账号数' AS tbl, COUNT(*) AS n FROM t_doomed_ids;

-- ② 履约域：买家作者内容（子表 → 主表）
USE duoduo_fulfilment;

DELETE l FROM t_share_comment_like l JOIN duoduo_user.t_doomed_ids d ON d.id = l.user_id;
SELECT 'fulfilment.t_share_comment_like' AS tbl, ROW_COUNT() AS deleted;

DELETE l FROM t_share_like l JOIN duoduo_user.t_doomed_ids d ON d.id = l.user_id;
SELECT 'fulfilment.t_share_like' AS tbl, ROW_COUNT() AS deleted;

DELETE f FROM t_share_favorite f JOIN duoduo_user.t_doomed_ids d ON d.id = f.user_id;
SELECT 'fulfilment.t_share_favorite' AS tbl, ROW_COUNT() AS deleted;

DELETE c FROM t_share_comment c JOIN duoduo_user.t_doomed_ids d ON d.id = c.user_id;
SELECT 'fulfilment.t_share_comment' AS tbl, ROW_COUNT() AS deleted;

DELETE r FROM t_share_report r JOIN duoduo_user.t_doomed_ids d ON d.id = r.reporter_id;
SELECT 'fulfilment.t_share_report' AS tbl, ROW_COUNT() AS deleted;

DELETE s FROM t_product_share s JOIN duoduo_user.t_doomed_ids d ON d.id = s.user_id;
SELECT 'fulfilment.t_product_share' AS tbl, ROW_COUNT() AS deleted;

-- 兜底：清理挂在已删分享/评论下的残留（悬空的评论与点赞）
DELETE c FROM t_share_comment c LEFT JOIN t_product_share s ON s.id = c.share_id WHERE s.id IS NULL;
SELECT 'fulfilment.t_share_comment(悬空兜底)' AS tbl, ROW_COUNT() AS deleted;

DELETE l FROM t_share_comment_like l LEFT JOIN t_share_comment c ON c.id = l.comment_id WHERE c.id IS NULL;
SELECT 'fulfilment.t_share_comment_like(悬空兜底)' AS tbl, ROW_COUNT() AS deleted;

DELETE l FROM t_share_like l LEFT JOIN t_product_share s ON s.id = l.share_id WHERE s.id IS NULL;
SELECT 'fulfilment.t_share_like(悬空兜底)' AS tbl, ROW_COUNT() AS deleted;

DELETE f FROM t_share_favorite f LEFT JOIN t_product_share s ON s.id = f.share_id WHERE s.id IS NULL;
SELECT 'fulfilment.t_share_favorite(悬空兜底)' AS tbl, ROW_COUNT() AS deleted;

-- ③ 交易域：购物车（账号个人数据）
USE duoduo_trade;

DELETE c FROM t_cart_item c JOIN duoduo_user.t_doomed_ids d ON d.id = c.user_id;
SELECT 'trade.t_cart_item' AS tbl, ROW_COUNT() AS deleted;

-- ④ 用户域：个人数据 → 账号本身
USE duoduo_user;

DELETE r FROM t_refresh_token r JOIN t_doomed_ids d ON d.id = r.user_id;
SELECT 'user.t_refresh_token' AS tbl, ROW_COUNT() AS deleted;

DELETE p FROM t_pet_profile p JOIN t_doomed_ids d ON d.id = p.user_id;
SELECT 'user.t_pet_profile' AS tbl, ROW_COUNT() AS deleted;

DELETE a FROM t_user_address a JOIN t_doomed_ids d ON d.id = a.user_id;
SELECT 'user.t_user_address' AS tbl, ROW_COUNT() AS deleted;

DELETE u FROM t_user u JOIN t_doomed_ids d ON d.id = u.id;
SELECT 'user.t_user' AS tbl, ROW_COUNT() AS deleted;

-- ⑤ 复核：剩余账号（应只剩平台主运营账号）
SELECT '剩余账号总数' AS chk, COUNT(*) AS n FROM t_user;
SELECT '剩余账号明细' AS chk, id, phone, nickname, role FROM t_user;

-- 保留项必须未受影响
SELECT '保留 自营SPU' AS keep, COUNT(*) AS n FROM duoduo_product.t_spu WHERE seller_id = 0;
SELECT '保留 自营订单' AS keep, COUNT(*) AS n FROM duoduo_trade.t_order WHERE seller_id = 0;
SELECT '保留 自营售后' AS keep, COUNT(*) AS n FROM duoduo_fulfilment.t_after_sale WHERE seller_id = 0;
SELECT '保留 类目/品牌' AS keep, COUNT(*) AS n FROM duoduo_product.t_category;

-- 清理临时表
DROP TABLE IF EXISTS t_doomed_ids;
