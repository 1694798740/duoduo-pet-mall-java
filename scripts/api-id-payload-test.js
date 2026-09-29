#!/usr/bin/env node
/**
 * api.js 请求体 ID 精度单测（第五十三轮热修新增）
 *
 * 为什么需要它：接口探针（Python）传的 id 天然是字符串，**永远测不到**这个缺陷 ——
 * 缺陷在前端「把 id 强转成 JS 数字」这一步。故本测真实加载 miniprogram/utils/api.js，
 * 桩掉 wx.request 捕获实际出参，直接断言请求体里的 id 是**字符串且原值未变**。
 *
 * 背景：雪花 id 为 18~19 位十进制，超出 JS Number 的 53 位有效精度：
 *   Number('710000000000000001') === 710000000000000000   → 末位被吞
 * 服务端按错误 id 查库 → 404「用户不存在」→ 前端文案化成「内容不存在或已删除」，
 * 用户视角就是「消息怎么都发不出去」。
 *
 * 用法：node scripts/api-id-payload-test.js      （退出码 0 = 全过）
 */
const path = require('path');

const captured = [];

// ---- 最小 wx 运行时桩：只要能加载 api.js/request.js 并捕获出参 ----
global.wx = {
  getStorageSync: (k) => (String(k).indexOf('refresh') >= 0 ? 'stub-refresh-token' : 'stub-access-token'),
  setStorageSync() {},
  removeStorageSync() {},
  request(opts) {
    captured.push(opts);
    if (opts.success) {
      opts.success({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: {} } });
    }
  },
  uploadFile() {},
  showToast() {},
  showModal() {},
  navigateTo() {},
  redirectTo() {},
  switchTab() {},
  reLaunch() {},
  setTabBarBadge() {},
  hideTabBarBadge() {},
  setNavigationBarTitle() {},
};
global.getApp = () => ({ globalData: {} });

const api = require(path.join(__dirname, '..', 'miniprogram', 'utils', 'api.js'));

const OFFICIAL = '710000000000000001';      // 18 位，Number() 必丢位
const LISTING = '362981679285018624';       // 18 位
const SNOW_19 = '363184097960529920';       // 19 位

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
function checkType(name, value, want) {
  const got = typeof value;
  if (got === want) {
    pass++;
    console.log(`  [PASS] ${name} 类型为 ${want}（值 ${JSON.stringify(value)}）`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name} 类型=${got}（值 ${JSON.stringify(value)}），期望 ${want}`);
  }
}

console.log('=== api.js 请求体 ID 精度单测 ===');
console.log(`  JS Number("${OFFICIAL}") = ${Number(OFFICIAL)}（对照：这就是强转后的错值）`);

// ── 1. 发消息：全部 id 字段必须原样字符串 ──────────────────────────────────
console.log('\n[1] api.social.message.send');
captured.length = 0;
api.social.message.send(OFFICIAL, '你好', 'ASK', '1002', '1002', LISTING);
check('出参 URL 指向 /api/socials/messages', /\/api\/socials\/messages$/.test(captured[0].url), true);
const p1 = captured[0].data;
checkType('receiverId 为字符串', p1.receiverId, 'string');
check('receiverId 原值未变', p1.receiverId, OFFICIAL);
checkType('bizId 为字符串', p1.bizId, 'string');
check('bizId 原值未变', p1.bizId, '1002');
checkType('productId 为字符串', p1.productId, 'string');
checkType('listingId 为字符串', p1.listingId, 'string');
check('listingId 原值未变', p1.listingId, LISTING);
check('content 原样', p1.content, '你好');

// 19 位 id（Number 必丢位）
captured.length = 0;
api.social.message.send(SNOW_19, 'hi');
check('19 位 receiverId 原值未变（未被 Number 吞位）', captured[0].data.receiverId, SNOW_19);

// 缺省字段不该出现（避免把 "undefined" 当 id 上行）
captured.length = 0;
api.social.message.send(OFFICIAL, 'hi');
check('未传 bizType 时不出现在请求体', 'bizType' in captured[0].data, false);
check('未传 bizId 时不出现在请求体', 'bizId' in captured[0].data, false);
check('未传 listingId 时不出现在请求体', 'listingId' in captured[0].data, false);

// ── 2. 发布分享：spuId 同样按字符串上行 ────────────────────────────────────
console.log('\n[2] api.share.publish');
captured.length = 0;
api.share.publish(SNOW_19, '很不错的猫粮');
checkType('spuId 为字符串', captured[0].data.spuId, 'string');
check('spuId 原值未变', captured[0].data.spuId, SNOW_19);

// ── 3. URL 拼接不做数值转换（thread / markRead 带 peerId 与上下文） ────────
console.log('\n[3] api.social.message.thread / markRead');
captured.length = 0;
api.social.message.thread(OFFICIAL, 1, 20, '1002', LISTING);
const url = captured[0].url;
check('URL 内 peerId 原值未变', url.indexOf('/conversations/' + OFFICIAL) >= 0, true);
check('URL 内 listingId 原值未变', url.indexOf('listingId=' + LISTING) >= 0, true);
captured.length = 0;
api.social.message.markRead(OFFICIAL, '1002', LISTING);
check('markRead URL 内两个上下文都在且未被转数值',
  captured[0].url.indexOf('productId=1002') >= 0 && captured[0].url.indexOf('listingId=' + LISTING) >= 0,
  true);

// ── 4. 反回归：绝不允许出现强转后的错值 ────────────────────────────────────
console.log('\n[4] 反回归：错值不得出现');
captured.length = 0;
api.social.message.send(OFFICIAL, 'hi', 'ASK', '1002', '1002', LISTING);
const json = JSON.stringify(captured[0].data);
check('请求体不含 Number 强转后的错值', json.indexOf(String(Number(OFFICIAL))) >= 0, false);

console.log(`\n结论：${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
