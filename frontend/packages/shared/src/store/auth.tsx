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
import { ApiError } from '@duoduo/api-sdk/client';
import { authApi, petApi, userApi } from '@duoduo/api-sdk/endpoints';
import type { PetView, UserView } from '@duoduo/api-sdk/types';

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
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

/**
 * ⚠️ **这里刻意没有 `isAdmin`**（R74-3 删除）。
 *
 * 曾经有过一个 `isAdmin: user?.role === 'admin' || user?.role === 'operator'`：
 * 库内角色是**大写**存储（`ADMIN` / `OPERATOR`），全小写比较**恒为 false**，
 * 而且它不报任何错 —— 用它做入口显隐，入口会静默不出现，是极难归因的一类问题。
 * 它当时没有任何消费者，属于「埋着的坑」，因此直接删除而不是改成正确实现：
 * 运营 / 管理员判定**只有一处权威实现** `pages/admin/shared.tsx` 的 `isOperator()`，
 * 需要判定就从那里 import（前后端同口径）。**删掉字段本身即是防线** ——
 * 若将来有人再写 `useAuth().isAdmin`，`tsc` 会直接编译失败，不会再静默失效。
 */
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
