/**
 * A2 · 小程序端暗夜模式静态守卫（2026-10-21）。
 *
 * 与 Web 端守卫（check-web-theme.mjs）同一思路：变量成对、亮度方向正确、
 * 硬编码不回潮。另加小程序特有三项：darkmode 开关、theme.json 两档、
 * 自绘导航栏暗色覆盖。
 *
 * 用法：node scripts/check-miniprogram-dark.mjs
 * 退出码：0 全过；1 有失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', 'miniprogram');

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

// ---------- 1) 原生层：darkmode 开关 + theme.json 两档 ----------
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
check('app.json 开启 darkmode', appJson.darkmode === true, appJson.darkmode);
check('app.json 声明 themeLocation', appJson.themeLocation === 'theme.json', appJson.themeLocation);

const theme = JSON.parse(fs.readFileSync(path.join(root, 'theme.json'), 'utf8'));
for (const mode of ['light', 'dark']) {
  check(`theme.json 存在 ${mode} 档`, !!theme[mode], Object.keys(theme));
}
const THEME_KEYS = ['navBgColor', 'navTxtStyle', 'bgColor', 'bgTxtStyle', 'tabBgColor', 'tabFontColor', 'tabSelectedColor'];
for (const k of THEME_KEYS) {
  check(`theme.json 两档都有 ${k}`, !!theme.light?.[k] && !!theme.dark?.[k], k);
}
check('app.json window 引用 @navBgColor', JSON.stringify(appJson.window).includes('@navBgColor'));
check('app.json tabBar 引用 @tabBgColor', JSON.stringify(appJson.tabBar).includes('@tabBgColor'));

// 亮度断言：暗色档的导航/tabBar 底必须比浅色档暗
function luminance(hex) {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return 0.299 * r + 0.587 * g + 0.114 * b;
}
check('暗色档 tabBar 底比浅色档暗',
  luminance(theme.dark.tabBgColor) < luminance(theme.light.tabBgColor) - 40,
  `${theme.light.tabBgColor} → ${theme.dark.tabBgColor}`);

// ---------- 2) wxss 变量：暗色覆盖核心 token ----------
const appWxss = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
check('app.wxss 存在 prefers-color-scheme dark 块', /@media\s*\(\s*prefers-color-scheme:\s*dark\s*\)/.test(appWxss));
const darkBlock = appWxss.match(/@media\s*\(\s*prefers-color-scheme:\s*dark\s*\)\s*\{[\s\S]*?\n\}/)?.[0] ?? '';
for (const v of ['--text', '--text-2', '--text-3', '--bg', '--card', '--line']) {
  check(`暗色块覆盖了 ${v}`, darkBlock.includes(`${v}:`), v);
}
function wxssVar(block, name) {
  const m = block.match(new RegExp(name.replace(/-/g, '\\-') + ':\\s*(#[0-9a-fA-F]{6})'));
  return m ? m[1].toLowerCase() : null;
}
const lightText = wxssVar(appWxss.match(/page\s*\{[\s\S]*?\n\}/)?.[0] ?? '', '--text');
const darkText = wxssVar(darkBlock, '--text');
const lightBg = wxssVar(appWxss.match(/page\s*\{[\s\S]*?\n\}/)?.[0] ?? '', '--bg');
const darkBg = wxssVar(darkBlock, '--bg');
check('暗色正文比浅色正文亮', !!lightText && !!darkText && luminance(darkText) > luminance(lightText) + 40,
  `${lightText} → ${darkText}`);
check('暗色页面底比浅色暗', !!lightBg && !!darkBg && luminance(darkBg) < luminance(lightBg) - 40,
  `${lightBg} → ${darkBg}`);

// ---------- 3) 自绘导航栏：暗色覆盖存在 ----------
const navWxss = fs.readFileSync(path.join(root, 'components/nav-bar/nav-bar.wxss'), 'utf8');
check('nav-bar 组件有暗色覆盖（品牌蓝压暗）',
  /@media\s*\(\s*prefers-color-scheme:\s*dark\s*\)/.test(navWxss) && navWxss.includes('#2b3550'));

// ---------- 4) 硬编码不回潮 ----------
// 页面 wxss 的 background 不得再出现 #fff / #ffffff 字面量（改用 var(--card)）。
// 白名单：无 —— 白字走 color，background 的白一律是卡片底。
const offenders = [];
for (const dir of ['pages', 'components']) {
  const walk = (d) => {
    for (const f of fs.readdirSync(path.join(root, d), { withFileTypes: true })) {
      const p = path.join(root, d, f.name);
      if (f.isDirectory()) walk(path.join(d, f.name));
      else if (f.name.endsWith('.wxss')) {
        const s = fs.readFileSync(p, 'utf8');
        const m = s.match(/background(?:-color)?:\s*[^;]*#fff(fff)?\b/);
        if (m) offenders.push(path.relative(root, p));
      }
    }
  };
  walk(dir);
}
check('页面 wxss 无 background #fff 硬编码（防回潮）', offenders.length === 0, offenders);

console.log(`\n=== 小程序暗夜模式守卫：通过 ${pass} 项，失败 ${fails.length} 项 ===`);
if (fails.length) {
  for (const f of fails) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
