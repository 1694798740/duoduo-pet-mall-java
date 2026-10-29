/**
 * 角标消除规则 · **唯一一处定义**（2026-10-27）。
 *
 * <h2>为什么必须集中</h2>
 * 规则如果散落在各页 `<Link onClick>` 里写成 `if (status === 'CLOSED') ...`，
 * 迟早出现「买家端已关闭能消、商家端已关闭不消」这种同一状态两套行为 ——
 * 而用户看到的是同一套系统。这个文件是三端唯一的事实源。
 *
 * <h2>两类语义（这是整套角标的核心区分）</h2>
 * <ul>
 *   <li><b>PERSISTENT</b>（持续提醒）：状态本身就是「还没办完的事」。
 *       待支付要付钱、待收货要收货、待发货要发货 —— **看过也必须保留**，
 *       否则用户点开一次就再也想不起来处理。</li>
 *   <li><b>DISMISS_ON_READ</b>（阅后即消）：终态，看一眼就够了。
 *       已关闭、已退款这类订单翻来覆去看过之后，角标还挂在那里就是噪音。</li>
 * </ul>
 *
 * <h2>与后端的一致性</h2>
 * 服务端的计数 SQL 里也写了一份同样的状态名单（{@code OrderMapper#countByBuyerAndStatus}
 * 与 {@code countBySellerAndStatusForBadge} 的 {@code status IN (...)}）。
 * 两处**必须同时改**：
 * <ul>
 *   <li>后端那份决定「角标数字」；</li>
 *   <li>这份决定「点击时要不要上报已读」。漏改这边只会多/少一次上报（无害），
 *       漏改后端会让数字不对 —— 所以后端才是权威，这里只影响交互时机。</li>
 * </ul>
 */

/** 角标对「读过」的反应方式 */
export type BadgeBehavior = 'PERSISTENT' | 'DISMISS_ON_READ';

/** 订单状态 → 消除规则（买家视角） */
const BUYER_ORDER_RULES: Record<string, BadgeBehavior> = {
  PENDING_PAYMENT: 'PERSISTENT', // 还没付钱，必须一直提醒
  PAID: 'PERSISTENT', // 等卖家发货，提醒买家别急但也别忘
  SHIPPED: 'PERSISTENT', // 等收货 —— 这是买家该做的事，看过也要留
  COMPLETED: 'PERSISTENT', // 后端未把已完成从计数里排除，保持一致
  REFUNDED: 'DISMISS_ON_READ',
  CLOSED: 'DISMISS_ON_READ',
};

/** 订单状态 → 消除规则（商家视角） */
const SELLER_ORDER_RULES: Record<string, BadgeBehavior> = {
  PENDING_PAYMENT: 'PERSISTENT', // 等买家付款
  PAID: 'PERSISTENT', // 待发货 —— 卖家最高频待办，看过也必须留
  // 已发货/已完成/已关闭/已退款：都属「看过的历史」。
  // 特别地，发货动作本身会由服务端写一条店铺已读（SellerService#ship），
  // 所以新发货的订单进入「已发货」分桶时就是已读 —— **自己操作不产生新角标**。
  SHIPPED: 'DISMISS_ON_READ',
  COMPLETED: 'DISMISS_ON_READ',
  REFUNDED: 'DISMISS_ON_READ',
  CLOSED: 'DISMISS_ON_READ',
};

export type OrderSide = 'buyer' | 'seller';

/** 某个订单状态下，点击详情是否应该触发「已读上报」 */
export const shouldDismissOnRead = (side: OrderSide, status: string | null | undefined): boolean => {
  if (!status) return false;
  const table = side === 'seller' ? SELLER_ORDER_RULES : BUYER_ORDER_RULES;
  return table[status] === 'DISMISS_ON_READ';
};

/**
 * 乐观减一：只对「可消」状态生效。
 *
 * <p>**调用方必须用服务端返回值覆盖它**（`setCounts(serverRows)`），
 * 请求失败时也要回滚到服务端值 —— 否则一次失败的请求会留下一笔永久少算的假账。
 */
export const applyOptimisticDismiss = (
  counts: Record<string, number>,
  status: string,
): Record<string, number> => {
  const current = counts[status] ?? 0;
  if (current <= 0) return counts;
  return { ...counts, [status]: current - 1 };
};

/**
 * 批量操作成功后的本地兜底扣减（2026-10-27 · 批量操作即已读）。
 *
 * <h2>为什么是「按成功条目逐条减」而不是直接减 selectedIds.length</h2>
 * 批量操作允许部分失败（未过审、已删除、越权），失败条目**不该**消角标 ——
 * 用户没有处理成，那件事还在等他。所以调用方必须只传**成功**条目的状态；
 * 混用选中数量会让角标少减或减成负数。
 *
 * <h2>为什么还要服务端覆盖</h2>
 * 本函数只是「上报返回前的过渡值」：跨状态、跨页、他人操作都会让本地算术失准。
 * 服务端返回最新分桶后必须用 `toCountMap(rows)` 覆盖；本函数只在
 * 接口没有返回分桶（旧后端）时兜底，并自带 `>= 0` 边界。
 */
export const applyBatchDismissLocally = (
  counts: Record<string, number>,
  statuses: Array<string | null | undefined>,
  isDismissible: (status: string) => boolean,
): Record<string, number> => {
  const next = { ...counts };
  for (const status of statuses) {
    if (!status || !isDismissible(status)) continue;
    const current = next[status] ?? 0;
    if (current > 0) next[status] = current - 1;
  }
  return next;
};

// ---------------- 售后（2026-10-27） ----------------

/**
 * 买家侧可消的售后状态：只有终态。
 *
 * <p>进行中的（待卖家处理 / 待寄回 / 待收货 / 仲裁中）都是「这件事还没结束」，
 * 读过也必须继续提醒 —— 与订单侧「待支付/待发货/待收货不减」同一条规则。
 */
const BUYER_DISMISS_AFTER_SALE = new Set(['REFUNDED', 'CLOSED']);

/**
 * 卖家侧可消的售后状态：终态 + 卖家已拒绝。
 *
 * <p>{@code SELLER_REJECTED} 对卖家是**已办**（我已经处理完、等买家决定下一步），
 * 看过的就消；但 {@code PENDING}（待我处理）与 {@code RETURNING}（待我签收）
 * 必须留着，那是还没办的事。
 */
const SELLER_DISMISS_AFTER_SALE = new Set(['REFUNDED', 'CLOSED', 'SELLER_REJECTED']);

/** 售后页点击详情是否应触发已读上报 */
export const shouldDismissAfterSale = (
  side: OrderSide,
  status: string | null | undefined,
): boolean => {
  if (!status) return false;
  return (side === 'seller' ? SELLER_DISMISS_AFTER_SALE : BUYER_DISMISS_AFTER_SALE).has(status);
};
