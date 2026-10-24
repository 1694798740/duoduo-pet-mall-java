/**
 * 订单级库存预占（B-03）专项冒烟
 *
 * 守的是「释放 / 扣减只校验全局冻结量」这个资损方向的缺陷：
 *   A. 缺 orderId 一律 400；
 *   B. 同一订单重复冻结幂等（数量一致返回 0，不重复冻）；
 *   C. 同一订单改数量重复冻结 → 拒绝（不能按新数量再冻一次）；
 *   D. A 单释放不影响 B 单；重复释放幂等；
 *   E. 扣减只扣本订单的预占；重复扣减幂等；扣减后不能再释放；
 *   F. 没冻结过就扣减 → 拒绝（钱收了库存没扣是资损方向）；没冻结过释放 → 幂等成功；
 *   G. 每次操作都回库核对 t_sku.stock / frozen_stock 与预占行状态，不只看接口 200。
 *
 * 前置：容器在跑；incremental/47 已执行；商品服务已升级到 B-03。
 * 用法：NO_PROXY="*" node scripts/stock-order-reservation-smoke.mjs
 */
import { createHmac, randomUUID } from 'node:crypto';
import { mysql } from './lib/docker-exec.mjs';

const PRODUCT = 'http://localhost:8082';
const ROOT_TOKEN = process.env.INTERNAL_TOKEN || 'duoduo-local-dev-internal-token';
const CALLER = 'duoduo-smoke';
const SKU = Number(process.env.R47_SKU_ID || 2001);
const ORDER_A = 900000000000047001n;
const ORDER_B = 900000000000047002n;
const ORDER_C = 900000000000047003n;

let pass = 0;
const failures = [];
let original = null;

function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 400)));
  }
}

/** 与 scripts/lib/internal-token.ps1 同口径：每次调用现签，nonce 一次性 */
function internalToken(method) {
  const b64url = (buf) => buf.toString('base64')
    .replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const hmac = (key, data) => b64url(createHmac('sha256', key).update(data).digest());
  const ts = Math.floor(Date.now() / 1000);
  const nonce = randomUUID().replace(/-/g, '');
  const key = hmac(ROOT_TOKEN, `dd1|key|${CALLER.toLowerCase()}`);
  const sig = hmac(key, `dd1\n${CALLER}\n${ts}\n${nonce}\n${method.toUpperCase()}`);
  return `dd1.${CALLER}.${ts}.${nonce}.${sig}`;
}

