-- 一次性修数：2026-10-22 支付成功但订单不入账（订单 202610221304559969466）
--
-- 现场（修复前实查）：
--   t_order        371645096950640640  status=PENDING_PAYMENT  pay_amount=14880
--   t_payment      两张 ALIPAY_PC 均为 SUCCESS（用户点了两次支付，钱收了两笔）
--                  202610221305078190313 / 202610221308521149245
--   t_stock_freeze 3010×2、3020×1，status=FROZEN
--   t_sku          frozen_stock 均为 0（被幂等撞车补偿释放掉了）
--
-- 期望的终态：
--   订单 PAID；保留的那笔支付单 SUCCESS；另一笔 CLOSED 并注明「重复支付需退款」；
--   台账 FROZEN→DEDUCTED；frozen_stock 回到 0；stock 分别 -2 / -1。
--
-- 做法：**不手工改订单状态**。只把两个来源的数据摆回自洽，
--       然后让交易域自己的渠道查询兜底（channelQuerySync）走完整入账链路 ——
--       托管、券核销、状态日志、通知全都照常发生。手写 UPDATE 把订单改成已付款
--       会跳过这些副作用，账面「已支付」但托管单不存在，退款时立刻穿帮。
--
-- 可重复执行：所有步骤都带前置条件，已修过则影响 0 行。

-- 0) 现场核对（修之前先看，确认与预期一致再往下）
SELECT 'before' AS stage, id, order_no, status, pay_amount FROM t_order WHERE id = 371645096950640640;
SELECT 'before' AS stage, sku_id, quantity, status FROM t_stock_freeze WHERE order_id = 371645096950640640;
SELECT 'before' AS stage, id, pay_no, status, channel_trade_no FROM t_payment WHERE order_id = 371645096950640640 ORDER BY created_at;

-- 1) 补回被误释放的冻结量：只补「台账 FROZEN 比商品域多出来的那部分」，
--    并保证补完不在售罄商品上凭空造出负可售。
UPDATE duoduo_product.t_sku s
JOIN (
    SELECT f.sku_id, SUM(f.quantity) AS qty
    FROM t_stock_freeze f
    JOIN t_order o ON o.id = f.order_id
    WHERE o.id = 371645096950640640 AND f.status = 'FROZEN'
    GROUP BY f.sku_id
) need ON need.sku_id = s.id
SET s.frozen_stock = COALESCE(s.frozen_stock, 0) + need.qty
WHERE COALESCE(s.frozen_stock, 0) < need.qty
  AND s.stock >= need.qty;

-- 2) 重复收款的那一笔（较晚创建）置为 CLOSED 并留痕；
--    它确实收了钱，所以保留 channel_trade_no，退款以它为准。
UPDATE t_payment
SET status = 'CLOSED',
    fail_reason = CONCAT('重复支付：订单已由支付单 202610221305078190313 入账，本笔需退款（tradeNo=',
                         channel_trade_no, '）'),
    updated_at = UTC_TIMESTAMP()
WHERE pay_no = '202610221308521149245' AND status = 'SUCCESS';

-- 3) 保留的那一笔回到 PENDING，并把过期时间推后 —— 让兜底调度重新认领并完成入账。
--    支付单状态机允许 PENDING→SUCCESS（markSuccess 的 CAS 条件），渠道侧已确认收款，
--    重新走一遍会命中「渠道查询确认支付成功」，然后扣库存、置 PAID、开托管。
UPDATE t_payment
SET status = 'PENDING',
    updated_at = UTC_TIMESTAMP(),
    expire_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 10 MINUTE)
WHERE pay_no = '202610221305078190313' AND status = 'SUCCESS';

-- 4) 复核（应在调度跑过一轮之后再看）
SELECT 'after-repair' AS stage, id, order_no, status, paid_at FROM t_order WHERE id = 371645096950640640;
SELECT 'after-repair' AS stage, sku_id, quantity, status FROM t_stock_freeze WHERE order_id = 371645096950640640;
SELECT 'after-repair' AS stage, id, pay_no, status, fail_reason FROM t_payment WHERE order_id = 371645096950640640 ORDER BY created_at;
