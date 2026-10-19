/**
 * 页面级通用小工具：统一处理「加载中 / 出错 / 空数据」三态。
 *
 * 目的：页面里不重复写 useEffect + try/catch + setState 三段式，
 * 同时保证任何接口失败都能落到一句可读的中文提示上。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, OfflineError, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { subscribeDataChanged } from '../lib/dataBus';

export interface AsyncState<T> {
  data: T | undefined;
  loading: boolean;
  error: string | null;
  /** 手动触发重新拉取（会进入 loading 态，用于「重试」按钮） */
  reload: () => void;
  /**
   * 静默重新拉取：不置 loading、不清 error，页面不闪。
   * 供实时刷新使用 —— 后台每 30 秒把整页变成骨架屏是体验灾难，而不是「实时」。
   */
  reloadSilently: () => void;
  setData: React.Dispatch<React.SetStateAction<T | undefined>>;
}

/**
 * 拉取远端数据并管理三态。
 *
 * @param fetcher 返回 Promise 的取数函数；调用方需自行用 useCallback 稳定引用
 * @param enabled 为 false 时跳过请求（如未登录、参数缺失）
 */
export function useAsync<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  enabled = true,
  deps: unknown[] = [],
): AsyncState<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  /** 下一次 tick 是否静默（由 reloadSilently 置位，effect 读到后立刻复位） */
  const silentNext = useRef(false);
  /**
   * 手动重试：必须**清掉**可能残留的 silent 标记。
   * 否则「静默刷新刚入队、用户紧接着点了重试」时，那次重试会被当成静默刷新 ——
   * 用户看不到任何加载反馈，会以为按钮点坏了。
   */
  const reload = useCallback(() => {
    silentNext.current = false;
    setTick((v) => v + 1);
  }, []);
  const reloadSilently = useCallback(() => {
    silentNext.current = true;
    setTick((v) => v + 1);
  }, []);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return undefined;
    }
    const controller = new AbortController();
    let alive = true;

    const silent = silentNext.current;
    silentNext.current = false;
    if (!silent) {
      setLoading(true);
      setError(null);
    }

    fetcherRef
      .current(controller.signal)
      .then((result) => {
        if (!alive) return;
        setData(result);
        setError(null);
      })
      .catch((err) => {
        if (!alive) return;
        if ((err as Error).name === 'AbortError') return;
        setError(toFriendlyMessage(err));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });

    return () => {
      alive = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, tick, ...deps]);

  return { data, loading, error, reload, reloadSilently, setData };
}

/**
 * 页面级实时刷新（2026-10-19）。
 *
 * <p>解决「状态由**服务端单方面**改变」的页面会停在一份旧数据上的问题：
 * 超时自动关单、支付回调、商家发货、售后被受理 —— 这些动作本端没有写操作可广播。</p>
 *
 * <p>三层策略，与秒杀中心同口径：</p>
 * <ol>
 *   <li><b>本端写操作广播</b>（dataBus）：自己刚做完的操作，立即刷新；</li>
 *   <li><b>切回标签页</b>：用户离开期间状态大概率变了，回到页面先拉一次；</li>
 *   <li><b>兜底轮询</b>：默认 30 秒，且只在页面可见时跑 —— 后台标签页不白白打接口。</li>
 * </ol>
 *
 * <p>传进来的 refresh 必须是**静默**刷新（如 useAsync 的 reloadSilently），
 * 否则每次轮询都会把页面打回骨架屏。</p>
 */
export function useLiveRefresh(
  refresh: () => void,
  options: { intervalMs?: number; enabled?: boolean; poll?: boolean } = {},
): void {
  const { intervalMs = 30_000, enabled = true, poll = true } = options;
  /** 用 ref 持有最新回调：调用方每次渲染新建的箭头函数不会导致重复订阅 */
  const cbRef = useRef(refresh);
  cbRef.current = refresh;

  useEffect(() => {
    if (!enabled) return undefined;
    const run = () => cbRef.current();
    const offData = subscribeDataChanged(run);
    const onVisible = () => {
      if (document.visibilityState === 'visible') run();
    };
    document.addEventListener('visibilitychange', onVisible);
    const timer = poll
      ? window.setInterval(() => {
          if (document.visibilityState === 'visible') run();
        }, intervalMs)
      : null;
    return () => {
      offData();
      document.removeEventListener('visibilitychange', onVisible);
      if (timer !== null) window.clearInterval(timer);
    };
  }, [enabled, intervalMs, poll]);
}

/** 401 单独识别：用于「需要登录」这类提示，与业务错误区分开 */
export function isAuthError(error: unknown): boolean {
  return error instanceof ApiError && error.isAuthError;
}

/** 网络离线识别：用于展示「离线」而不是「服务出错」 */
export function isOffline(error: unknown): boolean {
  return error instanceof OfflineError;
}
