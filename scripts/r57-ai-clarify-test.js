#!/usr/bin/env node
/**
 * AI 导购澄清交互 · 前端契约与页面行为 单测（第五十七轮 · M5/M6）
 *
 * 为什么必须有这一套：澄清链路的契约全在「前端拼装请求」与「前端消费响应」两头 ——
 * 服务端单测与接口探针都测不到：
 *   · `clarified` 布尔必须随每次请求上行（漏了 → AI 每轮都反问，导购变问卷）；
 *   · 响应里的 `clarify.options` 要渲染成可点 chips，`tapOption` 直接发 `send` 文本
 *     （label 与 send 分离是服务端 DTO 的设计，前端接错字段就「点了没反应」）;
 *   · 发送新消息时要**清掉旧选项**（否则历史消息里的 chips 还能点、重复发送同一条）。
 * 本测真实加载 `utils/api.js` 与 `pages/ai/ai.js`，桩掉 wx.request 捕获实际出参。
 *
 * 末节含「反向验证」：把 wxml 的选项区块与样式故意改坏，证明前面的断言真的会抓
 * （静态断言最怕变成永真的空转检查 —— R54 的教训）。
 *
 * 用法：node scripts/r57-ai-clarify-test.js      （退出码 0 = 全过）
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MNP = path.join(ROOT, 'miniprogram');

const captured = [];

// ---- 最小 wx 运行时桩 ----
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

/** 造一个页面实例：支持 setData 的路径写法（messages[0].content） */
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
function last() {
  return captured[captured.length - 1];
}
function lastPath() {
  return String(last().url || '').replace(/^https?:\/\/[^/]+/, '');
}
function lastBody() {
  const o = last();
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

/** 让页面里排队的 Promise 链跑完（send 内部是 api Promise → then → setData） */
async function drain(inst) {
  for (let i = 0; i < 5; i++) {
    await tick();
    if (!inst.data.sending) await tick();
  }
}

const CLARIFY_RES = {
  reply: '我先确认一下再帮你挑：你想买哪一类？',
  recommendations: [],
  mode: 'mock',
  clarify: {
    question: '你想买哪一类？',
    options: [
      { label: '宠物主粮', send: '宠物主粮' },
      { label: '宠物零食', send: '宠物零食' },
    ],
  },
};

(async () => {
  // ══════════════════ A. api.js 出参契约 ══════════════════
  console.log('\n[A] api.js 出参契约（clarified 必须随请求上行）');

  reset();
  api.ai.chat('推荐一下', [{ role: 'user', content: '你好' }], false);
  check('A1 chat 路径 /api/duoduo/chat', lastPath(), '/api/duoduo/chat');
  check('A2 未澄清时 clarified=false 上行', lastBody().clarified, false);
  check('A3 message 与 history 原样上行', {
    message: lastBody().message,
    history: lastBody().history,
  }, { message: '推荐一下', history: [{ role: 'user', content: '你好' }] });

  reset();
  api.ai.chat('推荐一下', [], true);
  check('A4 澄清过后 clarified=true 上行', lastBody().clarified, true);

  reset();
  api.ai.chat('推荐一下', [], undefined);
  check('A5 第三参缺省按 false 归一（不上行 undefined）', lastBody().clarified, false);

  // ══════════════════ B. 页面行为：澄清响应 ══════════════════
  console.log('\n[B] 页面行为：澄清响应渲染与状态记忆');

  // 控制 wx.request 的响应内容
  let respondWith = null;
  global.wx.request = function (opts) {
    captured.push(opts);
    if (opts.success) {
      if (respondWith === 'error') {
        opts.fail && opts.fail({ errMsg: 'request:fail' });
      } else {
        opts.success({
          statusCode: 200,
          data: { code: 'SUCCESS', message: 'ok', data: respondWith || {} },
        });
      }
    }
  };

  reset();
  const cfg = loadPage('pages/ai/ai.js');
  const inst = makePage(cfg, { input: '' });
  inst.onLoad && inst.onLoad({});
  await drain(inst);
  reset();

  // 首轮：服务端返回澄清
  respondWith = CLARIFY_RES;
  inst.send('推荐一下');
  await drain(inst);
  const firstChat = captured[0];
  check('B1 首轮请求 clarified=false', lastBody().clarified, false);
  const msgs = inst.data.messages;
  const lastMsg = msgs[msgs.length - 1];
  ok('B2 澄清消息渲染选项（options 进 data）', Array.isArray(lastMsg.options) && lastMsg.options.length === 2, JSON.stringify(lastMsg.options));
  check('B3 选项 label/send 完整透传', lastMsg.options, CLARIFY_RES.clarify.options);
  ok('B4 收到澄清后记住 _clarifiedSent', inst._clarifiedSent === true);
  ok('B5 澄清轮不带推荐卡片', Array.isArray(lastMsg.recs) && lastMsg.recs.length === 0);

  // 次轮：直接发文本 —— clarified 必须变 true，且旧选项被清掉
  reset();
  respondWith = { reply: '为你找到 1 款相关商品：', recommendations: [], mode: 'mock', clarify: null };
  inst.send('宠物主粮');
  await drain(inst);
  check('B6 次轮请求 clarified=true（最多问一次的约定）', lastBody().clarified, true);
  const after = inst.data.messages;
  ok('B7 发送新消息后旧选项被清掉（不能再点）', after.every((m) => !m.options), JSON.stringify(after.map((m) => m.options)));
  ok('B8 次轮用户消息就是选项文本', after.some((m) => m.role === 'user' && m.content === '宠物主粮'));

  // ══════════════════ C. tapOption：点选项直接发 send 文本 ══════════════════
  console.log('\n[C] tapOption 交互');

  reset();
  respondWith = CLARIFY_RES;
  const cfg2 = loadPage('pages/ai/ai.js');
  const inst2 = makePage(cfg2, { input: '' });
  inst2.onLoad && inst2.onLoad({});
  await drain(inst2);
  reset();

  respondWith = { reply: 'ok', recommendations: [], mode: 'mock', clarify: null };
  inst2.tapOption({ currentTarget: { dataset: { send: '宠物零食' } } });
  await drain(inst2);
  ok('C1 点选项发出的就是 send 文本（不经输入框）', captured.some((o) => {
    const b = typeof o.data === 'string' ? JSON.parse(o.data) : o.data;
    return b && b.message === '宠物零食';
  }), JSON.stringify(captured.map((o) => (typeof o.data === 'string' ? JSON.parse(o.data) : o.data)).map((b) => b && b.message)));

  // 无 send 值时不动作
  reset();
  const before = inst2.data.messages.length;
  inst2.tapOption({ currentTarget: { dataset: {} } });
  await drain(inst2);
  ok('C2 缺 send 时点选项不动作', inst2.data.messages.length === before);

  // sending 中不允许重复发
  reset();
  respondWith = { reply: 'x', recommendations: [], mode: 'mock', clarify: null };
  inst2.data.sending = true;
  inst2.send('再来一条');
  await drain(inst2);
  ok('C3 sending 中不发请求（防重复）', captured.length === 0);

  // ══════════════════ D. 静态契约 + 反向验证 ══════════════════
  console.log('\n[D] 静态契约与反向验证（故意改坏必须能被抓）');

  const wxml = fs.readFileSync(path.join(MNP, 'pages/ai/ai.wxml'), 'utf8');
  const wxss = fs.readFileSync(path.join(MNP, 'pages/ai/ai.wxss'), 'utf8');

  // 断言用的谓词抽出来，反向验证时对「改坏版」复用同一谓词
  const hasOptionBlock = (s) =>
    s.includes('wx:if="{{msg.options') && s.includes('bindtap="tapOption"') && s.includes('data-send="{{opt.send}}"');
  const hasChipStyle = (s) => {
    const m = /\.clarify-chip\s*\{([^}]*)\}/.exec(s);
    if (!m) return false;
    const body = m[1];
    // 负向后视排除 line-height —— 裸 height\s*: 会误配到 line-height，反向验证 D6 抓过这个洞
    return /(?<![-\w])height\s*:/.test(body) && /line-height\s*:\s*1/.test(body) && /border-radius\s*:/.test(body);
  };

  ok('D1 wxml 有选项区块且绑定 tapOption/data-send', hasOptionBlock(wxml));
  ok('D2 .clarify-chip 胶囊四条齐备（height/line-height:1/border-radius）', hasChipStyle(wxss));
  const listBlock = /\.clarify-list\s*\{([^}]*)\}/.exec(wxss);
  ok('D3 .clarify-list 容器声明 align-items:center（防 stretch 拉伸）', !!listBlock && /align-items\s*:\s*center/.test(listBlock[1]));

  // —— 反向验证：把源码改坏，谓词必须翻脸 ——
  const brokenWxml = wxml.replace(/bindtap="tapOption"/g, 'bindtap="nope"').replace('wx:if="{{msg.options', 'wx:if="{{never');
  ok('D4 反向验证：wxml 改坏后 D1 谓词必须失败', hasOptionBlock(brokenWxml) === false);
  const brokenWxss = wxss.replace(/\.clarify-chip\s*\{/, '.clarify-chip-x {');
  ok('D5 反向验证：样式类名改掉后 D2 谓词必须失败', hasChipStyle(brokenWxss) === false);
  const brokenWxss2 = wxss.replace(/\.clarify-chip\s*\{([^}]*)\}/, (w, b) => w.replace(/height\s*:[^;]+;?/, ''));
  ok('D6 反向验证：删掉 height 后 D2 谓词必须失败', hasChipStyle(brokenWxss2) === false);

  // api.js 契约静态复核：clarified 必须真出现在请求体里（防有人把参数收了不用）
  const apiSrc = fs.readFileSync(path.join(MNP, 'utils/api.js'), 'utf8');
  ok('D7 api.js chat 请求体含 clarified 字段', /clarified:\s*!!clarified/.test(apiSrc));

  // ══════════════════ 结果 ══════════════════
  console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===`);
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => {
  console.error('测试执行异常:', e);
  process.exit(1);
});
