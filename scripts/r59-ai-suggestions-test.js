#!/usr/bin/env node
/**
 * AI 导购「快捷问题 / 相关追问」· 前端契约与页面行为 单测（第五十八轮 · S3）
 *
 * 为什么必须有这一套：服务端把 `quickQuestions`（能力接口）与 `followUps`（对话响应）
 * 两个新字段下发之后，**前端不消费就等于没做** —— 而这类「后端有、前端没接」的缺口
 * 接口探针永远测不到（探针直接看 JSON，不看页面有没有渲染）。本轮要守的具体契约：
 *   · `quickQuestions` 取自商品域真实类目，只在**还没聊起来**时显示（聊过之后再显示
 *     类目级问题会打断话题）；
 *   · `followUps` 由本轮候选推导，**换话题时必须和澄清选项一起清掉**（旧的追问是
 *     针对上一轮候选的，留着点了就答非所问）；
 *   · 两类 chips 共用 `tapOption`（都是「点一下就发这一句」），复用同一个 data-send 契约。
 *
 * 本测真实加载 `utils/api.js` 与 `pages/ai/ai.js`，桩掉 wx.request 捕获实际出参。
 * 末节含**反向验证**：把 wxml 区块与胶囊样式故意改坏，证明断言真的会抓
 * （静态断言最怕变成永真空转 —— R54 教训）。
 *
 * 用法：node scripts/r59-ai-suggestions-test.js      （退出码 0 = 全过）
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MNP = path.join(ROOT, 'miniprogram');

const captured = [];

let toastLog = [];
global.wx = {
  getStorageSync: () => 'stub-access-token',
  setStorageSync() {},
  removeStorageSync() {},
  request(opts) {
    captured.push(opts);
    if (opts.success) {
      opts.success({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: {} } });
    }
  },
  uploadFile() {},
  showToast(o) {
    toastLog.push(o && o.title);
  },
  showModal(o) {
    if (o && o.success) o.success({ confirm: false });
  },
  navigateTo() {},
  redirectTo() {},
  switchTab() {},
  reLaunch() {},
  navigateBack() {},
  stopPullDownRefresh() {},
  setNavigationBarTitle() {},
  onKeyboardHeightChange() {},
  offKeyboardHeightChange() {},
};

global.getApp = () => ({ globalData: {}, requireLogin: () => Promise.resolve() });

let pageCfg = null;
global.Page = (cfg) => {
  pageCfg = cfg;
};
global.Component = () => {};

function loadPage(rel) {
  const abs = path.join(MNP, rel);
  delete require.cache[require.resolve(abs)];
  pageCfg = null;
  require(abs);
  if (!pageCfg) throw new Error('页面未注册 Page: ' + rel);
  return pageCfg;
}

function makePage(cfg, data) {
  const inst = Object.assign({}, cfg);
  inst.data = Object.assign({}, cfg.data, data || {});
  inst.setData = (patch) => {
    Object.keys(patch || {}).forEach((k) => {
      const m = /^([A-Za-z_$][\w$]*)\[(\d+)\]\.(.+)$/.exec(k);
      if (m) {
        inst.data[m[1]] = inst.data[m[1]] || [];
        if (!inst.data[m[1]][Number(m[2])]) inst.data[m[1]][Number(m[2])] = {};
        inst.data[m[1]][Number(m[2])][m[3]] = patch[k];
        return;
      }
      inst.data[k] = patch[k];
    });
  };
  return inst;
}

const api = require(path.join(MNP, 'utils', 'api.js'));

let pass = 0;
let fail = 0;
function check(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    pass++;
    console.log(`  [PASS] ${name} → ${a}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name} → 实际=${a} 期望=${e}`);
  }
}
function ok(name, cond, detail) {
  if (cond) {
    pass++;
    console.log(`  [PASS] ${name}${detail === undefined ? '' : ' → ' + detail}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name}${detail === undefined ? '' : ' → ' + detail}`);
  }
}
function lastBody() {
  const o = captured[captured.length - 1];
  if (!o || o.data == null) return null;
  return typeof o.data === 'string' ? JSON.parse(o.data) : o.data;
}
function reset() {
  captured.length = 0;
  toastLog = [];
}
function tick() {
  return new Promise((r) => setTimeout(r, 0));
}
async function drain(inst) {
  for (let i = 0; i < 5; i++) {
    await tick();
    if (!inst.data.sending) await tick();
  }
}

/** 按请求路径分发响应（capabilities 与 chat 是两个端点，必须分别给内容） */
let capRespondWith = null;
let chatRespondWith = {};
global.wx.request = function (opts) {
  captured.push(opts);
  const isCap = String(opts.url || '').includes('/capabilities');
  const data = isCap ? capRespondWith : chatRespondWith;
  if (opts.success) {
    opts.success({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: data || {} } });
  }
};

const CAP = {
  chatAvailable: true,
  quickQuestions: ['猫粮怎么选', '狗狗零食推荐', '猫砂哪款好用'],
};
const CHAT_WITH_FOLLOWUPS = {
  reply: '为你找到 2 款相关商品：',
  recommendations: [],
  mode: 'mock',
  clarify: null,
  followUps: ['有没有更便宜的', '适合幼猫的吗'],
};

