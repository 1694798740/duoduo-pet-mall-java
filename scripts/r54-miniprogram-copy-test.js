#!/usr/bin/env node
/**
 * R54 小程序页面级单测：通知入口去重 + 计数文案 + 详情页下拉刷新。
 *
 * 为什么需要它（三条都属于「只在真机上看得出来、接口探针测不到」的改动）：
 *  1. 删掉导航栏的通知图标后，**tabBar 的「通知」入口必须还在** ——
 *     两个入口是重复的，不是都要删。这条如果只靠人工看，很容易连 tabBar 一起删掉，
 *     而后果是「通知页再也进不去」，且不会有任何编译错误。
 *  2. 「N 次浏览 / N 人想要」是**文案与口径的对应关系**：浏览=打开次数（可累加）、
 *     想要=咨询去重人数。改成别的说法，用户就会把两个数理解成同一件事。
 *  3. 详情页下拉刷新是「刷新也算一次浏览」的落地手段（真机行为），
 *     这里用桩 wx.request 断言它确实重新请求了详情、且不把页面闪成加载态。
 *
 * 用法：node scripts/r54-miniprogram-copy-test.js      （退出码 0 = 全过）
 */
const fs = require('fs');
const path = require('path');

const MP = path.join(__dirname, '..', 'miniprogram');

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
const read = (rel) => fs.readFileSync(path.join(MP, rel), 'utf8');
const tick = () => new Promise((r) => setImmediate(r));

// ═══════════════════════════════════════════════════════════════════════
// A. 通知入口去重：导航栏那个删掉，tabBar 那个必须留着
// ═══════════════════════════════════════════════════════════════════════
console.log('\n[A] 通知入口：只删导航栏的重复图标，保留 tabBar 入口');

const navWxml = read('components/nav-bar/nav-bar.wxml');
const navJs = read('components/nav-bar/nav-bar.js');
const navWxss = read('components/nav-bar/nav-bar.wxss');
const appJson = JSON.parse(read('app.json'));

ok('A1 导航栏 wxml 已无通知图标节点（nb-msg）', navWxml.indexOf('nb-msg') < 0);
ok('A2 导航栏 wxml 已无 💬 图标字符', navWxml.indexOf('💬') < 0);
ok('A3 导航栏 js 已无 goNotice 跳转方法', navJs.indexOf('goNotice') < 0);
ok('A4 导航栏 js 不再声明 msg 属性开关', !/\bmsg\s*:\s*\{/.test(navJs));
// 注意断言的是「没有生效的规则」而不是「文件里不出现该字符串」：
// 文件里保留一句「原 .nb-msg 已删除」的说明是有价值的，不该被判成失败。
ok('A5 导航栏 wxss 已无 .nb-msg 生效规则', !/^\s*\.nb-msg\s*[,{]/m.test(navWxss));
ok('A6 导航栏仍保留右侧命名插槽（通用能力未被误删）', navWxml.indexOf('slot name="right"') >= 0);
ok('A7 导航栏仍保留返回键逻辑', navJs.indexOf('goBack') >= 0 && navWxml.indexOf('nb-back') >= 0);

const tabPaths = ((appJson.tabBar || {}).list || []).map((t) => t.pagePath);
ok('A8 ★tabBar 的「通知」入口仍在（删的是重复入口，不是通知功能本身）',
  tabPaths.indexOf('pages/notification/notification') >= 0, JSON.stringify(tabPaths));

// 全量扫描：不应再有任何页面给 nav-bar 传 msg 属性
const wxmlFiles = [];
(function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p);
    else if (name.endsWith('.wxml')) wxmlFiles.push(p);
  }
})(MP);
const msgUsers = wxmlFiles.filter((f) => fs.readFileSync(f, 'utf8').indexOf('msg="{{false}}"') >= 0);
check('A9 已无页面残留 msg="{{false}}"（组件属性已删除）', msgUsers.length, 0);

// ═══════════════════════════════════════════════════════════════════════
// B. 计数文案：浏览（次数）与想要（人数）必须分清
// ═══════════════════════════════════════════════════════════════════════
console.log('\n[B] 计数文案与口径对应');

const marketWxml = read('pages/flea-market/flea-market.wxml');
const detailWxml = read('pages/flea-detail/flea-detail.wxml');
const mineWxml = read('pages/flea-mine/flea-mine.wxml');

