/**
 * 页面级通用小工具：统一处理「加载中 / 出错 / 空数据」三态。
 *
 * 目的：页面里不重复写 useEffect + try/catch + setState 三段式，
 * 同时保证任何接口失败都能落到一句可读的中文提示上。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, OfflineError, toFriendlyMessage } from '@duoduo/api-sdk/client';

export interface AsyncState<T> {
  data: T | undefined;
  loading: boolean;
  error: string | null;
  /** 手动触发重新拉取 */
  reload: () => void;
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

  const reload = useCallback(() => setTick((v) => v + 1), []);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return undefined;
    }
    const controller = new AbortController();
    let alive = true;

    setLoading(true);
    setError(null);

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

  return { data, loading, error, reload, setData };
}

/** 401 单独识别：用于「需要登录」这类提示，与业务错误区分开 */
export function isAuthError(error: unknown): boolean {
  return error instanceof ApiError && error.isAuthError;
}

/** 网络离线识别：用于展示「离线」而不是「服务出错」 */
export function isOffline(error: unknown): boolean {
  return error instanceof OfflineError;
}
