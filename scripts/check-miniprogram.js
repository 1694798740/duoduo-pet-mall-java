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