ok('B1 跳蚤市场卡片：浏览量为「次浏览」', marketWxml.indexOf('{{item.viewCount}} 次浏览') >= 0);
ok('B2 跳蚤市场卡片：新增「人想要」', marketWxml.indexOf('{{item.wantCount}} 人想要') >= 0);
ok('B3 闲置详情：浏览量为「次浏览」', detailWxml.indexOf('{{card.viewCount}} 次浏览') >= 0);
ok('B4 闲置详情：新增「人想要」', detailWxml.indexOf('{{card.wantCount}} 人想要') >= 0);
ok('B5 我的闲置：浏览量与想要并列', mineWxml.indexOf('次浏览') >= 0 && mineWxml.indexOf('人想要') >= 0);
ok('B6 ★旧文案「人看过」已全部替换（浏览是可累加的次数，说「人」不准确）',
  [marketWxml, detailWxml, mineWxml].every((s) => s.indexOf('人看过') < 0));

// ═══════════════════════════════════════════════════════════════════════
// C. 详情页下拉刷新：刷新一次 = 再计一次浏览
// ═══════════════════════════════════════════════════════════════════════
console.log('\n[C] 闲置详情下拉刷新行为（桩 wx.request 实测）');

const captured = [];
let stopRefreshCount = 0;

global.wx = {
  getStorageSync: (k) => (String(k).indexOf('refresh') >= 0 ? 'r' : 'a'),
  setStorageSync() {},
  removeStorageSync() {},
  request(opts) {
    captured.push(opts);
    if (opts.success) {
      opts.success({
        statusCode: 200,
        data: {
          code: 'SUCCESS',
          message: 'ok',
          // card 不带 sellerId：避免顺带触发卖家资料请求，让断言聚焦在详情请求上
          data: { card: { id: '362981679285018888', title: 'T', price: 100 }, description: '', images: [], sameCategoryNew: [] },
        },
      });
    }
  },
  stopPullDownRefresh() {
    stopRefreshCount++;
  },
  showToast() {},
  showModal() {},
  navigateTo() {},
  navigateBack() {},
  previewImage() {},
  setNavigationBarTitle() {},
};
global.getApp = () => ({ globalData: {} });

let pageCfg = null;
global.Page = (cfg) => { pageCfg = cfg; };
global.Component = () => {};

require(path.join(MP, 'pages', 'flea-detail', 'flea-detail.js'));

function newPage() {
  const inst = Object.assign({}, pageCfg);
  inst.data = JSON.parse(JSON.stringify(pageCfg.data));
  inst.setData = function (patch) { Object.assign(this.data, patch); };
  return inst;
}

(async function main() {
  const detailJson = JSON.parse(read('pages/flea-detail/flea-detail.json'));
  ok('C1 detail.json 已开启下拉刷新', detailJson.enablePullDownRefresh === true,
    JSON.stringify(detailJson));

  // 首次进入：onLoad 拉一次详情
  captured.length = 0;
  const p1 = newPage();
  p1.onLoad({ id: '362981679285018888' });
  await tick();
  ok('C2 onLoad 首次进入即请求详情',
    captured.length === 1 && captured[0].url.indexOf('/api/listings/362981679285018888') >= 0,
    JSON.stringify(captured.map((c) => c.url)));

  // 下拉刷新：再请求一次（服务端每次详情请求都会给浏览次数 +1 → 「刷新一次也计一次」）
  captured.length = 0;
  const p2 = newPage();
  p2.data.id = '362981679285018888';
  p2.onPullDownRefresh();
  await tick();
  ok('C3 ★下拉刷新会重新请求详情（刷新也计一次浏览）',
    captured.length === 1 && captured[0].url.indexOf('/api/listings/362981679285018888') >= 0,
    JSON.stringify(captured.map((c) => c.url)));
  check('C4 刷新结束后收起下拉指示器', stopRefreshCount, 1);
  check('C5 刷新是静默的：不把页面切回「正在加载」（内容留在屏幕上）', p2.data.loading, false);
  check('C6 刷新后详情渲染完成（card 已就位）', !!(p2.data.card && p2.data.card.title), true);

  // 缺 id 时不发请求，但仍要收起指示器（否则刷新图标一直转）
  captured.length = 0;
  stopRefreshCount = 0;
  const p3 = newPage();
  p3.data.id = null;
  p3.onPullDownRefresh();
  await tick();
  check('C7 缺少 id 时不发请求（避免拼出 /api/listings/undefined）', captured.length, 0);
  check('C8 但必须收起下拉指示器（否则图标一直转）', stopRefreshCount, 1);

  console.log('\n结论：' + pass + ' PASS / ' + fail + ' FAIL');
  process.exit(fail ? 1 : 0);
})();
