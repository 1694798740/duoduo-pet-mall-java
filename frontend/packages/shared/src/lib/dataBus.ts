/**
 * 轻量数据变更广播（2026-10-19）。
 *
 * <h2>解决什么问题</h2>
 * 「取消订单后返回详情页还显示已抢完」「下单后余量不变」这类问题，
 * 本质都是**写操作发生在 A 页面，而 B 页面的展示数据还停在旧值**，
 * 旧值要等下一次轮询 / 用户手动刷新才更新。
 *
 * 与其给每对「写者 → 读者」拉一根专用线（谁加一个写点都要记得接线），
 * 不如立一条全站约定：**任何会改变他人正在展示的数据的写操作成功后，喊一嗓子**；
 * 关心实时性的数据源（秒杀中心、商品详情）各自订阅这一嗓子，听到就重拉。
 *
 * 口径：
 * <ul>
 *   <li>只做「该刷新了」的通知，<b>不带数据</b> —— 各订阅方按自己的口径重拉，避免出现第二份数据源；</li>
 *   <li>听不到也不会坏：所有订阅方本来就有轮询/进页面拉取兜底，广播只是把延迟从 60s 降到 0。</li>
 * </ul>
 */

/** 全站统一的数据变更事件名 */
export const DATA_CHANGED_EVENT = 'duoduo:data-changed';

/** 写操作成功后调用：通知所有订阅方「有数据变了，该重拉了」 */
export function notifyDataChanged(): void {
  window.dispatchEvent(new CustomEvent(DATA_CHANGED_EVENT));
}

/** 订阅数据变更；返回取消订阅函数（组件卸载时调用） */
export function subscribeDataChanged(cb: () => void): () => void {
  window.addEventListener(DATA_CHANGED_EVENT, cb);
  return () => window.removeEventListener(DATA_CHANGED_EVENT, cb);
}
