#!/usr/bin/env node
/**
 * 分享圈补全 · 前端契约与页面行为 单测（第五十六轮新增）
 *
 * 为什么必须有这一套：本轮四块功能里，**最容易出错的地方全在前端拼装**：
 *   · 编辑话题的三态（不改 / 清空 / 替换）—— 混了会让用户永远删不掉话题，
 *     而服务端**完全正确**，接口探针永远测不到；
 *   · 评论 / 回复 / 举报 的请求体字段与路径形状（路径形状本身就是网关白名单的依据）；
 *   · 雪花 id 一旦被 Number 强转，19 位精度丢位 → 服务端 404（R53 血泪）。
 * 所以本测真实加载 `utils/api.js` 与四个页面 js，桩掉 wx.request 捕获**实际出参**，
 * 断言前端到底发了什么 —— 而不是断言服务端能接受什么。
 *
 * 用法：node scripts/r56-share-comment-test.js      （退出码 0 = 全过）
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MNP = path.join(ROOT, 'miniprogram');

const captured = [];

// ---- 最小 wx 运行时桩 ----
let modalAnswer = { confirm: false };
let toastLog = [];
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
  showToast(o) {
    toastLog.push(o && o.title);
  },
  showModal(o) {
    if (o && o.success) o.success(modalAnswer);
  },
  navigateTo() {},
  redirectTo() {},
  switchTab() {},
  reLaunch() {},
  navigateBack() {},
  stopPullDownRefresh() {},
  setNavigationBarTitle() {},
};

/** 登录桩：默认已登录（requireLogin 直接 resolve）；可切换成未登录（reject 带 needLogin） */
let loginOk = true;
global.getApp = () => ({
  globalData: {},
  requireLogin: () =>
    loginOk
      ? Promise.resolve()
      : Promise.reject(Object.assign(new Error('请先登录'), { needLogin: true })),
});

/** 桩 Page：捕获页面配置对象；每次 loadPage 重置，便于一次测多个页面 */
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

/** 造一个页面实例：支持 setData 的路径写法（items[0].content） */
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
/** 去掉 base（http://localhost:8080），只留路径与查询串 —— 断言写全串太脆 */
function pathOf(url) {
  return String(url || '').replace(/^https?:\/\/[^/]+/, '');
}
function lastPath() {
  return pathOf(last().url);
}
function anyPath(pred) {
  return captured.some((o) => pred(pathOf(o.url)));
}
function lastBody() {
  const o = last();
  if (!o || o.data == null) return null;
  // 请求层的 data 可能是对象（wx.request 自己序列化）也可能是字符串，两种都接
  return typeof o.data === 'string' ? JSON.parse(o.data) : o.data;
}
function reset() {
  captured.length = 0;
  toastLog = [];
}
/** 等一轮微任务：页面方法内部的 Promise 链需要跑完 */
function tick() {
  return new Promise((r) => setTimeout(r, 0));
}

const SHARE_ID = '363219521462484992'; // 18 位雪花，Number() 强转会丢位
const COMMENT_ID = '363241729941454848';

