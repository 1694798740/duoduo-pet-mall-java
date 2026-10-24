-- 47 · 在途冻结补登记为订单级预占（B-03 升级修复脚本）
--
-- 适用环境：已有交易数据、且 t_stock_freeze 里仍有 status='FROZEN' 的订单。
-- 本地新环境若查询结果为 0 行，本脚本不产生任何变更。
--
-- 为什么必须执行：
--   B-03 之后，商品服务的释放 / 扣减只认 t_sku_stock_reservation。
--   升级前已冻结、但没补登记的订单：
--     · 取消 → 商品域查不到预占，按「幂等成功」返回，**全局 frozen_stock 不会被释放**；
--     · 支付 → 扣减返回 RESERVATION_NOT_FOUND，支付事务回滚，用户付不了款。
--
-- 执行顺序（务必在维护窗口内）：
--   1) 执行 incremental/47-stock-order-reservation.sql 建表；
--   2) 部署交易服务（新请求会带 orderId，旧商品服务会忽略该字段，行为不变）；
--   3) 停止下单入口（或确认无新订单），执行本脚本；
--   4) 部署商品服务；
--   5) 跑 scripts/check-stock-freeze-recon.mjs 确认两域一致。
--
-- 可重复执行：INSERT IGNORE + 主键 (order_id, sku_id)。
-- 只读交易库、写商品库，不在应用代码里跨库；本脚本是一次性运维修复，不是运行时依赖。
SET NAMES utf8mb4;

-- 0) 前置盘点：交易域在途冻结（按 SKU 汇总）
SELECT f.sku_id, SUM(f.quantity) AS trade_frozen_qty, COUNT(*) AS order_rows
FROM duoduo_trade.t_stock_freeze f
WHERE f.status = 'FROZEN'
GROUP BY f.sku_id;

-- 1) 补登记（唯一索引与交易台账的 (sku_id, order_id) 一一对应）
INSERT IGNORE INTO duoduo_product.t_sku_stock_reservation
    (order_id, sku_id, quantity, status, created_at, updated_at)
SELECT f.order_id, f.sku_id, f.quantity, 'FROZEN', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM duoduo_trade.t_stock_freeze f
WHERE f.status = 'FROZEN';

-- 2) 事后核对 A：交易域 FROZEN 是否都有对应预占行（期望 0 行）
SELECT f.order_id, f.sku_id, f.quantity
FROM duoduo_trade.t_stock_freeze f
LEFT JOIN duoduo_product.t_sku_stock_reservation r
       ON r.order_id = f.order_id AND r.sku_id = f.sku_id
WHERE f.status = 'FROZEN'
  AND (r.order_id IS NULL OR r.status <> 'FROZEN' OR r.quantity <> f.quantity);

-- 3) 事后核对 B：每个 SKU 的预占 FROZEN 汇总是否等于 t_sku.frozen_stock（期望 0 行）
SELECT s.id, s.frozen_stock, COALESCE(r.qty, 0) AS reserved_qty
FROM duoduo_product.t_sku s
LEFT JOIN (
    SELECT sku_id, SUM(quantity) AS qty
    FROM duoduo_product.t_sku_stock_reservation
    WHERE status = 'FROZEN'
    GROUP BY sku_id
) r ON r.sku_id = s.id
WHERE COALESCE(s.frozen_stock, 0) <> COALESCE(r.qty, 0);
