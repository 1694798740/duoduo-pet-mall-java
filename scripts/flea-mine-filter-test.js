#!/usr/bin/env node
/**
 * 「我的闲置」筛选互斥 + 单一状态徽章 单测（第五十三轮热修二新增）
 *
 * 为什么需要它：接口探针（Python）只能证明**服务端**在给定参数下返回正确结果，
 * 却测不到前端「到底发了什么参数」—— 用户报的「一个商品同时出现在审核中和在售两个标签下」，
 * 根因恰恰在前端只传了 `status=ON_SALE` 而漏了 `auditStatus=APPROVED`。
 * 故本测真实加载 miniprogram/pages/flea-mine/flea-mine.js，桩掉 wx.request 捕获实际 URL，
 * 直接断言前端拼出的每个标签的查询串，并把 decorate() 的渲染结果一并断言。
 *
 * 用法：node scripts/flea-mine-filter-test.js      （退出码 0 = 全过）
 */
const path = require('path');

const captured = [];

// ---- 最小 wx 运行时桩：够 api.js/request.js 跑完一次 GET 即可 ----
global.wx = {
  getStorageSync: (k) => (String(k).indexOf('refresh') >= 0 ? 'stub-refresh-token' : 'stub-access-token'),
  setStorageSync() {},
  removeStorageSync() {},
  request(opts) {
    captured.push(opts);
    // 分页包络：request 层解包后 page 拿到的是 data 本身（{items,total}）
    if (opts.success) {
      opts.success({
        statusCode: 200,
        data: { code: 'SUCCESS', message: 'ok', data: { items: rows, total: rows.length } },
      });
    }
  },
  uploadFile() {},
  showToast() {},
  showModal() {},
  navigateTo() {},
  redirectTo() {},
  switchTab() {},
  reLaunch() {},
  navigateBack() {},
  stopPullDownRefresh() {},
  setNavigationBarTitle() {},
};
global.getApp = () => ({ globalData: {} });

// 桩 Page：捕获页面配置对象（flea-mine.js 在 require 时就会调用）
let pageCfg = null;
global.Page = (cfg) => { pageCfg = cfg; };
global.Component = () => {};

require(path.join(__dirname, '..', 'miniprogram', 'pages', 'flea-mine', 'flea-mine.js'));

// changeStatus 也要能被桩掉（用于「就地更新」用例）
const api = require(path.join(__dirname, '..', 'miniprogram', 'utils', 'api.js'));

// 本用例的服务端返回数据，随用例切换
let rows = [];

