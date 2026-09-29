#!/usr/bin/env node
/**
 * 小程序静态自检（A14 修复轮新增，可挂 CI）
 *
 * 检查四类「开发者工具才会报、编译不报」的问题：
 *   1. .js 语法错误            → node --check 等价（用 vm.Script 编译，不执行）
 *   2. .json 不是合法 JSON      → JSON.parse
 *   3. WXML 绑定了不存在的方法   → 从 wxml 抽 bind- / catch- 前缀事件的处理函数名，与同目录 .js 的
 *                                「方法名集合」比对（本次排查里 coupons 的 goLogin 就是这样漏的）
 *   4. WXML 引用了未定义的数据字段 → 只做「顶层字段」粗筛：{{x}} / {{x.y}} 的 x 必须出现在
 *                                data 初始值或 setData 调用的顶层键里（避免误报，仅告警不失败）
 *   5. 引用的本地图片不存在   → 扫描 wxml/js/json 里的 images/xxx.(png|jpg|jpeg|webp)，
 *                                与磁盘比对（tabBar 图标在 app.json 里，同一条规则覆盖）
 *   6. 雪花 id 被 Number() 强转 → 超出 JS 数字精度会丢位（第五十三轮热修，error 级）
 *   7. 按钮文字会折行         → 按钮基类缺 white-space:nowrap，或横向排布的
 *                                标签/按钮缺 flex:none（第五十三轮热修二，error 级）
 *
 * 用法：node scripts/check-miniprogram.js [miniprogramDir]
 * 退出码：有 error 级问题 → 1；仅 warning → 0（便于先接入 CI 再逐步收紧）
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = process.argv[2] || path.join(__dirname, '..', 'miniprogram');

const errors = [];
const warnings = [];

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const stat = fs.statSync(full);
    if (stat.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/** 去掉注释与字符串字面量，避免把注释里的词当成方法名 */
