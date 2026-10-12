/**
 * 图表主题（R74-6）：**全站图表配色的唯一来源**。
 *
 * <p><b>为什么要有这个文件</b>：图表配色原先直接写在 `components/charts.tsx` 里（虽然已集中在一处，
 * 但只有浅色一套，且没有任何「换主题」的接入点）。一旦将来做全局深色主题，
 * 改配色就得回到图表实现里翻常量 —— 而且很容易只改一半（改了三处漏第五处），
 * 结果出现「深色坐标轴文字压在浅色卡片上」这种不可读的组合。
 *
 * <p><b>当前状态（如实说明）</b>：**应用本身还没有深色主题**
 * （`styles.css` 只有一套浅色变量，没有任何地方写 `data-theme`、也没有 `prefers-color-scheme` 分支）。
 * 因此 {@link currentChartThemeMode} 在运行时实际仍恒为 `'light'`，dark 一套是**备好的**、
 * 暂时没有真实消费方 —— 这是刻意的：不做「只有图表变深」的半成品（那会让图表与卡片背景打架）。
 *
 * <p><b>一条明确的约定</b>：图表主题**跟随应用主题，不跟随操作系统的 `prefers-color-scheme`**。
 * 原因：应用卡片背景是固定的浅色，图表若自行按系统偏好变深，就会出现浅底 + 浅字/深轴线的不可读组合。
 * 要支持深色，正确路径是**先把全局主题做了**（CSS 变量 + `data-theme`），再让本文件跟随它。
 * e2e（`admin-charts-ui-check.mjs`）里有一条断言专门钉住这个约定，防止将来只改图表造成的半修复。
 *
 * <p><b>怎么接（接入点已就绪）</b>：{@link currentChartThemeMode} **已改为读
 * `<html>` 的 `data-theme`**，取值规则见该函数注释。应用将来实现全局深色主题时，
 * 只需在主题切换处设置 `document.documentElement.dataset.theme` 并触发一次重渲染，
 * 图表实现与各页面都不用动。配套地，`charts.tsx` 已从「模块级常量」改为**组件内求值** ——
 * 否则主题变了图表不会跟着变（模块级常量只在首次加载时算一次）。
 */

export type ChartThemeMode = 'light' | 'dark';

export interface ChartTheme {
  /** 系列主色板（顺序固定：同一维度两次刷新不能换色） */
  palette: string[];
  /** 折线图两条线的专用顺序（订单数 / 成交额） */
  linePalette: string[];
  /** 坐标轴刻度文字 */
  axisLabel: { color: string; fontSize: number };
  /** 坐标轴线 */
  axisLine: string;
  /** 网格分隔线 */
  splitLine: string;
  /** 图例文字 */
  legendText: string;
  /** 提示框 */
  tooltip: {
    backgroundColor: string;
    borderColor: string;
    textColor: string;
    extraCssText: string;
  };
  /** 饼图扇区之间的描边（与卡片背景同色才不会有「脏边」） */
  pieBorderColor: string;
  /** 折线图 axisPointer 线色 */
  pointerLine: string;
  /** 柱状图 axisPointer 阴影色 */
  pointerShadow: string;
}

export const CHART_THEMES: Record<ChartThemeMode, ChartTheme> = {
  light: {
    palette: ['#ff7a45', '#4f6ef7', '#36cfc9', '#f7b84b', '#a855f7', '#f759ab', '#52c41a', '#8c8c8c'],
    linePalette: ['#4f6ef7', '#ff7a45'],
    axisLabel: { color: '#8b8b94', fontSize: 11 },
    axisLine: 'rgba(0,0,0,.1)',
    splitLine: 'rgba(0,0,0,.06)',
    legendText: '#6b6b73',
    tooltip: {
      backgroundColor: 'rgba(255,255,255,.98)',
      borderColor: '#e9e9ef',
      textColor: '#2b2b33',
      extraCssText: 'border-radius:8px;box-shadow:0 6px 18px rgba(0,0,0,.08);',
    },
    pieBorderColor: '#fff',
    pointerLine: 'rgba(79,110,247,.4)',
    pointerShadow: 'rgba(255,122,69,.08)',
  },
  // 备好未启用（应用还没有深色主题）—— 值与浅色一一对应，只是把「底」换成深色
  dark: {
    palette: ['#ff9668', '#7c93ff', '#4fd8d2', '#f7c76b', '#bb7bf7', '#f77cbb', '#6fd94a', '#9a9aa6'],
    linePalette: ['#7c93ff', '#ff9668'],
    axisLabel: { color: '#9a9aa6', fontSize: 11 },
    axisLine: 'rgba(255,255,255,.18)',
    splitLine: 'rgba(255,255,255,.10)',
    legendText: '#b4b4bf',
    tooltip: {
      backgroundColor: 'rgba(32,32,38,.96)',
      borderColor: '#3a3a44',
      textColor: '#ececf1',
      extraCssText: 'border-radius:8px;box-shadow:0 6px 18px rgba(0,0,0,.45);',
    },
    pieBorderColor: '#202026',
    pointerLine: 'rgba(124,147,255,.5)',
    pointerShadow: 'rgba(255,150,104,.12)',
  },
};

/**
 * 当前图表主题：**跟随应用主题**（`<html data-theme="...">`）。
 *
 * <p>接入点收成这一个函数 —— 应用做全局深色主题时，只需在切换主题的地方设置
 * `document.documentElement.dataset.theme` 并触发一次重渲染，图表侧无需改动。
 *
 * <p><b>取值规则</b>：仅当 `data-theme === 'dark'` 时返回 `'dark'`；
 * 其余情况（未设置、空串、其他值、非浏览器环境没有 `document`）**一律回落 `'light'`**。
 * 当前应用尚未实现深色主题、没有地方设置 `data-theme`，因此运行时行为与「恒 light」一致。
 *
 * <p>刻意不读 `prefers-color-scheme`：见文件头「一条明确的约定」。
 */
export function currentChartThemeMode(): ChartThemeMode {
  if (typeof document === 'undefined') return 'light';
  const theme = document.documentElement?.dataset?.theme;
  return theme === 'dark' ? 'dark' : 'light';
}

/** 取某模式下的主题；缺省取当前应用主题 */
export function chartTheme(mode: ChartThemeMode = currentChartThemeMode()): ChartTheme {
  return CHART_THEMES[mode];
}
