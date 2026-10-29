/**
 * 角标变更通知（2026-10-27）。
 *
 * <h2>为什么需要它</h2>
 * 页面内的角标是「本地态 + 服务端覆盖」，父级菜单的角标（`MerchantRoot` / `AdminTabs`）
 * 则是另一个组件里的独立请求。商家点开一张卡片后，两者必须**同一时刻**一起减 ——
 * 若父级只靠 30 秒轮询，用户会看到「页签已经消了、左侧菜单还挂着」，
 * 那正是这轮要修的那类不一致。
 *
 * <p>做法：上报成功后广播一个普通 DOM 事件，父级导航监听后静默重取一次。
 * 不用全局状态库/Context 透传：角标数据本来就是「服务端权威」，
 * 这里需要的只是「该重取了」这一个信号，事件比把三端都接进一个 store 更轻。
 */

export const BADGE_CHANGED_EVENT = 'duoduo:badge-changed';

/** 广播「角标可能变了」——已读上报 / 发货 / 退款等动作成功后调用 */
export const notifyBadgeChanged = (): void => {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(BADGE_CHANGED_EVENT));
  }
};

/** 订阅角标变更，返回取消订阅函数（直接作为 useEffect 的清理函数） */
export const onBadgeChanged = (handler: () => void): (() => void) => {
  if (typeof window === 'undefined') return () => undefined;
  window.addEventListener(BADGE_CHANGED_EVENT, handler);
  return () => window.removeEventListener(BADGE_CHANGED_EVENT, handler);
};