function stripCommentsAndStrings(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/'(?:\\.|[^'\\])*'/g, "''")
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/`(?:\\.|[^`\\])*`/g, '``');
}

/** 采集 .js 里定义的方法名：`name(` 形式 + 对象属性 `name: function` / 简写 */
function collectMethods(src) {
  const clean = stripCommentsAndStrings(src);
  const names = new Set();
  const patterns = [/(?:^|[,{;\s])([A-Za-z_$][\w$]*)\s*\(/g, /([A-Za-z_$][\w$]*)\s*:\s*(?:async\s*)?(?:function|\()/g];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(clean))) names.add(m[1]);
  }
  return names;
}

/** 采集 data 初始值 + properties 声明 + setData 的顶层键（用于字段粗筛） */
function collectDataKeys(src) {
  const clean = stripCommentsAndStrings(src);
  const keys = new Set();
  // data: { ... } 的顶层 key；组件的 properties 同样对外可见，一并收进白名单
  const blocks = ['data\\s*:\\s*\\{', 'properties\\s*:\\s*\\{'];
  for (const pat of blocks) {
    const dataBlock = clean.match(new RegExp(pat));
    if (!dataBlock) continue;
    let i = dataBlock.index + dataBlock[0].length - 1;
    let depth = 0;
    let end = i;
    for (; end < clean.length; end++) {
      if (clean[end] === '{') depth++;
      else if (clean[end] === '}') {
        depth--;
        if (depth === 0) break;
      }
    }
    const body = clean.slice(i, end + 1);
    let m;
    const keyRe = /(?:^|[,{])\s*([A-Za-z_$][\w$]*)\s*:/g;
    while ((m = keyRe.exec(body))) keys.add(m[1]);
  }
  // setData({ x: ... })
  const setDataRe = /setData\s*\(\s*\{/g;
  let m;
  while ((m = setDataRe.exec(clean))) {
    let i = m.index + m[0].length - 1;
    let depth = 0;
    let end = i;
    for (; end < clean.length; end++) {
      if (clean[end] === '{') depth++;
      else if (clean[end] === '}') {
        depth--;
        if (depth === 0) break;
      }
    }
    const body = clean.slice(i, end + 1);
    const keyRe = /(?:^|[,{])\s*([A-Za-z_$][\w$]*)\s*:/g;
    let k;
    while ((k = keyRe.exec(body))) keys.add(k[1]);
  }
  return keys;
}

const files = walk(ROOT);

// ---------- 1. JS 语法 ----------
const jsFiles = files.filter((f) => f.endsWith('.js'));
for (const file of jsFiles) {
  const src = fs.readFileSync(file, 'utf8');
  try {
    new vm.Script(src, { filename: file });
  } catch (e) {
    errors.push(`[JS 语法] ${path.relative(ROOT, file)}: ${e.message}`);
  }
}

// ---------- 2. JSON 合法性 ----------
for (const file of files.filter((f) => f.endsWith('.json'))) {
  try {
    JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    errors.push(`[JSON] ${path.relative(ROOT, file)}: ${e.message}`);
  }
}

// ---------- 3/4. WXML ↔ JS 对齐 ----------
const wxmlFiles = files.filter((f) => f.endsWith('.wxml'));
for (const wxml of wxmlFiles) {
  const jsPath = wxml.replace(/\.wxml$/, '.js');
  const content = fs.readFileSync(wxml, 'utf8');
  const hasJs = fs.existsSync(jsPath);
  const jsSrc = hasJs ? fs.readFileSync(jsPath, 'utf8') : '';
  const methods = hasJs ? collectMethods(jsSrc) : new Set();
  const dataKeys = hasJs ? collectDataKeys(jsSrc) : new Set();

  if (!hasJs) {
    errors.push(`[缺 JS] ${path.relative(ROOT, wxml)} 没有同名 .js`);
    continue;
  }

  // 事件绑定：bindtap / catchtap / bindinput / bindconfirm / bindscrolltolower ...
  const handlerRe = /\b(?:bind|catch|mut-bind|capture-bind|capture-catch)[:-]?([a-zA-Z]+)\s*=\s*"([^"{}]+)"/g;
  let m;
  const checked = new Set();
  while ((m = handlerRe.exec(content))) {
    const fn = m[2].trim();
    if (!fn || checked.has(fn)) continue;
    checked.add(fn);
    if (!methods.has(fn)) {
      errors.push(
        `[事件未定义] ${path.relative(ROOT, wxml)} 绑定了 ${fn}()，但 ${path
          .relative(ROOT, jsPath)
          .replace(/\\/g, '/')} 里没有这个方法`
      );
    }
  }

  // 数据字段粗筛：只查 {{ identifier }} / {{ identifier.x }} 的第一个标识符
  const exprRe = /\{\{([^}]*)\}\}/g;
  const allowed = new Set([
    'true', 'false', 'null', 'undefined',
    // wx:for-item / wx:for-index 的自定义别名（WXML 里内联定义的循环变量）
    'item', 'index', 'idx', 'msg', 'rec', 'sku', 'act', 'seg', 'part', 'node', 'val', 'row', 'col',
  ]);
  const seenWarn = new Set();
  while ((m = exprRe.exec(content))) {
    // 先剥掉表达式里的字符串字面量：'tag-gray' 这种是 CSS 类名，不是数据字段
    const expr = m[1].replace(/'(?:\\.|[^'\\])*'/g, " '' ").replace(/"(?:\\.|[^"\\])*"/g, ' "" ');
    const idRe = /(^|[^.\w$'"`])([A-Za-z_$][\w$]*)/g;
    let id;
    while ((id = idRe.exec(expr))) {
      const name = id[2];
      if (allowed.has(name)) continue;
      if (/^\d/.test(name)) continue;
      if (dataKeys.has(name) || methods.has(name)) continue;
      // 属性访问（a.b 里的 b）跳过：前面是点号
      const idx = id.index + id[1].length;
      if (idx > 0 && expr[idx - 1] === '.') continue;
      const key = name;
      if (seenWarn.has(key)) continue;
      seenWarn.add(key);
      warnings.push(
        `[字段可疑] ${path.relative(ROOT, wxml)} 引用了 {{${name}}}，但同目录 .js 的 data/setData 顶层键里未见同名键`
      );
    }
  }
}

