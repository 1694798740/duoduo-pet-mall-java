/**
 * e2e 卖家夹具（E10 起）：在库内直建一个「已通过审核的商家」。
 *
 * <h2>为什么不再调 POST /api/users/me/merchant</h2>
 * E10 落地后入驻走「提交材料 → 平台审核 → 开店」的完整状态机，
 * 旧的"实名即升 SELLER"端点已删除。业务套件（seller / aftersale / logistics）
 * 需要的只是"一个能接单的卖家"，让它完整走一遍入驻流程会让每个套件
 * 多出十几个与自身无关的步骤 —— 完整入驻链路的 e2e 覆盖是 E11 审核台的交付项。
 *
 * <h2>直建的三张表（与 MerchantOnboardingService.approve 的开店动作同构）</h2>
 *   t_merchant        status=APPROVED（其余审核字段按真实形态填充）
 *   t_merchant_member role=OWNER
 *   t_user            role=SELLER
 * id 生成用「毫秒时间戳 × 1000 + 自增」—— 与雪花 id 同量级（16 位），
 * 字符串流转纪律下不会碰到 JS 精度问题。
 *
 * 前置：账号已注册；实名（POST /api/users/me/realname）由调用方完成或传 doRealname=true。
 */
import { mysql } from './docker-exec.mjs';

let seq = 0;

/** 与雪花 id 同量级的唯一 id（库内直建专用） */
export function fixtureId() {
  return (BigInt(Date.now()) * 1000n + BigInt(++seq % 1000)).toString();
}

/**
 * 直建一个 APPROVED 商家并把账号升为 SELLER。
 *
 * @param {object} opts
 * @param {string} opts.phone          卖家手机号
 * @param {string} opts.storeName      店铺名（默认带时间戳防撞 uk_name）
 * @param {boolean} [opts.doRealname]  是否先调实名接口（需要调用方提供 callFn）
 * @param {Function} [opts.callFn]     套件的 call(method, path, body, jar) —— doRealname 时必传
 * @param {object} [opts.jar]          卖家 Cookie jar —— doRealname 时必传
 * @returns {Promise<{merchantId: string, storeName: string}>}
 */
export async function createSellerFixture({ phone, storeName, doRealname = false, callFn, jar }) {
  if (doRealname) {
    if (!callFn || !jar) {
      throw new Error('doRealname=true 时必须提供 callFn 与 jar');
    }
    const res = await callFn('POST', '/api/users/me/realname',
      { realName: '李小商', idCard: '310101199005054321' }, jar);
    if (res.status !== 200) {
      throw new Error(`实名失败 status=${res.status}`);
    }
  }

  const merchantId = fixtureId();
  const memberId = fixtureId();
  // ★ 店名必须带唯一后缀：t_merchant.uk_name 是唯一键，夹具反复跑（全量回归一天多次），
  //   固定店名第二跑就撞 409 —— 首跑绿、重跑红，正是那种"看起来像抖动其实是缺陷"的坑
  const name = `${storeName ?? '夹具店铺'}-${Date.now()}`;
  const now = 'UTC_TIMESTAMP()';

  // t_merchant：status 直接 APPROVED（审核字段按真实形态填充，审核台列表能看到它）
  await mysql('duoduo_user', `
INSERT INTO t_merchant (id, name, owner_user_id, status, isolation_level, applied_at, reviewed_at, reviewed_by, created_at, updated_at)
VALUES (${merchantId}, '${name}', (SELECT id FROM t_user WHERE phone='${phone}'), 'APPROVED', 'STANDARD',
        ${now}, ${now}, 363903492038987776, ${now}, ${now});`);
  // t_merchant_member：店主
  await mysql('duoduo_user', `
INSERT INTO t_merchant_member (id, merchant_id, user_id, role, status, created_at, updated_at)
VALUES (${memberId}, ${merchantId}, (SELECT id FROM t_user WHERE phone='${phone}'), 'OWNER', 'ACTIVE', ${now}, ${now});`);
  // 角色升级
  await mysql('duoduo_user', `UPDATE t_user SET role='SELLER' WHERE phone='${phone}';`);

  return { merchantId, storeName: name };
}
