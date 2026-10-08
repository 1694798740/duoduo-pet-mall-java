-- T2 数据清理脚本（一次性，2026-10-08）
--
-- 目的：按决策 D1「销售账户数据全部删除、从零开始」，清理**第三方商家**相关业务数据。
-- 删除范围：seller_id <> 0（第三方）的商品/闲置/订单/售后/结算，以及 role='SELLER' 的账号及其个人数据。
-- 保留范围：平台自营（seller_id = 0）、买家账号（USER）、平台账号（OPERATOR/ADMIN）、
--          平台配置（类目/品牌/优惠券/秒杀活动）、审计日志。
--
-- 前置：五库备份已完成并通过抽验（scripts/backup/t2_<TS>_*.sql）。
-- 用法：docker exec -i duoduo-mysql mysql -uroot -p<密码> --default-character-set=utf8mb4 < 本文件
--
-- ⚠️ 不可逆。执行前必须确认备份存在且抽验通过。

-- ① 履约域：先删订单维度的物流轨迹（否则订单删了轨迹悬空），再删售后与评价
USE duoduo_fulfilment;
DELETE t FROM t_logistics_track t
  JOIN duoduo_trade.t_order o ON o.order_no = t.order_no
  WHERE o.seller_id <> 0;
SELECT 'fulfilment.t_logistics_track' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_after_sale WHERE seller_id <> 0;
SELECT 'fulfilment.t_after_sale' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_review WHERE seller_id <> 0;
SELECT 'fulfilment.t_review' AS tbl, ROW_COUNT() AS deleted;

-- ② 交易域：按「子表 → 主表」顺序删，避免悬挂引用
USE duoduo_trade;
DELETE FROM t_stock_freeze WHERE order_id IN (SELECT id FROM t_order WHERE seller_id <> 0);
SELECT 'trade.t_stock_freeze' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_order_status_log WHERE order_id IN (SELECT id FROM t_order WHERE seller_id <> 0);
SELECT 'trade.t_order_status_log' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_payment WHERE order_id IN (SELECT id FROM t_order WHERE seller_id <> 0);
SELECT 'trade.t_payment' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE seller_id <> 0);
SELECT 'trade.t_order_item' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_order WHERE seller_id <> 0;
SELECT 'trade.t_order' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_settlement WHERE seller_id <> 0;
SELECT 'trade.t_settlement' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_seller_debt WHERE seller_id <> 0;
SELECT 'trade.t_seller_debt' AS tbl, ROW_COUNT() AS deleted;

-- 购物车里指向第三方商品的条目（属于买家数据，但引用已删商品，必须一并清掉）
DELETE FROM t_cart_item WHERE spu_id IN (SELECT id FROM duoduo_product.t_spu WHERE seller_id <> 0);
SELECT 'trade.t_cart_item(第三方商品)' AS tbl, ROW_COUNT() AS deleted;

-- ③ 商品域：SKU/补货日志 → 闲置意向 → 闲置 → SPU
USE duoduo_product;
DELETE FROM t_sku_restock_log WHERE sku_id IN (
  SELECT s.id FROM t_sku s JOIN t_spu p ON p.id = s.spu_id WHERE p.seller_id <> 0);
SELECT 'product.t_sku_restock_log' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_sku WHERE spu_id IN (SELECT id FROM t_spu WHERE seller_id <> 0);
SELECT 'product.t_sku' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_listing_want WHERE listing_id IN (SELECT id FROM t_listing WHERE seller_id <> 0);
SELECT 'product.t_listing_want' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_listing WHERE seller_id <> 0;
SELECT 'product.t_listing' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_spu WHERE seller_id <> 0;
SELECT 'product.t_spu' AS tbl, ROW_COUNT() AS deleted;

-- ④ 用户域：先删令牌与个人数据，再删商家账号本身
USE duoduo_user;
DELETE FROM t_refresh_token WHERE user_id IN (SELECT id FROM t_user WHERE role = 'SELLER');
SELECT 'user.t_refresh_token(SELLER)' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_pet_profile WHERE user_id IN (SELECT id FROM t_user WHERE role = 'SELLER');
SELECT 'user.t_pet_profile(SELLER)' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_user_address WHERE user_id IN (SELECT id FROM t_user WHERE role = 'SELLER');
SELECT 'user.t_user_address(SELLER)' AS tbl, ROW_COUNT() AS deleted;

DELETE FROM t_user WHERE role = 'SELLER';
SELECT 'user.t_user(SELLER)' AS tbl, ROW_COUNT() AS deleted;

-- ⑤ 删后复核：第三方口径应全为 0；平台自营与平台账号必须保持不变
SELECT '复核 product.t_spu(第三方) 应为 0' AS chk, COUNT(*) AS n FROM duoduo_product.t_spu WHERE seller_id <> 0;
SELECT '复核 product.t_listing(第三方) 应为 0' AS chk, COUNT(*) AS n FROM duoduo_product.t_listing WHERE seller_id <> 0;
SELECT '复核 trade.t_order(第三方) 应为 0' AS chk, COUNT(*) AS n FROM duoduo_trade.t_order WHERE seller_id <> 0;
SELECT '复核 trade.t_settlement(第三方) 应为 0' AS chk, COUNT(*) AS n FROM duoduo_trade.t_settlement WHERE seller_id <> 0;
SELECT '复核 fulfilment.t_after_sale(第三方) 应为 0' AS chk, COUNT(*) AS n FROM duoduo_fulfilment.t_after_sale WHERE seller_id <> 0;
SELECT '复核 user.SELLER 账号 应为 0' AS chk, COUNT(*) AS n FROM duoduo_user.t_user WHERE role = 'SELLER';
SELECT '保留 product.t_spu(自营 seller_id=0)' AS keep, COUNT(*) AS n FROM duoduo_product.t_spu WHERE seller_id = 0;
SELECT '保留 user.OPERATOR 账号' AS keep, COUNT(*) AS n FROM duoduo_user.t_user WHERE role = 'OPERATOR';
SELECT '保留 user.USER 账号' AS keep, COUNT(*) AS n FROM duoduo_user.t_user WHERE role = 'USER';