// ---------- 5. 本地图片存在性 ----------
// 图片路径写错在开发者工具里只表现为「图不显示」，不会编译报错，因此单列一条硬检查。
const imgRe = /['"](?:\.\.\/)*\/?images\/([A-Za-z0-9_\-./]+\.(?:png|jpg|jpeg|gif|webp))['"]/g;
const imgChecked = new Set();
for (const file of files) {
  if (!/\.(wxml|js|json)$/.test(file)) continue;
  const src = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = imgRe.exec(src))) {
    const rel = m[1];
    if (imgChecked.has(rel)) continue;
    imgChecked.add(rel);
    const target = path.join(ROOT, 'images', rel);
    if (!fs.existsSync(target)) {
      errors.push(
        `[图片缺失] ${path.relative(ROOT, file)} 引用了 images/${rel}，但文件不存在`
      );
    }
  }
}

// ---------- 6. ID 精度：雪花 id 禁止 Number() 强转 ----------
// 背景（第五十三轮热修）：雪花 id 是 19 位十进制，超出 JS Number 的 53 位有效精度 ——
//   Number('710000000000000001') === 710000000000000000（末位被吞成 0）
// 后果极隐蔽：开发者工具不报任何错，只在联调时表现为「消息怎么都发不出去」——
// 服务端拿错 id 查库 → 404「用户不存在」→ 前端文案化成「内容不存在或已删除」。
// 故单列 error 级硬检查。只查「以 Id 结尾」的标识符（receiverId/spuId/bizId…）：
// dataset.index / item.quantity 这类数值语义变量不在其列，且它们带点号本就不匹配。
const ID_NUM_RE = /Number\(\s*([A-Za-z_$][\w$]*)\s*\)/g;
for (const file of jsFiles) {
  const clean = stripCommentsAndStrings(fs.readFileSync(file, 'utf8'));
  let m;
  while ((m = ID_NUM_RE.exec(clean))) {
    const name = m[1];
    const looksLikeId = name === 'id' || name === 'Id' || /[a-z0-9_$]Id$/.test(name);
    if (!looksLikeId) continue;
    errors.push(
      `[ID 精度] ${path.relative(ROOT, file)}: Number(${name}) —— ` +
        `雪花 id 超出 JS 数字精度会丢位，必须按字符串原样上行或拼接`
    );
  }
}

// ---------- 7. 按钮文字禁止换行（第五十三轮热修二）----------
// 背景：小程序 button 自带 28rpx/侧内边距，与 flex:1 一同作用时文字框被挤窄，
// 「加入购物车」「立即购买」这类长文案就折成两行；筛选条 6 个标签挤在一行时
// 「审核中/审核失败/已下架/已售出」全部折行 —— 用户视角就是「按钮忽大忽小、与文字不协调」。
// 修法是两条硬约定：① 按钮基类一律 white-space:nowrap；② 横向排布的标签/按钮 flex:none（不被压缩）。
// 这两条一旦被后续改动抹掉就会复发，且 JS 侧测不到，故单列 error 级硬检查。
function cssBlock(src, selector) {
  const m = new RegExp('\\.' + selector + '\\s*\\{([\\s\\S]*?)\\}').exec(src);
  return m ? m[1] : null;
}
function readWxss(relPath) {
  const abs = path.join(ROOT, relPath);
  return fs.existsSync(abs) ? fs.readFileSync(abs, 'utf8') : null;
}

/**
 * 剥掉 CSS 注释。
 *
 * <p><b>不剥会出假阴性</b>（第五十四轮实测踩到）：注释里常常写出声明本身的字面形式
 * （例如「由 height 定高、line-height:1 让文字不撑行」「★ align-items:center 是必须的」），
 * 于是规则在这些**注释文本**上匹配成功 —— 声明真的被删掉了，检查却报「没问题」。
 * 这类假阴性比不检查更危险：它给出的是「已验证」的错觉。
 */
function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** 通用声明校验：指定选择器块必须含某声明（找不到块→告警；块在但缺声明→error） */
function requireDecl(targets, re, label, whyItMatters) {
  for (const t of targets) {
    const src = readWxss(t.file);
    if (src === null) continue;
    const block = cssBlock(src, t.sel);
    if (block === null) {
      warnings.push(`[布局约定] ${t.file} 未找到 .${t.sel} 规则块，无法校验${label}`);
      continue;
    }
    if (!re.test(stripCssComments(block))) {
      errors.push(
        `[布局约定] ${t.file} 的 .${t.sel}（${t.why}）缺少${label} —— ${whyItMatters}`
      );
    }
  }
}