(async () => {
  // ══════════════════ A. api.js 出参契约 ══════════════════
  console.log('\n[A] api.js 出参契约（前端到底发了什么）');

  reset();
  api.share.publish(1001, '很好用', ['养宠日常', '新手养猫']);
  check('A1 发布：路径 /api/shares', lastPath(), '/api/shares');
  check('A2 发布：spuId 是字符串且 topics 原样上行', lastBody(), {
    spuId: '1001',
    content: '很好用',
    topics: ['养宠日常', '新手养猫'],
  });

  reset();
  api.share.publish(1001, '无心话题');
  check('A3 发布不传话题 → topics 为 []（不是 undefined）', lastBody().topics, []);

  reset();
  api.share.edit(SHARE_ID, '改过的文字', undefined);
  check('A4 编辑不传 topics → topics 为 null（=不改）', lastBody().topics, null);
  check('A5 编辑：id 以字符串拼进 URL 未被 Number 强转', lastPath(), '/api/shares/' + SHARE_ID + '/edit');

  reset();
  api.share.edit(SHARE_ID, '改过的文字', []);
  check('A6 编辑传空数组 → topics 为 []（=清空，必须与 null 区分）', lastBody().topics, []);

  reset();
  api.share.edit(SHARE_ID, '改过的文字', ['猫粮']);
  check('A7 编辑传数组 → 原样上行', lastBody().topics, ['猫粮']);

  reset();
  api.share.comment(SHARE_ID, '一级评论');
  check('A8 一级评论：POST /{id}/comments', lastPath(), '/api/shares/' + SHARE_ID + '/comments');
  check('A9 一级评论请求体不含 parentCommentId', Object.keys(lastBody()), ['content']);

  reset();
  api.share.comment(SHARE_ID, '回复', COMMENT_ID);
  check('A10 回复：parentCommentId 为字符串且原值未变', lastBody().parentCommentId, COMMENT_ID);

  reset();
  api.share.likeComment(COMMENT_ID);
  check('A11 评论点赞路径（三段，不落白名单）', lastPath(), '/api/shares/comments/' + COMMENT_ID + '/like');

  reset();
  api.share.reportComment(COMMENT_ID, '引战');
  check('A12 举报评论路径', lastPath(), '/api/shares/comments/' + COMMENT_ID + '/report');
  check('A13 举报评论请求体', lastBody(), { reason: '引战' });

  reset();
  api.share.comments(SHARE_ID, 1, 20);
  ok('A14 ★ 评论列表走查询参数（一段路径，可匿名）', lastPath().indexOf('/api/shares/comments?') === 0, lastPath());
  ok('A15 未指定 parentId 时 URL 不含 parentId', lastPath().indexOf('parentId') < 0, lastPath());

  reset();
  api.share.comments(SHARE_ID, 1, 20, COMMENT_ID);
  ok('A16 指定 parentId 时 URL 带上它（回复分页）', lastPath().indexOf('parentId=' + COMMENT_ID) > 0, lastPath());

  reset();
  api.share.mine(1, 10);
  ok('A17 我的分享路径', lastPath().indexOf('/api/shares/mine/list') === 0, lastPath());

  reset();
  api.share.remove(SHARE_ID);
  check('A18 删除分享路径', lastPath(), '/api/shares/' + SHARE_ID + '/delete');

  reset();
  api.share.report(SHARE_ID, '垃圾广告');
  check('A19 举报分享路径与请求体', lastPath(), '/api/shares/' + SHARE_ID + '/report');

  reset();
  api.share.feed(1, 10);
  ok('A20 信息流默认不带 scope（=推荐）', lastPath().indexOf('scope') < 0, lastPath());

  reset();
  api.share.feed(1, 10, 'FOLLOWING');
  ok('A21 信息流带 scope=FOLLOWING', lastPath().indexOf('scope=FOLLOWING') > 0, lastPath());

  reset();
  api.share.topicFeed('养宠日常', 1, 10);
  ok('A22 ★ 话题名做了 encodeURIComponent（中文不进 URL 原文）', lastPath().indexOf(encodeURIComponent('养宠日常')) > 0, lastPath());

  reset();
  api.share.hotTopics();
  ok('A23 热门话题路径', lastPath().indexOf('/api/shares/topics?limit=') === 0, lastPath());

  // 反回归：任何出参里都不得出现被 Number 强转后的 id
  const truncated = String(Number(SHARE_ID));
  const leaked = captured.some((o) => JSON.stringify(o.url || '').indexOf(truncated) >= 0);
  ok('A24 反回归：精度丢失后的 id 值没有出现在任何出参 URL 里', !leaked, '丢位值=' + truncated);
  ok('A25 ★ 反回归：Number(SHARE_ID) 确实丢位（故前端绝不能强转）', String(Number(SHARE_ID)) !== SHARE_ID, SHARE_ID + ' → ' + String(Number(SHARE_ID)));

  // ══════════════════ B. 我的分享：编辑三态与删除 ══════════════════
  console.log('\n[B] 我的分享页（编辑 / 删除）');
  const mineCfg = loadPage('pages/share-mine/share-mine.js');

  const mineItem = {
    id: SHARE_ID,
    content: '原来的评价',
    topics: ['养宠日常', '新手养猫'],
    parentId: null,
    likeCount: 1,
    favoriteCount: 2,
    commentCount: 3,
  };
  const mine = makePage(mineCfg, { items: [mineItem] });

  mine.openEdit({ currentTarget: { dataset: { id: SHARE_ID } } });
  check('B1 打开编辑：正文回填', mine.data.editContent, '原来的评价');
  check('B2 打开编辑：话题用空格连接回填', mine.data.editTopics, '养宠日常 新手养猫');

  reset();
  const mine2 = makePage(mineCfg, {
    items: [Object.assign({}, mineItem)],
    editTarget: Object.assign({}, mineItem),
    editContent: '改完的',
    editTopics: '',
  });
  mine2.saveEdit();
  await tick();
  check('B3 ★ 话题输入为空 → 上行 []（清空）而不是 null（不改）', lastBody().topics, []);

  reset();
  const mine3 = makePage(mineCfg, {
    items: [],
    editTarget: { id: SHARE_ID, parentId: null },
    editContent: '   ',
    editTopics: '',
  });
  mine3.saveEdit();
  await tick();
  check('B4 原创且内容为空 → 本地拦截，不发请求', captured.length, 0);
  ok('B5 且给出提示', toastLog.indexOf('分享内容不能为空') >= 0, toastLog.join('|'));

  reset();
  const mine4 = makePage(mineCfg, {
    items: [],
    editTarget: { id: SHARE_ID, parentId: '999' },
    editContent: '',
    editTopics: '',
  });
  mine4.saveEdit();
  await tick();
  check('B6 转发单允许清空附言 → 仍发请求', captured.length, 1);

  reset();
  const mine5 = makePage(mineCfg, {
    items: [],
    editTarget: { id: SHARE_ID, parentId: null },
    editContent: 'x',
    editTopics: '#猫粮、冻干 狗粮 多余的第三个',
  });
  mine5.saveEdit();
  await tick();
  check('B7 话题输入按 # / 顿号 / 空格切分并截断到 2 个', lastBody().topics, ['猫粮', '冻干']);

  reset();
  modalAnswer = { confirm: true };
  const mine6 = makePage(mineCfg, { items: [Object.assign({}, mineItem)], total: 1 });
  mine6.confirmDelete({ currentTarget: { dataset: { id: SHARE_ID } } });
  await tick();
  check('B8 删除：二次确认后调用删除接口', lastPath(), '/api/shares/' + SHARE_ID + '/delete');
  check('B9 删除成功后从列表移除', mine6.data.items.length, 0);

  reset();
  modalAnswer = { confirm: false };
  const mine7 = makePage(mineCfg, { items: [Object.assign({}, mineItem)], total: 1 });
  mine7.confirmDelete({ currentTarget: { dataset: { id: SHARE_ID } } });
  await tick();
  check('B10 取消确认 → 不发请求', captured.length, 0);
  check('B11 取消确认 → 列表不变', mine7.data.items.length, 1);

  // ══════════════════ C. 分享圈：双 tab 与举报 ══════════════════
  console.log('\n[C] 分享圈（推荐 / 关注 双 tab + 举报）');
  const circleCfg = loadPage('pages/share-circle/share-circle.js');

  loginOk = false;
  reset();
  const c1 = makePage(circleCfg, { items: [mineItem], scope: 'RECOMMEND' });
  c1.switchScope({ currentTarget: { dataset: { scope: 'FOLLOWING' } } });
  await tick();
  check('C1 ★ 未登录切「关注」→ 不发请求（先引导登录）', captured.length, 0);
  check('C2 且不改变当前范围', c1.data.scope, 'RECOMMEND');

  loginOk = true;
  reset();
  const c2 = makePage(circleCfg, { items: [mineItem], scope: 'RECOMMEND', page: 3 });
  c2.switchScope({ currentTarget: { dataset: { scope: 'FOLLOWING' } } });
  await tick();
  check('C3 已登录切「关注」→ 清空列表再请求', c2.data.items.length, 0);
  check('C4 且范围已切换', c2.data.scope, 'FOLLOWING');
  ok('C5 且请求带上 scope=FOLLOWING', lastPath().indexOf('scope=FOLLOWING') > 0, lastPath());

  reset();
  const c3 = makePage(circleCfg, { items: [mineItem], scope: 'FOLLOWING' });
  c3.switchScope({ currentTarget: { dataset: { scope: 'RECOMMEND' } } });
  await tick();
  ok('C6 切回「推荐」不需要登录，且 URL 不带 scope（服务端默认值不显式传）', captured.length === 1 && lastPath().indexOf('scope') < 0, lastPath());

  reset();
  const c4 = makePage(circleCfg, { items: [mineItem] });
  c4.openReport({ currentTarget: { dataset: { id: SHARE_ID } } });
  check('C7 打开举报弹层并记住目标', c4.data.reportTarget.id, SHARE_ID);
  c4.pickReport({ currentTarget: { dataset: { index: 0 } } });
  await tick();
  check('C8 选理由即提交，理由取预置项', lastBody(), { reason: '垃圾广告' });
  check('C9 提交后关闭弹层', c4.data.reportPanel, false);

  // ══════════════════ D. 分享详情：评论区 ══════════════════
  console.log('\n[D] 分享详情（评论 / 回复）');
  const detailCfg = loadPage('pages/share-detail/share-detail.js');

  reset();
  const d1 = makePage(detailCfg, { comments: [], commentInput: '   ', commentTotal: 0 });
  d1.shareId = SHARE_ID;
  d1.submitComment();
  await tick();
  check('D1 空白评论 → 不发请求', captured.length, 0);

  reset();
  const d2 = makePage(detailCfg, { comments: [], commentInput: '好物', commentTotal: 0 });
  d2.shareId = SHARE_ID;
  d2.submitComment();
  await tick();
  check('D2 一级评论 → 请求路径正确', lastPath(), '/api/shares/' + SHARE_ID + '/comments');
  check('D3 一级评论成功后追加到列表末尾', d2.data.comments.length, 1);
  check('D4 评论总数 +1', d2.data.commentTotal, 1);
  check('D5 输入框已清空', d2.data.commentInput, '');

  reset();
  const d3 = makePage(detailCfg, {
    comments: [{ id: COMMENT_ID, nickname: '甲', replyCount: 0, replies: [] }],
    commentInput: '回复内容',
    replyTo: { id: COMMENT_ID, nickname: '甲', index: 0 },
    commentTotal: 1,
  });
  d3.shareId = SHARE_ID;
  d3.submitComment();
  await tick();
  check('D6 回复 → 请求体带 parentCommentId', lastBody().parentCommentId, COMMENT_ID);
  check('D7 回复挂到该评论下', d3.data.comments[0].replies.length, 1);
  check('D8 并自动展开', d3.data.comments[0].expanded, true);
  check('D9 该评论回复数 +1', d3.data.comments[0].replyCount, 1);

  // ══════════════════ E. 话题页 ══════════════════
  console.log('\n[E] 话题聚合页 / 广场');
  const topicCfg = loadPage('pages/share-topic/share-topic.js');

  reset();
  const t1 = makePage(topicCfg, {});
  t1.onLoad({});
  await tick();
  ok('E1 无 name → 拉热门话题榜（广场形态）', lastPath().indexOf('/api/shares/topics?limit=') === 0, lastPath());

  reset();
  const t2 = makePage(topicCfg, {});
  t2.onLoad({ name: encodeURIComponent('养宠日常') });
  await tick();
  ok('E2 有 name → 拉该话题下的分享（本页会额外拉一次热门榜取计数，故用 anyPath）',
    anyPath((u) => u.indexOf('/api/shares/topics/' + encodeURIComponent('养宠日常')) === 0),
    captured.map(pathOf).join(' | '));

  // ══════════════════ F. 胶囊与横排样式纪律（剥注释后匹配） ══════════════════
  console.log('\n[F] 胶囊样式四条齐备（静态）');

  function stripCssComments(src) {
    return src.replace(/\/\*[\s\S]*?\*\//g, '');
  }
  function block(src, selector) {
    const m = new RegExp(
      selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^}]*)\\}'
    ).exec(src);
    return m ? m[1] : '';
  }
  function decl(b, prop) {
    const m = new RegExp('(?:^|;)\\s*' + prop + '\\s*:\\s*([^;]+)').exec(b);
    return m ? m[1].trim() : '';
  }
  function pillOk(file, chipSel, wrapSel) {
    const src = stripCssComments(fs.readFileSync(path.join(MNP, file), 'utf8'));
    const chip = block(src, chipSel);
    const wrap = block(src, wrapSel);
    const h = parseInt(decl(chip, 'height'), 10);
    const lh = decl(chip, 'line-height');
    const radius = parseInt(decl(chip, 'border-radius'), 10);
    const wrapAlign = decl(wrap, 'align-items');
    const problems = [];
    if (!h) problems.push('缺显式 height');
    if (lh !== '1') problems.push('line-height 不是 1（实际 ' + lh + '）');
    if (!radius || radius * 2 !== h) problems.push('半径不是半高（h=' + h + ' r=' + radius + '）');
    if (wrapAlign !== 'center') problems.push('容器缺 align-items:center（会被拉伸）');
    return { problems, h: h, radius: radius, wrapAlign: wrapAlign };
  }

  const f1 = pillOk('pages/share-circle/share-circle.wxss', '.sc-tab', '.sc-tabs');
  ok('F1 分享圈 tab 胶囊四条齐备', f1.problems.length === 0, f1.problems.join('，'));
  const f2 = pillOk('pages/share-circle/share-circle.wxss', '.sc-topic', '.sc-topics');
  ok('F2 分享圈话题胶囊四条齐备', f2.problems.length === 0, f2.problems.join('，'));
  const f3 = pillOk('pages/share-detail/share-detail.wxss', '.sd-topic', '.sd-topics');
  ok('F3 详情页话题胶囊四条齐备', f3.problems.length === 0, f3.problems.join('，'));
  const f4 = pillOk('pages/share-mine/share-mine.wxss', '.sm-topic', '.sm-topics');
  ok('F4 我的分享话题胶囊四条齐备', f4.problems.length === 0, f4.problems.join('，'));
  const f5 = pillOk('pages/share-topic/share-topic.wxss', '.st-topic', '.st-topics');
  ok('F5 话题页话题胶囊四条齐备', f5.problems.length === 0, f5.problems.join('，'));

  // 反向验证：把 line-height 改掉应当被抓出来
  const tmpFile = 'pages/share-topic/share-topic.wxss';
  const original = fs.readFileSync(path.join(MNP, tmpFile), 'utf8');
  fs.writeFileSync(
    path.join(MNP, tmpFile),
    original.replace(/line-height:\s*1;/, 'line-height: 1.5;')
  );
  const broken = pillOk(tmpFile, '.st-topic', '.st-topics');
  fs.writeFileSync(path.join(MNP, tmpFile), original);
  ok('F6 ★ 反向验证：改坏 line-height 能被抓出（规则不空转）', broken.problems.length > 0, broken.problems.join('，'));

  // ══════════════════ G. 页面清单与白名单同步 ══════════════════
  console.log('\n[G] 页面清单 / 后端白名单同步');

  const appJson = JSON.parse(fs.readFileSync(path.join(MNP, 'app.json'), 'utf8'));
  const pages = appJson.pages.map((p) => '/' + p);
  ok('G1 app.json 含「我的分享」页', pages.indexOf('/pages/share-mine/share-mine') >= 0, pages.length + ' 页');
  ok('G2 app.json 含「话题」页', pages.indexOf('/pages/share-topic/share-topic') >= 0, pages.length + ' 页');

  const java = fs.readFileSync(
    path.join(ROOT, 'duoduo-social/src/main/java/com/duoduo/social/support/MiniProgramPages.java'),
    'utf8'
  );
  const declared = (java.match(/"\/(pages\/[^"]+)"/g) || []).map((s) => s.replace(/"/g, ''));
  const missing = pages.filter((p) => declared.indexOf(p) < 0);
  const extra = declared.filter((p) => pages.indexOf(p) < 0);
  ok('G3 ★ MiniProgramPages 与 app.json pages 一一对应', missing.length === 0 && extra.length === 0,
    '缺=' + JSON.stringify(missing) + ' 多=' + JSON.stringify(extra));

  ['js', 'json', 'wxml', 'wxss'].forEach((ext) => {
    const base = 'pages/share-mine/share-mine.';
    ok('G4 我的分享页有 .' + ext, fs.existsSync(path.join(MNP, base + ext)), base + ext);
  });
  ['js', 'json', 'wxml', 'wxss'].forEach((ext) => {
    const base = 'pages/share-topic/share-topic.';
    ok('G5 话题页有 .' + ext, fs.existsSync(path.join(MNP, base + ext)), base + ext);
  });

  // 通知落地页白名单覆盖新页面（分享评论通知落在 share-detail，这里顺带守住它仍在）
  ok('G6 分享详情页仍在白名单（评论通知的落地页）', declared.indexOf('/pages/share-detail/share-detail') >= 0, 'ok');

  console.log('\n' + '='.repeat(74));
  console.log(`结果：${pass} 通过 / ${fail} 失败`);
  console.log('='.repeat(74));
  process.exit(fail ? 1 : 0);
})();