(async () => {
  // ══════════════════ A. 快捷问题：能力探测 → 首屏 ══════════════════
  console.log('\n[A] 快捷问题（capabilities → 首屏 chips）');

  reset();
  capRespondWith = CAP;
  const cfg = loadPage('pages/ai/ai.js');
  const inst = makePage(cfg, { input: '' });
  inst.onLoad && inst.onLoad({});
  await drain(inst);

  check('A1 能力接口的快捷问题进 data', inst.data.quickQuestions, CAP.quickQuestions);
  check('A2 首屏只有问候语（快捷问题的显示前提）', inst.data.messages.length, 1);
  ok('A3 AI 可用时不显示不可用提示', inst.data.aiReady === true && inst.data.aiNotice === '');

  // 不可用 / 取不到类目时都不该显示这一块
  reset();
  capRespondWith = { chatAvailable: false, quickQuestions: ['猫粮怎么选'] };
  const cfg2 = loadPage('pages/ai/ai.js');
  const inst2 = makePage(cfg2, { input: '' });
  inst2.onLoad && inst2.onLoad({});
  await drain(inst2);
  check('A4 AI 不可用时快捷问题为空（不给点不动的按钮）', inst2.data.quickQuestions, []);
  ok('A5 不可用时给出明确原因', inst2.data.aiNotice.indexOf('大模型') >= 0, inst2.data.aiNotice);

  reset();
  capRespondWith = { chatAvailable: true };
  const cfg3 = loadPage('pages/ai/ai.js');
  const inst3 = makePage(cfg3, { input: '' });
  inst3.onLoad && inst3.onLoad({});
  await drain(inst3);
  check('A6 类目取不到时快捷问题为空（不拿固定文案凑数）', inst3.data.quickQuestions, []);

  // ══════════════════ B. 相关追问：随响应渲染 / 换话题清掉 ══════════════════
  console.log('\n[B] 相关追问（followUps）');

  reset();
  capRespondWith = CAP;
  const cfg4 = loadPage('pages/ai/ai.js');
  const inst4 = makePage(cfg4, { input: '' });
  inst4.onLoad && inst4.onLoad({});
  await drain(inst4);
  reset();

  chatRespondWith = CHAT_WITH_FOLLOWUPS;
  inst4.send('猫粮');
  await drain(inst4);
  let msgs = inst4.data.messages;
  let lastMsg = msgs[msgs.length - 1];
  check('B1 追问随响应进 data', lastMsg.followUps, CHAT_WITH_FOLLOWUPS.followUps);
  ok('B2 聊过之后首屏快捷问题不再显示（messages>1）', msgs.length > 1, String(msgs.length));

  // 换话题：旧的追问必须和澄清选项一起清掉
  reset();
  chatRespondWith = { reply: 'ok', recommendations: [], mode: 'mock', clarify: null };
  inst4.send('狗粮');
  await drain(inst4);
  msgs = inst4.data.messages;
  ok(
    'B3 发送新消息后旧追问被清掉（不能再点）',
    msgs.every((m) => !m.followUps),
    JSON.stringify(msgs.map((m) => m.followUps))
  );
  ok(
    'B4 旧澄清选项同样被清掉（与追问同一处清理）',
    msgs.every((m) => !m.options)
  );

  // 候选为空时服务端不给追问 → 前端不渲染
  reset();
  chatRespondWith = { reply: '没找到', recommendations: [], mode: 'mock', clarify: null };
  inst4.send('不存在的东西');
  await drain(inst4);
  lastMsg = inst4.data.messages[inst4.data.messages.length - 1];
  ok('B5 服务端不给追问时为 null（不渲染空区块）', lastMsg.followUps === null, String(lastMsg.followUps));

  // ══════════════════ C. 点 chips 直接发文本 ══════════════════
  console.log('\n[C] 点 chips 直接发送（快捷问题与追问共用 tapOption）');

  reset();
  capRespondWith = CAP;
  const cfg5 = loadPage('pages/ai/ai.js');
  const inst5 = makePage(cfg5, { input: '' });
  inst5.onLoad && inst5.onLoad({});
  await drain(inst5);
  reset();
  chatRespondWith = { reply: 'ok', recommendations: [], mode: 'mock', clarify: null };

  const before = inst5.data.messages.length;
  inst5.tapOption({ currentTarget: { dataset: { send: '狗狗零食推荐' } } });
  await drain(inst5);
  ok(
    'C1 点 chips 发出的就是该文本（不经输入框）',
    captured.some((o) => {
      const b = typeof o.data === 'string' ? JSON.parse(o.data) : o.data;
      return b && b.message === '狗狗零食推荐';
    }),
    JSON.stringify(captured.map((o) => (typeof o.data === 'string' ? JSON.parse(o.data) : o.data).message))
  );
  ok('C2 点击后消息数增加（用户气泡 + 回复）', inst5.data.messages.length === before + 2);

  // ══════════════════ D. 静态契约 + 反向验证 ══════════════════
  console.log('\n[D] 静态契约与反向验证（故意改坏必须能被抓）');

  const wxml = fs.readFileSync(path.join(MNP, 'pages/ai/ai.wxml'), 'utf8');
  const wxss = fs.readFileSync(path.join(MNP, 'pages/ai/ai.wxss'), 'utf8');

  /** 快捷问题区块：条件含 messages.length<=1 与 quickQuestions.length>0，且绑定 data-send/tapOption */
  const QUICK_IF = /wx:if="{{messages\.length\s*<=\s*1\s*&&\s*quickQuestions\.length\s*>\s*0}}"/;
  /**
   * 快捷问题区块：**按块取片段**再判定。
   *
   * 早期版本用 `s.includes('data-send="{{item}}"')` 全文件匹配 —— 追问区块写法一模一样，
   * 于是把快捷问题那段改坏之后断言**仍然为真**（反向验证失败）。这类「命中了别处」的
   * 判定会让反向验证失去意义：它证明不了任何事。
   */
  const hasQuickBlock = (s) => {
    const m = QUICK_IF.exec(s);
    if (!m) return false;
    const seg = s.slice(m.index, m.index + 700);
    return (
      seg.includes('wx:for="{{quickQuestions}}"') &&
      seg.includes('data-send="{{item}}"') &&
      seg.includes('bindtap="tapOption"')
    );
  };
  /** 追问区块：与澄清选项同形（复用胶囊），但来源字段是 msg.followUps */
  const hasFollowUpBlock = (s) =>
    /wx:if="{{msg\.followUps/.test(s) &&
    s.includes('wx:for="{{msg.followUps}}"') &&
    s.includes('data-send="{{item}}"') &&
    s.includes('bindtap="tapOption"');
  /** 胶囊四条齐备（负向后视排除 line-height 误配 height —— R57 踩过这个洞） */
  const hasChipStyle = (s) => {
    const m = /\.clarify-chip\s*\{([^}]*)\}/.exec(s);
    if (!m) return false;
    const body = m[1];
    return (
      /(?<![-\w])height\s*:/.test(body) &&
      /line-height\s*:\s*1/.test(body) &&
      /border-radius\s*:/.test(body) &&
      /flex\s*:\s*none/.test(body)
    );
  };
  const hasContainerCentered = (s) => {
    const m = /\.ai-quick-list\s*\{([^}]*)\}/.exec(s);
    return !!m && /align-items\s*:\s*center/.test(m[1]);
  };

  ok('D1 wxml 有快捷问题区块且只在首屏显示', hasQuickBlock(wxml));
  ok('D2 wxml 有追问区块且绑定 data-send/tapOption', hasFollowUpBlock(wxml));
  ok('D3 胶囊样式四条齐备（height/line-height:1/border-radius/flex:none）', hasChipStyle(wxss));
  ok('D4 快捷问题容器垂直居中（防被拉伸成「框比字大」）', hasContainerCentered(wxss));
  ok('D5 追问与澄清用不同颜色（chip-muted 独立定义）', /\.chip-muted\s*\{[^}]*color\s*:/.test(wxss));

  // 反向验证：改坏后同一谓词必须转 false（否则上面的断言是空转的）
  // 只改快捷问题那一段（两个区块的 data-send 写法相同，全局替换会把追问段一起改掉，
  // 那就分不清「反向验证命中的是哪一处」—— 这类不精确的反向验证本身也是噪声源）
  const qi = wxml.indexOf('wx:for="{{quickQuestions}}"');
  const brokenQuick =
    wxml.slice(0, qi) + wxml.slice(qi).replace('data-send="{{item}}"', 'data-send=""');
  ok('D6 反向：去掉快捷问题的 data-send → D1 转 false', hasQuickBlock(wxml) && !hasQuickBlock(brokenQuick));

  const brokenFollow = wxml.replace('wx:for="{{msg.followUps}}"', 'wx:for="{{msg.recs}}"');
  ok('D7 反向：追问区块改指别的字段 → D2 转 false', hasFollowUpBlock(wxml) && !hasFollowUpBlock(brokenFollow));

  const brokenStyle = wxss.replace(/\.clarify-chip\s*\{[^}]*\}/, (s) => s.replace(/(?<![-\w])height\s*:\s*[^;]+;/, ''));
  ok('D8 反向：删掉胶囊 height → D3 转 false', hasChipStyle(wxss) && !hasChipStyle(brokenStyle));

  const brokenCenter = wxss.replace(/\.ai-quick-list\s*\{[^}]*\}/, (s) => s.replace(/align-items\s*:\s*center;?/, ''));
  ok('D9 反向：删掉容器 align-items → D4 转 false', hasContainerCentered(wxss) && !hasContainerCentered(brokenCenter));

  console.log(`\n结论：${pass} 通过 / ${fail} 失败\n`);
  process.exit(fail === 0 ? 0 : 1);
})();