// ① 按钮基类必须禁止换行（否则长文案折两行、按钮高度忽大忽小）
requireDecl(
  [
    { file: 'app.wxss', sel: 'btn', why: '按钮基类' },
    { file: 'app.wxss', sel: 'btn-mini', why: '小按钮基类' },
  ],
  /white-space\s*:\s*nowrap/,
  'white-space:nowrap',
  '长文案按钮会折成两行，按钮高度忽大忽小'
);

// ② 横向排布成员必须「不被压缩」（写死 flex:none；否则被容器挤窄 → 折行 → 行高不一）
requireDecl(
  [
    { file: 'pages/product/product.wxss', sel: 'footer-share', why: '详情页底部工具按钮' },
    { file: 'pages/flea-detail/flea-detail.wxss', sel: 'fd-foot-buy', why: '闲置详情购买按钮' },
    { file: 'pages/flea-market/flea-market.wxss', sel: 'fm-chip', why: '跳蚤市场筛选胶囊' },
    { file: 'pages/flea-mine/flea-mine.wxss', sel: 'fmine-filter', why: '我的闲置筛选标签' },
    { file: 'pages/flea-mine/flea-mine.wxss', sel: 'fmine-add', why: '我的闲置发布按钮' },
    { file: 'pages/flea-mine/flea-mine.wxss', sel: 'fmine-btn', why: '我的闲置操作按钮' },
    { file: 'pages/flea-mine/flea-mine.wxss', sel: 'fmine-badge', why: '状态徽章' },
  ],
  /flex\s*:\s*none/,
  'flex:none',
  '横向排布时会被挤窄导致文字折行'
);

// ③ 胶囊三项定高约定（第五十四轮）：
//    用户截图里「背景框比文字大一圈」，根因是 scroll-view 内的 flex 子项**默认按容器高拉伸**，
//    加上行高额外撑高、圆角大于半高，就成了「胖椭圆」。三条缺一不可 —— 且都不体现在 JS 里，
//    删掉任何一条都不会有编译或运行报错，只会让视觉悄悄变糟，故必须静态守住。
requireDecl(
  [{ file: 'pages/flea-market/flea-market.wxss', sel: 'fm-chip', why: '跳蚤市场筛选胶囊' }],
  /height\s*:\s*\d/,
  '显式 height',
  '没有固定高度就会随行高与容器拉伸变化，背景框大于文字'
);
requireDecl(
  [{ file: 'pages/flea-market/flea-market.wxss', sel: 'fm-chip', why: '跳蚤市场筛选胶囊' }],
  /line-height\s*:\s*1\b/,
  'line-height:1',
  '行高会额外撑高背景框，使胶囊不贴合文字'
);
requireDecl(
  [{ file: 'pages/flea-market/flea-market.wxss', sel: 'fm-chips', why: '跳蚤市场横排筛选条' }],
  /align-items\s*:\s*center/,
  'align-items:center',
  'scroll-view 子项默认按容器高拉伸，胶囊会被撑大且各排高度不一'
);

// ---------- 输出 ----------
const rel = (s) => s.replace(/\\/g, '/');
console.log('=== 小程序静态自检 ===');
console.log(`扫描：${jsFiles.length} 个 js / ${wxmlFiles.length} 个 wxml / ${files.length} 个文件 / ${imgChecked.size} 个图片引用`);
if (errors.length) {
  console.log(`\n错误（${errors.length}）：`);
  errors.forEach((e) => console.log('  ✗ ' + rel(e)));
}
if (warnings.length) {
  console.log(`\n告警（${warnings.length}，需人工确认是否误报）：`);
  warnings.forEach((w) => console.log('  ! ' + rel(w)));
}
if (!errors.length && !warnings.length) console.log('\n全部通过 ✓');
console.log(`\n结论：${errors.length} 错误 / ${warnings.length} 告警`);
process.exit(errors.length ? 1 : 0);
