/**
 * 认证状态。
 *
 * 与 Node 原型版的关键差异：
 *  · 令牌存放于 httpOnly Cookie（dd_at / dd_rt），前端**读不到也不该读**，
 *    因此登录态判定只能靠服务端接口 GET /api/users/me
 *  · 页面刷新时无法同步得知是否已登录 —— 先乐观置 user=null，
 *    等 /api/users/me 返回后再渲染，其间 loading=true 走骨架屏
 *  · 后端登录/注册直接返回扁平的 UserView（不是 {user:{...}}）
 *  · 无短信登录接口，只有「手机号 + 密码」
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { ApiError } from '../api/client';
import { authApi, petApi, userApi } from '../api/endpoints';
import type { PetView, UserView } from '../api/types';

interface AuthState {
  user: UserView | null;
  /** 默认宠物档案（优先 isDefault，其次第一条） */
  pet: PetView | null;
  pets: PetView[];
  /** 首次会话校验是否仍在进行 */
  loading: boolean;
  login: (phone: string, password: string) => Promise<UserView>;
  register: (phone: string, password: string, nickname?: string) => Promise<UserView>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  refreshPets: () => Promise<void>;
  setPets: (pets: PetView[]) => void;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserView | null>(null);
  const [pets, setPetsState] = useState<PetView[]>([]);
  const [loading, setLoading] = useState(true);

  const pet = useMemo(() => pets.find((p) => p.isDefault) ?? pets[0] ?? null, [pets]);

  const refreshPets = useCallback(async () => {
    try {
      const list = await petApi.list();
      setPetsState(Array.isArray(list) ? list : []);
    } catch {
      // 宠物档案是辅助能力，接口异常不阻断主流程，静默降级
      setPetsState([]);
    }
  }, []);

  const refreshMe = useCallback(async () => {
    try {
      const me = await userApi.me();
      setUser(me);
      await refreshPets();
    } catch (error) {
      // 401 表示确实未登录；其它错误（网络抖动等）也按未登录处理并留待用户手动重试
      if (error instanceof ApiError) {
        setUser(null);
        setPetsState([]);
      }
    }
  }, [refreshPets]);

  useEffect(() => {
    void (async () => {
      await refreshMe();
      setLoading(false);
    })();
  }, [refreshMe]);

  const login = useCallback(
    async (phone: string, password: string) => {
      const me = await authApi.login({ phone, password });
      setUser(me);
      await refreshPets();
      return me;
    },
    [refreshPets],
  );

  const register = useCallback(
    async (phone: string, password: string, nickname?: string) => {
      const me = await authApi.register({ phone, password, nickname });
      setUser(me);
      await refreshPets();
      return me;
    },
    [refreshPets],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      // 无论后端是否成功撤销，本地状态一律清干净
      setUser(null);
      setPetsState([]);
    }
  }, []);

  const value: AuthState = {
    user,
    pet,
    pets,
    loading,
    login,
    register,
    logout,
    refreshMe,
    refreshPets,
    setPets: setPetsState,
    isAdmin: user?.role === 'admin' || user?.role === 'operator',
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
