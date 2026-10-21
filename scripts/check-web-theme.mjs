/**
 * A2 · Web 端暗夜模式静态守卫（2026-10-20）。
 *
 * 为什么需要它：暗夜模式的回归很隐蔽 —— 有人往组件里写一个 #fff 的硬编码色，
 * 深色模式下就是一块刺眼的白色，而浅色模式看不出来、CI 也不会报红。
 * 这里用静态检查把三类回归挡住：变量缺失、硬编码色回潮、深色档没跟着改。
 *
 * 用法：node scripts/check-web-theme.mjs
 * 退出码：0 全过；1 有失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

let pass = 0;
const fails = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    fails.push(name);
    console.log(`  [FAIL] ${name}${got === undefined ? '' : `  实际=${JSON.stringify(got)}`}`);
  }
}

const css = fs.readFileSync(path.join(root, 'frontend/packages/shared/src/styles.css'), 'utf8');

// ---------- 1) 变量体系：浅色档与深色档必须成对 ----------
check('定义了 :root 浅色变量', css.includes(':root {'));
check('定义了 html[data-theme="dark"] 深色档', /html\[data-theme='dark'\]\s*\{/.test(css));

function varsIn(selectorRegex) {
  const m = css.match(selectorRegex);
  if (!m) return [];
  // 取该选择器的花括号内容
  const start = css.indexOf('{', m.index);
  let depth = 0;
  let end = start;
  for (let i = start; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}') {
      depth--;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  return [...css.slice(start, end).matchAll(/(--[a-z0-9-]+)\s*:/g)].map((x) => x[1]);
}

const lightVars = varsIn(/:root \{/);
const darkVars = varsIn(/html\[data-theme='dark'\] \{/);

// 深色档必须覆盖所有"颜色类"变量；尺寸/圆角/动效令牌刻意不覆盖（切换时不能跳动）
const COLOR_VARS = [
  '--brand', '--brand-dark', '--brand-soft',
  '--accent', '--accent-soft',
  '--danger', '--danger-soft',
  '--warn', '--warn-soft',
  '--success', '--success-soft',
  '--bg', '--surface', '--surface-2', '--border', '--border-strong',
  '--text', '--text-2', '--text-3',
  '--brand-strong', '--accent-strong', '--danger-strong', '--warn-strong', '--success-strong',
  '--brand-line', '--brand-line-soft',
  // 顶栏毛玻璃底：写死 rgba(255,...) 的回归就是暗夜档整条白导航（2026-10-21 用户报障）
  '--header-bg',
];
for (const v of COLOR_VARS) {
  check(`深色档覆盖了 ${v}`, darkVars.includes(v), v);
}
check('浅色档也定义了同样数量的颜色变量（两档成对）', COLOR_VARS.every((v) => lightVars.includes(v)));

// 语义断言：只查"变量存在"是不够的 —— 把深色档的 --bg 写成浅色，
// 变量照样齐全、CSS 照样合法，但暗夜模式等于没做。必须比亮度。
function valueOf(selectorRegex, name) {
  const block = varsIn(selectorRegex);
  const m = css.match(new RegExp(`\\$\\{name\\}\\.`));
  const re = new RegExp('(?<![-a-z0-9])' + name + '\\s*:\\s*(#[0-9a-fA-F]{6})');
  const mm = css.match(selectorRegex);
  if (!mm) return null;
  const start = css.indexOf('{', mm.index);
  let depth = 0;
  let end = start;
  for (let i = start; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}') {
      depth--;
      if (depth === 0) { end = i; break; }
    }
  }
  const hit = css.slice(start, end).match(re);
  return hit ? hit[1].toLowerCase() : null;
}
function luminance(hex) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return 0.299 * r + 0.587 * g + 0.114 * b;
}
const PAIRS = [
  ['--bg', '页面底色'],
  ['--surface', '卡片底色'],
  ['--border', '边框'],
];
for (const [v, label] of PAIRS) {
  const l = valueOf(/:root \{/, v);
  const d = valueOf(/html\[data-theme='dark'\] \{/, v);
  const ok = !!(l && d) && luminance(d) < luminance(l) - 40;
  check(`深色档的${label}确实比浅色档暗（${v}）`, ok, `light=${l}(${l && Math.round(luminance(l))}) dark=${d}(${d && Math.round(luminance(d))})`);
}
// 文字方向相反：深色档的文字必须更亮
for (const [v, label] of [['--text', '正文'], ['--text-2', '次级文字']]) {
  const l = valueOf(/:root \{/, v);
  const d = valueOf(/html\[data-theme='dark'\] \{/, v);
  const ok = !!(l && d) && luminance(d) > luminance(l) + 40;
  check(`深色档的${label}确实比浅色档亮（${v}）`, ok, `light=${l} dark=${d}`);
}

// 深色档**不得**改尺寸与动效：主题切换伴随布局跳动/快慢不一是更糟的体验
const MUST_NOT_OVERRIDE = ['--radius', '--radius-sm', '--radius-lg', '--header-h', '--max-w',
  '--dur-fast', '--dur-base', '--ease-out'];
for (const v of MUST_NOT_OVERRIDE) {
  check(`深色档未改动 ${v}（避免切换时布局跳动/快慢不一）`, !darkVars.includes(v), v);
}

// ---------- 1.5) 2026-10-21 两起真实回归的针对性断言 ----------
// 顶栏：.app-header 的背景必须引用变量。浅色档的 rgba(255,255,255,.92) 当时写死在规则里，
// 深色档没有任何覆盖，结果暗夜下整条导航是白的 —— 页面其余部分全黑，就它亮着。
const headerStart = css.indexOf('.app-header {');
const headerBlock = headerStart >= 0 ? css.slice(headerStart, css.indexOf('}', headerStart)) : '';
check('.app-header 背景引用 var(--header-bg)（不得写死白色）',
  /background:\s*var\(--header-bg\)/.test(headerBlock) && !/rgba\(255/.test(headerBlock));
check('深色档覆盖了 --header-bg 且基色是深色',
  /--header-bg:\s*rgba\(23,\s*26,\s*33/.test(css));
// Toast：这三个 --c-* 只被 Toast 当背景、文字固定白字 —— 深色档一旦按常规曲线映射成浅色，
// 就是"白字压浅底"几乎不可读。必须保持深底（亮度 < 110）。
for (const v of ['--c-1f2430', '--c-10894c', '--c-c0292e']) {
  const d = valueOf(/html\[data-theme='dark'\] \{/, v);
  check(`深色档 ${v}（Toast 底色）保持深底`, !!d && luminance(d) < 110, d);
}

// ---------- 2) 三端都挂了 Provider 与切换按钮 ----------
for (const app of ['mall', 'admin', 'merchant']) {
  const entry = app === 'mall'
    ? 'frontend/apps/mall/src/main.tsx'
    : `frontend/apps/${app}/src/${app}-main.tsx`;
  const src = fs.readFileSync(path.join(root, entry), 'utf8');
  check(`${app} 入口挂载了 ThemeProvider`, src.includes('<ThemeProvider>'));
}
for (const [app, file] of [
  ['mall', 'frontend/apps/mall/src/components/Layout.tsx'],
  ['admin', 'frontend/apps/admin/src/pages/admin/AdminRoot.tsx'],
  ['merchant', 'frontend/apps/merchant/src/pages/merchant/MerchantRoot.tsx'],
]) {
  const src = fs.readFileSync(path.join(root, file), 'utf8');
  check(`${app} 顶栏放置了 ThemeToggle`, src.includes('<ThemeToggle'));
}

// ---------- 3) 全局样式里不得回潮硬编码颜色 ----------
// 允许白名单：白色文字压在彩色按钮上是合理的（它随主题不变），以及透明/继承。
const HEX = /#([0-9a-f]{6})\b/gi;
const body = css.slice(css.indexOf('body {'));
const remaining = [...body.matchAll(HEX)].map((m) => m[1].toLowerCase());
const WHITELIST = new Set(['ffffff', 'fff']);
const offenders = [...new Set(remaining.filter((h) => !WHITELIST.has(h)))];
check('全局样式正文无硬编码颜色（白色除外）', offenders.length === 0, offenders);

console.log(`\n=== Web 端暗夜模式守卫：通过 ${pass} 项，失败 ${fails.length} 项 ===`);
if (fails.length) {
  for (const f of fails) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
