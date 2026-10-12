#!/usr/bin/env node
/**
 * 图表主题接入点契约守卫（S10 前半，2026-10-12）
 *
 * <p><b>为什么需要它</b>：`chartTheme.ts` 的 `currentChartThemeMode()` 是「全站图表主题的
 * 唯一接入点」，它的价值全在「将来做全局深色主题时只改这一处」。这个价值有个前提：
 *   ① 函数真的读 `<html data-theme>`（不是写死 `'light'` 的死代码）；
 *   ② 取值回落正确（未设置/其他值 → `light`，只有精确 `dark` 才切深色）；
 *   ③ 消费端（`charts.tsx`）在**组件内**求值 —— 模块级常量只在首次加载算一次，主题切了不生效；
 *   ④ 不引入 `prefers-color-scheme`（那会造成浅底 + 深轴线的不可读组合）。
 * 四条都是「不写测试就会静默腐烂」的约定（铁律 19：凡降级/兜底，必写能走到那个分支的测试），
 * 因此固化成守卫，接入 `check-all.ps1` 的 `[0/5]` 静态守卫段与 CI。
 *
 * <p><b>判据分两层</b>：
 *   · 动态：真 import 该模块，替换 `globalThis.document` 后调用，验 7 种取值的返回值；
 *   · 静态：读源码文本，验接入点与消费端写法（①③④）。
 * ★ 扫描行数为 0 直接判违规 —— 「什么都没扫到」与「扫了、结果干净」长得一模一样。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const SRC = path.join(REPO, 'frontend', 'packages', 'shared', 'src', 'components');
const CHART_THEME = path.join(SRC, 'chartTheme.ts');
const CHARTS = path.join(SRC, 'charts.tsx');

let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) { pass++; console.log(`  [OK]   ${name}`); }
  else { fail++; console.log(`  [FAIL] ${name}${detail ? '  —— ' + detail : ''}`); }
};

console.log('[check-chart-theme-contract] 图表主题接入点契约（S10）');

// ---------- 先读源码：读不到/扫到 0 行直接判违规 ----------
const srcTheme = fs.readFileSync(CHART_THEME, 'utf8');
const srcCharts = fs.readFileSync(CHARTS, 'utf8');
const themeLines = srcTheme.split('\n').length;
const chartsLines = srcCharts.split('\n').length;
console.log(`  源文件：chartTheme.ts ${themeLines} 行 / charts.tsx ${chartsLines} 行`);
check('两个源文件都已扫到（扫描数 0 判违规）',
  themeLines > 50 && chartsLines > 50, `theme=${themeLines} charts=${chartsLines}`);

// ① 接入点真的在读 data-theme
check('① 接入点读 document.documentElement.dataset.theme',
  /dataset\??\.theme/.test(srcTheme), '未找到 dataset.theme 读取');

// ④ 代码里不得出现 prefers-color-scheme（注释中说明「刻意不读」是允许的）
const codeOnlyTheme = srcTheme.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
check('④ 代码中不出现 prefers-color-scheme（仅在注释里说明）',
  !/prefers-color-scheme/.test(codeOnlyTheme), '代码里出现了 prefers-color-scheme');

// ③ 消费端必须在组件内求值
check('③ charts.tsx 没有模块级 const T = chartTheme()',
  !/^const T = chartTheme\(\);$/m.test(srcCharts), '发现模块级 const T = chartTheme()');
check('③ charts.tsx 在组件内调用 chartTheme()',
  /^\s+const T = chartTheme\(\);$/m.test(srcCharts), '组件内未找到 chartTheme() 调用');
check('③ charts.tsx 的 useMemo 依赖含 T（主题变化能重算 option）',
  /\[type, data, money, unit, T\]/.test(srcCharts), 'useMemo 依赖未含 T');

// ---------- 动态：替换 globalThis.document 后真调用 ----------
const { currentChartThemeMode } =
  await import(new URL('../../frontend/packages/shared/src/components/chartTheme.ts', import.meta.url).href);

const savedDoc = globalThis.document;
const setDoc = (v) => { if (v === undefined) delete globalThis.document; else globalThis.document = v; };
const withTheme = (theme) => ({ documentElement: { dataset: theme === undefined ? {} : { theme } } });
const got = () => { try { return currentChartThemeMode(); } catch (e) { return `THREW:${e.message}`; } };

try {
  setDoc(undefined);
  check('② 无 document（SSR / 单测环境）→ light', got() === 'light', String(got()));
  setDoc({ documentElement: { dataset: {} } });
  check('② document 存在但无 data-theme → light', got() === 'light', String(got()));
  setDoc(withTheme('light'));
  check('② data-theme="light" → light', got() === 'light', String(got()));
  setDoc(withTheme('dark'));
  check('★ data-theme="dark" → dark（证明接入点真的活着，不是死代码）', got() === 'dark', String(got()));
  setDoc(withTheme(''));
  check('② data-theme="" → light（空串回落）', got() === 'light', String(got()));
  setDoc(withTheme('DARK'));
  check('② data-theme="DARK" → light（只认精确小写 dark）', got() === 'light', String(got()));
  setDoc({ documentElement: undefined });
  check('② documentElement 缺失时不抛错 → light', got() === 'light', String(got()));
} finally {
  if (savedDoc === undefined) delete globalThis.document; else globalThis.document = savedDoc;
}

console.log(`  结果：PASS=${pass} FAIL=${fail}`);
console.log(fail === 0
  ? '  结论：通过 —— 接入点读 data-theme、回落正确、消费端在组件内求值、未引入 prefers-color-scheme。'
  : '  结论：不通过 —— 见上方 [FAIL] 项。');
process.exit(fail === 0 ? 0 : 1);
