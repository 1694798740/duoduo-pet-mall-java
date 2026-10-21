/**
 * A2 · 暗夜模式切换按钮（Web 端，2026-10-20）。
 *
 * 三态循环：☀️ 日间 → 🌙 暗夜 → 🖥️ 跟随系统。
 * 用按钮而不是下拉：顶栏空间有限，且"再点一下就换一种"比"展开再选"更快。
 *
 * 无障碍要点：
 *   · 用 aria-label 描述"当前是什么、点了会变成什么"——只写"切换主题"的话，
 *     读屏用户无从知道当前状态；
 *   · 用文字而非纯图标承载状态（图标 + 文案都要有），避免色盲用户只能靠颜色分辨。
 */
import React from 'react';
import { useTheme } from '../store/theme';

type Mode = 'light' | 'dark' | 'system';

/** 用 Record<Mode, …> 而非 Record<string, …>：取值时不必再判空，mode 已是穷举类型 */
const LABEL: Record<Mode, { icon: string; text: string; next: string }> = {
  light: { icon: '☀️', text: '日间', next: '暗夜' },
  dark: { icon: '🌙', text: '暗夜', next: '跟随系统' },
  system: { icon: '🖥️', text: '跟随系统', next: '日间' },
};

export const ThemeToggle: React.FC = () => {
  const { mode, resolved, cycle } = useTheme();
  const meta = LABEL[mode];
  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={cycle}
      aria-label={`主题：${meta.text}（点击切换到${meta.next}，当前实际为${resolved === 'dark' ? '暗夜' : '日间'}）`}
      title={`${meta.text} · 点击切到${meta.next}`}
    >
      <span className="theme-toggle-icon" aria-hidden="true">
        {meta.icon}
      </span>
      <span className="theme-toggle-text">{meta.text}</span>
    </button>
  );
};
