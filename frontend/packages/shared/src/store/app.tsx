/**
 * 全局 UI 状态：购物车角标 + 未读角标（通知 / 私信）。
 *
 * 与 Node 原型版的区别：这里不再承载「多多 AI 面板」——
 * duoduo-ai 服务尚未部署，网关对应路由返回 503，因此本期不提供 AI 导购入口。
 *
 * <h3>未读角标（S7）：口径与刷新点都只留一处</h3>
 *
 * 数据源只有一个：`GET /api/socials/notices/unread-summary`（服务端把通知与私信合并后下发）。
 * 这与小程序 `miniprogram/utils/unread.js` 是**同一个接口、同一套口径** —— 两端不会各算一套。
 *
 * 为什么不能各页面自己算：页面各写一遍（通知查一次、私信查一次再相加），迟早出现
 * 「首页角标 3、切到通知页变 5」这类自相矛盾。用户不会怀疑口径，只会认为未读数是错的。
 *
 * 刷新点（对齐小程序）：① 打开头像菜单时；② 页面重新可见时；③ 10 秒兜底轮询（自己的动作另有显式刷新）。
 * 实现都在本模块，页面只负责「什么时候启用」—— 散在各页面必然漏掉一处
 * （小程序第五十四轮的真实表现就是：只有通知页 onShow 会刷新，用户在别的页面永远看不到数字）。
 *
 * 轮询的**启停由消费方（商城端 Layout）驱动**，本 Provider 不自动开始，原因有二：
 *   · AppProvider 在 AuthProvider **外层**（见 `main.tsx` 的嵌套顺序），这里读不到登录态；
 *   · 三端共用本 Provider，而后台 / 商家端并不需要买家未读角标。
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { cartApi, noticeApi } from '@duoduo/api-sdk/endpoints';
import type { AiBadgePatch, UnreadSummary } from '@duoduo/api-sdk/types';
import { notifyBadgeChanged } from '../lib/badge-events';

/**
 * 兜底轮询间隔（2026-10-18 由 60s 收紧到 10s）。
 *
 * <p>先说清楚边界：**用户自己的动作不等轮询** —— 下单 / 支付成功后由对应页面显式
 * `refreshUnread()`，切页、回前台、打开菜单也各刷一次，表现与购物车角标一致（即时）。
 * 轮询只兜「别人触发的通知」（卖家发货、平台公告等），10 秒是可接受的兜底延迟。
 * 若将来要求这类事件也零延迟，需要上服务端推送（SSE），而不是继续把间隔调到 1 秒。
 */
export const UNREAD_POLL_MS = 10 * 1000;

/** 全部计零（未登录 / 主动清空时用） */
const EMPTY_UNREAD: UnreadSummary = { notice: 0, message: 0, support: 0, total: 0 };

interface AppState {
  /** 购物车件数角标 */
  cartCount: number;
  /** 不抛错：未登录 / 接口异常时角标降级为 0，不影响主流程 */
  refreshCart: () => Promise<void>;
  /** 下单成功后本地清零，省一次往返 */
  clearCart: () => void;

  /** 未读汇总（通知 + 私信，服务端合并口径）—— 全站角标读这一个 */
  unread: UnreadSummary;
  /**
   * 刷新未读。**失败时保持旧值**（不清零）：网络抖一下就把未读提示抹掉，
   * 比「数字暂时停在旧值」更让人困惑 —— 旧值至少还是真的，清掉则凭空制造「消息不见了」的错觉。
   */
  refreshUnread: () => Promise<void>;
  /** 清零（登出时用：上一个账号的历史数字不能留着） */
  clearUnread: () => void;
  /**
   * 应用服务端下发的红点补丁（2026-10-29 · AI 管家）。
   *
   * <p>为什么不能直接 `refreshUnread()`：补丁是服务端在**同一次操作里**算出的权威值，
   * 再发一次 unread-summary 既多一次往返，两次请求之间用户还可能又收到新通知，反而对不上。
   *
   * <p>四路语义：notification → 通知角标；support → 客服角标；
   * message 保持不变（AI 不碰私信）；order / afterSale 不属于全局未读口径
   * （它们由订单页 / 售后页自己的 counts 接口维护），这里只广播「该重取了」。
   */
  applyBadgePatch: (patch?: AiBadgePatch | null) => void;
  /** 开始 10 秒兜底轮询（幂等：重复调用不会叠出第二个定时器） */
  startUnreadPolling: () => void;
  /** 停止轮询（幂等） */
  stopUnreadPolling: () => void;
}

