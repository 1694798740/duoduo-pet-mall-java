/**
 * 全局 UI 状态：购物车角标。
 *
 * 与 Node 原型版的区别：这里不再承载「多多 AI 面板」——
 * duoduo-ai 服务尚未部署，网关对应路由返回 503，因此本期不提供 AI 导购入口。
 */

import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { cartApi } from '../api/endpoints';

interface AppState {
  /** 购物车件数角标 */
  cartCount: number;
  /** 不抛错：未登录 / 接口异常时角标降级为 0，不影响主流程 */
  refreshCart: () => Promise<void>;
  /** 下单成功后本地清零，省一次往返 */
  clearCart: () => void;
}

const AppContext = createContext<AppState | null>(null);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [cartCount, setCartCount] = useState(0);

  const refreshCart = useCallback(async () => {
    try {
      const res = await cartApi.count();
      setCartCount(res.count ?? 0);
    } catch {
      setCartCount(0);
    }
  }, []);

  const clearCart = useCallback(() => setCartCount(0), []);

  const value = useMemo(
    () => ({ cartCount, refreshCart, clearCart }),
    [cartCount, refreshCart, clearCart],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp 必须在 AppProvider 内使用');
  return ctx;
}
