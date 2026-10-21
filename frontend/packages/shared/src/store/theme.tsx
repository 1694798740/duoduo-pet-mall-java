/**
 * A2 · 暗夜模式（Web 端，2026-10-20）。
 *
 * 三态而不是两态：**日间 / 暗夜 / 跟随系统**。
 * 只做"日间/暗夜"两态时，用户改了系统主题还得回来手动切一次，
 * 那是把操作系统已经解决的事重复做了一遍。
 *
 * 为什么写 `document.documentElement.dataset.theme` 而不是给 body 加类：
 *   本项目 `<html>` 上还有图表库在读取 `data-theme`（图表配色已留此接入点），
 *   写在根元素上可以让纯 CSS 与第三方图表共用同一个开关，
 *   不必在 JS 里再同步一份状态。
 *
 * 为什么刻意**不做**这三件事：
 *   ① 不做"自动按时间切换"——用户设了跟随系统就够了，再加一套时间规则
 *      会出现"系统说亮、时间说暗"的矛盾，且无从解释；
 *   ② 不把主题存进后端——纯视觉偏好，换设备时不该被强制同步；
 *   ③ 不用 CSS-in-JS——全站样式是 CSS 变量驱动的，主题切换本就该是
 *      一个属性的事，引入运行时样式计算只会让首屏变慢。
 */
import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

export type ThemeMode = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'dd-theme';

interface ThemeState {
  /** 用户的选择（可能是 system） */
  mode: ThemeMode;
  /** 实际生效的主题（system 已解析为 light/dark） */
  resolved: 'light' | 'dark';
  setMode: (mode: ThemeMode) => void;
  /** 供顶栏按钮做三态循环：日间 → 暗夜 → 跟随系统 → 日间 */
  cycle: () => void;
}

const ThemeContext = createContext<ThemeState | null>(null);

const DARK_QUERY = '(prefers-color-scheme: dark)';

function readStored(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw;
  } catch {
    /* 隐私模式下 localStorage 可能不可用 —— 主题是纯视觉偏好，降级即可，不能因此崩页面 */
  }
  return 'light';
}

function systemPrefersDark(): boolean {
  try {
    return window.matchMedia(DARK_QUERY).matches;
  } catch {
    return false;
  }
}

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [mode, setModeState] = useState<ThemeMode>(() => readStored());
  const [systemDark, setSystemDark] = useState(() => systemPrefersDark());

  // 跟随系统时，系统主题变化要实时生效（用户不必刷新页面）
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const mq = window.matchMedia(DARK_QUERY);
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const resolved: 'light' | 'dark' =
    mode === 'system' ? (systemDark ? 'dark' : 'light') : mode;

  useEffect(() => {
    const root = document.documentElement;
    if (resolved === 'dark') {
      root.dataset.theme = 'dark';
    } else {
      delete root.dataset.theme;
    }
    // 让浏览器原生控件（滚动条、表单控件）也跟上主题，否则深色页面里
    // 会出现一块刺眼的白色滚动条 —— 这是暗夜模式最常见的破功点。
    root.style.colorScheme = resolved;
  }, [resolved]);

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* 存不下就算了，本次会话内仍然有效 */
    }
  }, []);

  const cycle = useCallback(() => {
    setModeState((prev) => {
      const next: ThemeMode = prev === 'light' ? 'dark' : prev === 'dark' ? 'system' : 'light';
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch {
        /* 同上 */
      }
      return next;
    });
  }, []);

  const value = useMemo(() => ({ mode, resolved, setMode, cycle }), [mode, resolved, setMode, cycle]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
};

export function useTheme(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme 必须在 ThemeProvider 内使用');
  return ctx;
}
