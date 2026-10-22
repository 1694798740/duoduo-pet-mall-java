/**
 * 库存 / 收款 对账守卫（2026-10-22 事故后补）
 *
 * 事故原貌：用户在 Web 端支付成功，订单却一直停在「待支付」。
 * 根因不是回调丢了，而是下单时多卖家拆单撞了幂等唯一键，
 * 补偿逻辑把**已经落库的那张子单**的冻结量释放掉了 ——
 * 商品域 frozen_stock 归零，交易域台账却还留着 FROZEN，支付时扣不动库存。
 *
 * 这类缺陷的共同特征：**两个来源各自都"成功"，只有对不上账**。
 * 所以本守卫不复述任何业务判断，只做三件事：
 *   ① 台账 FROZEN 汇总（按 SKU）必须等于 t_sku.frozen_stock；
 *   ② 一个订单最多只有一笔 SUCCESS 支付单（多笔 = 真的重复收了钱）；
 *   ③ 已支付 / 已关闭的订单不应再有 FROZEN 台账行。
 *
 * 判据一律取「数据库里的两个来源」，不看接口、不看日志 —— 接口 200 不代表账对。
 */
import { mysql } from './lib/docker-exec.mjs';

const checks = [];
const record = (name, ok, detail) => checks.push({ name, ok, detail });

async function main() {
  // ① 台账冻结量 vs 商品域冻结量
  const mismatch = await mysql('duoduo_trade', `
    SELECT f.sku_id, f.qty AS ledger_qty, COALESCE(s.frozen_stock, 0) AS sku_frozen
    FROM (SELECT sku_id, SUM(quantity) AS qty FROM t_stock_freeze
          WHERE status = 'FROZEN' GROUP BY sku_id) f
    LEFT JOIN duoduo_product.t_sku s ON s.id = f.sku_id
    WHERE COALESCE(s.frozen_stock, 0) <> f.qty`);
  record('台账 FROZEN 汇总 == t_sku.frozen_stock', mismatch === '',
    mismatch === '' ? '两来源一致' : `不一致的 SKU（sku/台账/商品域）：\n${mismatch}`);

  // ①b 反向：商品域有冻结量，台账却没有 FROZEN 行（幽灵占用，用户看到"库存没了"）
  const orphan = await mysql('duoduo_product', `
    SELECT s.id, s.frozen_stock FROM t_sku s
    WHERE COALESCE(s.frozen_stock, 0) > 0
      AND NOT EXISTS (SELECT 1 FROM duoduo_trade.t_stock_freeze f
                      WHERE f.sku_id = s.id AND f.status = 'FROZEN')`);
  record('商品域冻结量都有对应台账行', orphan === '',
    orphan === '' ? '无幽灵冻结' : `有冻结量但无台账（sku/frozen）：\n${orphan}`);

  // ② 同一订单多笔成功收款
  const dupPay = await mysql('duoduo_trade', `
    SELECT order_id, COUNT(*) AS n, GROUP_CONCAT(pay_no) AS pay_nos
    FROM t_payment WHERE status = 'SUCCESS' GROUP BY order_id HAVING n > 1`);
  record('每单最多一笔 SUCCESS 支付单', dupPay === '',
    dupPay === '' ? '无重复收款' : `重复收款（需退款）：\n${dupPay}`);

  // ②b 已标记为"重复支付"但还没退款的单据（有则必须人工处理，不能算绿）。
  //     「已退款」写在 fail_reason 里，所以判据必须把已退款的排除掉，否则这道检查永远红。
  const pendingRefund = await mysql('duoduo_trade', `
    SELECT pay_no, order_id, amount, channel_trade_no FROM t_payment
    WHERE status = 'CLOSED' AND fail_reason LIKE '%重复支付%'
      AND fail_reason NOT LIKE '%已退款%'`);
  record('没有待退款的重复收款单', pendingRefund === '',
    pendingRefund === '' ? '无' : `待退款：\n${pendingRefund}`);

  // ③ 已支付/已关闭订单不应再有 FROZEN 台账
  const stale = await mysql('duoduo_trade', `
    SELECT f.order_id, o.status, COUNT(*) AS rows_left
    FROM t_stock_freeze f JOIN t_order o ON o.id = f.order_id
    WHERE f.status = 'FROZEN' AND o.status IN ('PAID','SHIPPED','COMPLETED','CLOSED','CANCELLED')
    GROUP BY f.order_id, o.status`);
  record('已支付/已关闭订单无 FROZEN 残留', stale === '',
    stale === '' ? '无残留' : `残留（订单/状态/行数）：\n${stale}`);

  // ③b 待付款订单必须有 FROZEN 台账（没有就说明付了款也扣不动库存）
  const missingFreeze = await mysql('duoduo_trade', `
    SELECT o.id, o.order_no FROM t_order o
    WHERE o.status = 'PENDING_PAYMENT' AND o.order_type <> 'SECKILL'
      AND NOT EXISTS (SELECT 1 FROM t_stock_freeze f
                      WHERE f.order_id = o.id AND f.status = 'FROZEN')
      AND o.created_at > DATE_SUB(UTC_TIMESTAMP(), INTERVAL 2 DAY)`);
  record('待付款订单都有 FROZEN 台账', missingFreeze === '',
    missingFreeze === '' ? '无缺失' : `缺台账（订单 id/号）：\n${missingFreeze}`);
}

main()
  .then(() => {
    const failed = checks.filter((c) => !c.ok);
    for (const c of checks) {
      console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}`);
      if (!c.ok) console.log(`      ${c.detail.replace(/\n/g, '\n      ')}`);
    }
    console.log(`\n${checks.length - failed.length}/${checks.length} 通过`);
    process.exit(failed.length === 0 ? 0 : 1);
  })
  .catch((e) => {
    console.error('守卫执行失败：', e.message);
    process.exit(2);
  });