const AppContext = createContext<AppState | null>(null);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [cartCount, setCartCount] = useState(0);
  const [unread, setUnread] = useState<UnreadSummary>(EMPTY_UNREAD);

  const refreshCart = useCallback(async () => {
    try {
      const res = await cartApi.count();
      setCartCount(res.count ?? 0);
    } catch {
      setCartCount(0);
    }
  }, []);

  const clearCart = useCallback(() => setCartCount(0), []);

  /** 未完成的未读请求（单飞）：三个刷新点可能在同一次交互里一起触发 */
  const unreadInflight = useRef<Promise<void> | null>(null);
  /** 单例定时器：同一时刻最多一个，避免反复启用叠出多个 */
  const unreadTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const refreshUnread = useCallback(async () => {
    if (unreadInflight.current) return unreadInflight.current;
    const task = (async () => {
      try {
        const d = await noticeApi.unreadSummary();
        setUnread({
          notice: Number(d?.notice ?? 0),
          message: Number(d?.message ?? 0),
          support: Number(d?.support ?? 0),
          total: Number(d?.total ?? 0),
        });
      } catch {
        // 刻意不清零：见 AppState.refreshUnread 的注释
      }
    })();
    unreadInflight.current = task;
    try {
      await task;
    } finally {
      unreadInflight.current = null;
    }
  }, []);

  const clearUnread = useCallback(() => setUnread(EMPTY_UNREAD), []);

  const applyBadgePatch = useCallback((patch?: AiBadgePatch | null) => {
    if (!patch) return;
    // 只有「确实带了这个数字」的那一路才覆盖；null/undefined = 这一路没变化
    const hasNotification = typeof patch.notification === 'number';
    const hasSupport = typeof patch.support === 'number';
    if (hasNotification || hasSupport) {
      setUnread((prev) => {
        const notice = hasNotification ? (patch.notification as number) : prev.notice;
        const support = hasSupport ? (patch.support as number) : prev.support;
        // total 必须现场重算：它是 notice + message + support，单独存一份迟早对不上
        return { notice, message: prev.message, support, total: notice + prev.message + support };
      });
    }
    // 订单 / 售后角标不在全局口径里：广播一次，让订单页、售后页按自己的接口重取
    notifyBadgeChanged();
  }, []);

  const startUnreadPolling = useCallback(() => {
    if (unreadTimer.current) return;
    unreadTimer.current = setInterval(() => { void refreshUnread(); }, UNREAD_POLL_MS);
  }, [refreshUnread]);

  const stopUnreadPolling = useCallback(() => {
    if (unreadTimer.current) {
      clearInterval(unreadTimer.current);
      unreadTimer.current = null;
    }
  }, []);

  // 卸载兜底：组件树被替换时不能留下定时器
  // （React 18 严格模式下 effect 会跑两遍，靠 start / stop 的幂等兜住）
  useEffect(() => () => stopUnreadPolling(), [stopUnreadPolling]);

  const value = useMemo(
    () => ({
      cartCount,
      refreshCart,
      clearCart,
      unread,
      refreshUnread,
      clearUnread,
      applyBadgePatch,
      startUnreadPolling,
      stopUnreadPolling,
    }),
    [
      cartCount,
      refreshCart,
      clearCart,
      unread,
      refreshUnread,
      clearUnread,
      applyBadgePatch,
      startUnreadPolling,
      stopUnreadPolling,
    ],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp 必须在 AppProvider 内使用');
  return ctx;
}