let pass = 0;
let fail = 0;
function check(name, actual, expected) {
  if (actual === expected) {
    pass++;
    console.log(`  [PASS] ${name} → ${JSON.stringify(actual)}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name} → 实际=${JSON.stringify(actual)} 期望=${JSON.stringify(expected)}`);
  }
}
function ok(name, cond, detail) {
  if (cond) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name}${detail ? ' → ' + detail : ''}`);
  }
}
const tick = () => new Promise((r) => setImmediate(r));

/** 造一个页面实例（带 setData 合并语义） */
function newPage() {
  const inst = Object.assign({}, pageCfg);
  inst.data = JSON.parse(JSON.stringify(pageCfg.data));
  inst.setData = function (patch) { Object.assign(this.data, patch); };
  return inst;
}

/** 切到某标签并触发一次加载，返回实际请求 URL */
async function loadWith(filter) {
  captured.length = 0;
  const inst = newPage();
  inst.data.status = filter;
  inst.load(true);
  await tick();
  return captured.length ? captured[0].url : '';
}

function q(url, key) {
  const m = new RegExp('[?&]' + key + '=([^&]*)').exec(url);
  return m ? m[1] : null;
}

(async function main() {
  console.log('=== 「我的闲置」筛选互斥 + 单一状态徽章 单测 ===');

  // ── A. 五个标签各自拼出的查询串（互斥划分的前端侧契约） ────────────────
  console.log('\n[A] 标签 → 接口参数（不重不漏划分）');

  const urlPending = await loadWith('AUDIT_PENDING');
  ok('A1 审核中：带 auditStatus=PENDING', q(urlPending, 'auditStatus') === 'PENDING', urlPending);
  ok('A2 审核中：不带 status（否则会漏掉 status=OFF_SHELF 的驳回件）', q(urlPending, 'status') === null, urlPending);

  const urlRejected = await loadWith('AUDIT_REJECTED');
  ok('A3 审核失败：带 auditStatus=REJECTED', q(urlRejected, 'auditStatus') === 'REJECTED', urlRejected);
  ok('A4 审核失败：不带 status', q(urlRejected, 'status') === null, urlRejected);

  const urlOnSale = await loadWith('ON_SALE');
  ok('A5 在售：带 status=ON_SALE', q(urlOnSale, 'status') === 'ON_SALE', urlOnSale);
  ok('A6 在售：★必须同时带 auditStatus=APPROVED（本次缺陷根因，缺它就会把待审件一起捞出来）',
    q(urlOnSale, 'auditStatus') === 'APPROVED', urlOnSale);

  const urlOff = await loadWith('OFF_SHELF');
  ok('A7 已下架：带 status=OFF_SHELF', q(urlOff, 'status') === 'OFF_SHELF', urlOff);
  ok('A8 已下架：必须同时带 auditStatus=APPROVED（驳回件服务端也落 OFF_SHELF，不算卖家主动下架）',
    q(urlOff, 'auditStatus') === 'APPROVED', urlOff);

  const urlSold = await loadWith('SOLD');
  ok('A9 已售出：带 status=SOLD', q(urlSold, 'status') === 'SOLD', urlSold);
  ok('A10 已售出：必须同时带 auditStatus=APPROVED', q(urlSold, 'auditStatus') === 'APPROVED', urlSold);

  const urlAll = await loadWith('ALL');
  ok('A11 全部：两个维度都不带（服务端返回我的所有条目）',
    q(urlAll, 'status') === null && q(urlAll, 'auditStatus') === null, urlAll);

  ok('A12 分页参数仍在（回归：加筛选后没把 page/pageSize 挤掉）',
    q(urlOnSale, 'page') === '1' && q(urlOnSale, 'pageSize') === '10', urlOnSale);

  // ── B. 反例固定：三个交易标签的审计维度缺一不可 ──────────────────────
  console.log('\n[B] 反例固定（防止有人「简化」掉 auditStatus）');
  const trade = { ON_SALE: urlOnSale, OFF_SHELF: urlOff, SOLD: urlSold };
  Object.keys(trade).forEach((k) => {
    // 后端探针 S4 已固定：只传 status=ON_SALE 会同时命中 PENDING 件
    const hasBoth = q(trade[k], 'status') === k && q(trade[k], 'auditStatus') === 'APPROVED';
    ok(`B-${k} 两个维度同时存在（只传 status 会与「审核中」重叠）`, hasBoth, trade[k]);
  });

  // ── C. 单一状态徽章（decorate 渲染结果） ──────────────────────────────
  console.log('\n[C] 卡片徽章：一物一状态');
  const cases = [
    { in: { status: 'ON_SALE', auditStatus: 'PENDING' }, text: '审核中', cls: 'fm-badge-pending', why: '待审件不能显示「在售」' },
    { in: { status: 'ON_SALE', auditStatus: 'REJECTED' }, text: '审核失败', cls: 'fm-badge-rejected', why: '驳回件' },
    { in: { status: 'ON_SALE', auditStatus: 'APPROVED' }, text: '在售', cls: 'fm-badge-onsale', why: '过审在售' },
    { in: { status: 'OFF_SHELF', auditStatus: 'APPROVED' }, text: '已下架', cls: 'fm-badge-off', why: '过审下架' },
    { in: { status: 'SOLD', auditStatus: 'APPROVED' }, text: '已售出', cls: 'fm-badge-sold', why: '过审已售' },
    { in: { status: 'OFF_SHELF', auditStatus: 'REJECTED' }, text: '审核失败', cls: 'fm-badge-rejected', why: '驳回件服务端落 OFF_SHELF，仍应显示审核失败' },
  ];
  for (let i = 0; i < cases.length; i++) {
    const c = cases[i];
    rows = [Object.assign({ id: '36298167928501862' + i, price: 100, createdAt: '2026-09-29T04:00:00Z' }, c.in)];
    const inst = newPage();
    inst.data.status = 'ALL';
    inst.load(true);
    await tick();
    const it = inst.data.items[0];
    ok(`C${i + 1} ${JSON.stringify(c.in)} → 文案「${c.text}」（${c.why}）`, it.statusText === c.text, `实际=${it.statusText}`);
    ok(`C${i + 1}b 类名同步为 ${c.cls}（文案与配色同源，防错配）`, it.statusCls === c.cls, `实际=${it.statusCls}`);
    ok(`C${i + 1}c 单一性：文案不含拼接分隔符`, !/[\/|、,，+]/.test(it.statusText), it.statusText);
  }

  // ── D. 操作按钮权限与徽章同源 ────────────────────────────────────────
  console.log('\n[D] 按钮权限（不出现「徽章说在售、却给了上架按钮」）');
  const btn = [
    { in: { status: 'ON_SALE', auditStatus: 'PENDING' }, shelve: false, unshelve: false, resubmit: false },
    { in: { status: 'OFF_SHELF', auditStatus: 'REJECTED' }, shelve: false, unshelve: false, resubmit: true },
    { in: { status: 'ON_SALE', auditStatus: 'APPROVED' }, shelve: false, unshelve: true, resubmit: false },
    { in: { status: 'OFF_SHELF', auditStatus: 'APPROVED' }, shelve: true, unshelve: false, resubmit: false },
    { in: { status: 'SOLD', auditStatus: 'APPROVED' }, shelve: false, unshelve: false, resubmit: false },
  ];
  for (let i = 0; i < btn.length; i++) {
    const b = btn[i];
    rows = [Object.assign({ id: '3629816792850187' + i, price: 100, createdAt: '2026-09-29T04:00:00Z' }, b.in)];
    const inst = newPage();
    inst.data.status = 'ALL';
    inst.load(true);
    await tick();
    const it = inst.data.items[0];
    ok(`D${i + 1} ${JSON.stringify(b.in)} → 上架=${b.shelve} 下架=${b.unshelve} 重提=${b.resubmit}`,
      it.canShelve === b.shelve && it.canUnshelve === b.unshelve && it.canResubmit === b.resubmit,
      `实际 上架=${it.canShelve} 下架=${it.canUnshelve} 重提=${it.canResubmit}`);
  }

  // ── E. 就地更新（点下架后不整页重拉）也走同一套推导 ───────────────────
  console.log('\n[E] 就地更新一致性（下架后徽章与按钮同步翻转）');
  rows = [{ id: '362981679285018999', price: 100, createdAt: '2026-09-29T04:00:00Z', status: 'ON_SALE', auditStatus: 'APPROVED' }];
  const inst = newPage();
  inst.data.status = 'ALL';
  inst.load(true);
  await tick();
  check('E1 更新前徽章=在售', inst.data.items[0].statusText, '在售');
  // 桩掉 changeStatus，避免依赖真实网络
  const originChange = api.listing.changeStatus;
  api.listing.changeStatus = () => Promise.resolve({});
  inst.onToggleStatus({ currentTarget: { dataset: { id: '362981679285018999', action: 'OFF_SHELF' } } });
  await tick();
  api.listing.changeStatus = originChange;
  check('E2 更新后徽章=已下架', inst.data.items[0].statusText, '已下架');
  check('E3 更新后按钮翻转为可上架', inst.data.items[0].canShelve, true);
  check('E4 更新后不再给下架按钮', inst.data.items[0].canUnshelve, false);

  console.log('\n结论：' + pass + ' PASS / ' + fail + ' FAIL');
  process.exit(fail ? 1 : 0);
})();
