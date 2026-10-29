/**
 * 数字角标 + 带角标的页签（2026-10-27 · 三端共用）。
 *
 * <h2>为什么必须抽成共用件</h2>
 * 买家 / 商家 / 运营三套前端各页都要「页签右上角一个小红数字」。
 * 各写一份的结果已经见过太多次：有的写 `>99` 截断、有的写 `>=100`、
 * 有的 0 也渲染一个空圈、有的把数字放在文字前面 —— 同一套系统里三种样式。
 *
 * <h2>两条硬口径</h2>
 * <ul>
 *   <li><b>数字只来自服务端</b>：`count` 必须由 `StatusCount` 接口下发。
 *       绝不能用「当前页 items 里 status === X 的条数」—— 分页会把第 2 页全部漏掉，
 *       用户看到「页签没数字、点进去却有待处理单」；</li>
 *   <li><b>0 不渲染</b>：`count <= 0` 直接返回 null，不留空圈。角标是「有事情等你」，
 *       0 条时留个圈只会让人以为坏了。</li>
 * </ul>
 */

import React from 'react';
import type { StatusCount } from '@duoduo/api-sdk/types';

/** 数字封顶：>99 显示 99+（与小程序 TabBar 口径一致） */
export const formatBadge = (count: number): string => (count > 99 ? '99+' : String(count));

/**
 * 状态计数数组 → 以 status 为键的查表。
 *
 * <p>缺失的状态在调用方 `map[key] ?? 0` 兜底 —— 服务端只返回非 0 的分桶
 * （不返回一整排 0 让响应体变胖），所以「键不存在」是常态而不是异常。
 */
export const toCountMap = (rows: StatusCount[] | undefined | null): Record<string, number> => {
  const map: Record<string, number> = {};
  for (const row of rows ?? []) {
    if (row && row.status) {
      // Number() 兜一层：万一某天服务端把 count 序列化成字符串，也不至于 `+` 变拼接
      map[row.status] = Number(row.count) || 0;
    }
  }
  return map;
};

/**
 * 纯数字角标；count <= 0 或不传时不渲染任何东西。
 *
 * <p>`count` 声明为可选而不是必填：调用方大多直接传 `counts[key]`，
 * 而「某个状态没有计数项」在服务端只返回非 0 分桶的口径下是**常态**，
 * 逼调用方到处写 `?? 0` 只会增加噪音。
 */
export const Badge: React.FC<{ count?: number; className?: string }> = ({ count, className }) => {
  if (!count || count <= 0) return null;
  return (
    <span
      className={`badge-count${className ? ` ${className}` : ''}`}
      // 视觉上是「小红点 + 数字」，读屏需要能读到它代表什么
      aria-label={`${count} 项待处理`}
    >
      {formatBadge(count)}
    </span>
  );
};

/**
 * 橙色未读印记（2026-10-27 · 业务列表逐条未读）。
 *
 * <p>与消息中心的「未读」同一色系；数字角标回答「这一类还有几条」，
 * 橙点回答「这一条是哪一条」——两者必须同时出现/消失，
 * 否则用户看到「角标减了，但卡片还亮着」。
 */
export const UnreadDot: React.FC<{ label?: string }> = ({ label = '未读' }) => (
  <span className="unread-dot" role="img" aria-label={label} title={label} />
);

/**
 * 带角标的页签。样式复用全站 `.tab`，只额外塞一个 `Badge`。
 *
 * <p>`onClick` 必须由调用方传（页签切换通常还要同步 URL query），
 * 这里不做「自己管 active」—— 那样会出现「URL 说在待支付、高亮却在全部」。
 */
export const TabWithBadge: React.FC<{
  active: boolean;
  label: string;
  count?: number;
  onClick: () => void;
  className?: string;
}> = ({ active, label, count = 0, onClick, className }) => (
  <button
    type="button"
    className={`tab${active ? ' active' : ''}${className ? ` ${className}` : ''}`}
    onClick={onClick}
  >
    {label}
    <Badge count={count} />
  </button>
);