async function call(action, body) {
  const res = await fetch(`${PRODUCT}/api/products/skus/stock/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Internal-Token': internalToken('POST') },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

const freeze = (orderId, quantity) => call('freeze', {
  items: [{ skuId: SKU, quantity }],
  ...(orderId == null ? {} : { orderId: String(orderId) }),
});
const release = (orderId, quantity) => call('release', {
  items: [{ skuId: SKU, quantity }],
  ...(orderId == null ? {} : { orderId: String(orderId) }),
});
const deduct = (orderId, quantity) => call('deduct', {
  items: [{ skuId: SKU, quantity }],
  ...(orderId == null ? {} : { orderId: String(orderId) }),
});

async function stockRow() {
  const line = await mysql('duoduo_product',
    `SELECT stock, COALESCE(frozen_stock, 0) FROM t_sku WHERE id = ${SKU}`);
  const [stock, frozen] = line.split('\t').map(Number);
  return { stock, frozen };
}

async function reservation(orderId) {
  const line = await mysql('duoduo_product',
    `SELECT quantity, status FROM t_sku_stock_reservation WHERE order_id = ${orderId} AND sku_id = ${SKU}`);
  if (!line) return null;
  const [quantity, status] = line.split('\t');
  return { quantity: Number(quantity), status };
}

function resultOf(body) {
  return body?.data?.results?.[0];
}

async function cleanup() {
  if (original == null) return;
  await mysql('duoduo_product',
    `UPDATE t_sku SET stock = ${original.stock}, frozen_stock = ${original.frozen} WHERE id = ${SKU}`);
  await mysql('duoduo_product',
    `DELETE FROM t_sku_stock_reservation WHERE order_id IN (${ORDER_A}, ${ORDER_B}, ${ORDER_C})`);
  const restored = await stockRow();
  check('D1 探针数据已还原（stock / frozen_stock 回到原值，预占行已删）',
    restored.stock === original.stock && restored.frozen === original.frozen
      && (await reservation(ORDER_A)) == null
      && (await reservation(ORDER_B)) == null
      && (await reservation(ORDER_C)) == null,
    { original, restored });
}

async function main() {
  console.log('='.repeat(72));
  console.log(`订单级库存预占（B-03）冒烟 @ ${PRODUCT} / SKU ${SKU}`);
  console.log('='.repeat(72));

  original = await stockRow();
  check('P0 能读到 SKU 库存（探针前置）',
    Number.isFinite(original.stock) && Number.isFinite(original.frozen), original);
  if (!Number.isFinite(original.stock)) return 1;

  console.log('\n[A] 缺 orderId 必须拒绝');
  const noOrder = await freeze(null, 1);
  check('A1 freeze 缺 orderId → 400 VALIDATION_ERROR',
    noOrder.status === 400 && noOrder.body?.code === 'VALIDATION_ERROR', noOrder);
  const afterNoOrder = await stockRow();
  check('A2 缺 orderId 时库存未变动', afterNoOrder.frozen === original.frozen, afterNoOrder);

  console.log('\n[B] 首次冻结与重复冻结');
  const f1 = await freeze(ORDER_A, 2);
  check('B1 首次冻结 200 且成功，回执数量 2',
    f1.status === 200 && f1.body?.data?.allSuccess === true && resultOf(f1.body)?.quantity === 2, f1.body);
  const s1 = await stockRow();
  const r1 = await reservation(ORDER_A);
  check('B2 回库：frozen_stock +2，预占行 FROZEN / 2',
    s1.frozen === original.frozen + 2 && s1.stock === original.stock
      && r1?.status === 'FROZEN' && r1?.quantity === 2, { s1, r1 });

  const f2 = await freeze(ORDER_A, 2);
  check('B3 同订单同数量重复冻结 → 幂等成功，回执数量 0',
    f2.status === 200 && f2.body?.data?.allSuccess === true && resultOf(f2.body)?.quantity === 0, f2.body);
  const s2 = await stockRow();
  check('B4 重复冻结没有二次增加 frozen_stock', s2.frozen === s1.frozen, { before: s1, after: s2 });

  const f3 = await freeze(ORDER_A, 3);
  check('B5 同订单改数量重复冻结 → 拒绝 RESERVATION_QTY_MISMATCH',
    f3.status === 200 && f3.body?.data?.allSuccess === false
      && resultOf(f3.body)?.reason === 'RESERVATION_QTY_MISMATCH', f3.body);
  const s3 = await stockRow();
  check('B6 数量不一致时 frozen_stock 未变', s3.frozen === s2.frozen, { before: s2, after: s3 });

  console.log('\n[C] 第二个订单的预占');
  const f4 = await freeze(ORDER_B, 1);
  const s4 = await stockRow();
  check('C1 订单 B 冻结 1 件成功，全局 frozen_stock +1',
    f4.status === 200 && f4.body?.data?.allSuccess === true && s4.frozen === original.frozen + 3, { f4: f4.body, s4 });

  console.log('\n[D] 释放只作用于本订单');
  const relA = await release(ORDER_A, 2);
  const s5 = await stockRow();
  const rA = await reservation(ORDER_A);
  const rB = await reservation(ORDER_B);
  check('D1 释放订单 A 成功，回执数量 2', relA.body?.data?.allSuccess === true && resultOf(relA.body)?.quantity === 2, relA.body);
  check('D2 回库：frozen_stock 只减 2（B 的 1 件仍冻结）', s5.frozen === original.frozen + 1, { s4, s5 });
  check('D3 A 预占 RELEASED，B 预占仍 FROZEN', rA?.status === 'RELEASED' && rB?.status === 'FROZEN', { rA, rB });

  const relA2 = await release(ORDER_A, 2);
  const s6 = await stockRow();
  check('D4 重复释放 → 幂等成功，回执数量 0，库存不再变化',
    relA2.body?.data?.allSuccess === true && resultOf(relA2.body)?.quantity === 0 && s6.frozen === s5.frozen,
    { relA2: relA2.body, s6 });

  const relBWrong = await release(ORDER_B, 3);
  check('D5 释放数量与预占不一致 → 拒绝，B 仍 FROZEN',
    relBWrong.body?.data?.allSuccess === false
      && resultOf(relBWrong.body)?.reason === 'RESERVATION_QTY_MISMATCH'
      && (await reservation(ORDER_B))?.status === 'FROZEN', relBWrong.body);

  console.log('\n[E] 扣减只扣本订单的预占');
  const d1 = await deduct(ORDER_B, 1);
  const s7 = await stockRow();
  const rB2 = await reservation(ORDER_B);
  check('E1 扣减订单 B 成功，回执数量 1', d1.body?.data?.allSuccess === true && resultOf(d1.body)?.quantity === 1, d1.body);
  check('E2 回库：stock -1、frozen_stock 回到原值，B 预占 DEDUCTED',
    s7.stock === original.stock - 1 && s7.frozen === original.frozen
      && rB2?.status === 'DEDUCTED', { s7, rB2 });

  const d2 = await deduct(ORDER_B, 1);
  const s8 = await stockRow();
  check('E3 重复扣减 → 幂等成功，回执数量 0，库存不再变化',
    d2.body?.data?.allSuccess === true && resultOf(d2.body)?.quantity === 0
      && s8.stock === s7.stock && s8.frozen === s7.frozen, { d2: d2.body, s8 });

  const relB = await release(ORDER_B, 1);
  check('E4 已扣减后再释放 → 拒绝 RESERVATION_STATE_CONFLICT',
    relB.body?.data?.allSuccess === false
      && resultOf(relB.body)?.reason === 'RESERVATION_STATE_CONFLICT', relB.body);

  console.log('\n[F] 从未冻结的订单');
  const dC = await deduct(ORDER_C, 1);
  const relC = await release(ORDER_C, 1);
  const s9 = await stockRow();
  check('F1 没冻结就扣减 → 拒绝 RESERVATION_NOT_FOUND（资损方向不能静默成功）',
    dC.body?.data?.allSuccess === false
      && resultOf(dC.body)?.reason === 'RESERVATION_NOT_FOUND', dC.body);
  check('F2 没冻结就释放 → 幂等成功，回执数量 0',
    relC.body?.data?.allSuccess === true && resultOf(relC.body)?.quantity === 0, relC.body);
  check('F3 两种请求都没有改动库存', s9.stock === s8.stock && s9.frozen === s8.frozen, { s8, s9 });

  return 0;
}

try {
  await main();
} catch (e) {
  check('探针执行完成（无未捕获异常）', false, e.message);
} finally {
  try {
    await cleanup();
  } catch (e) {
    check('D1 探针数据已还原', false, e.message);
  }
}

console.log('\n' + '='.repeat(72));
console.log(`结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
}
console.log('='.repeat(72));
process.exitCode = failures.length === 0 ? 0 : 1;
