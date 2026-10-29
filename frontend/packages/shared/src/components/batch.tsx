/**
 * 批量操作通用件（2026-10-27 · 批量操作即已读）。
 *
 * <p>三端后台的批量入口形状完全一致：勾选当前页 → 批量栏出现 → 执行 → 逐条结果。
 * 抽在这里是为了避免六个页面各写一份「全选 / 清空 / 结果弹窗」，
 * 其中任何一处漏掉「成功后清空选择」，用户就会以为操作没生效。
 *
 * <p>纪律（与商品管理一致）：
 *  · 只选**当前页**可见条目，切页 / 筛选 / 搜索自动清空；
 *  · HTTP 200 只代表处理完成，成败逐条看 `items[].success`；
 *  · 失败条目必须把原因列出来，不能只报「成功 8 条」。
 */

import React, { useEffect, useMemo, useState } from 'react';
import type { BatchResultView } from '@duoduo/api-sdk/types';
import { Alert, Modal } from './ui';

export interface BatchSelection {
  selected: string[];
  selectedSet: Set<string>;
  toggle: (id: string) => void;
  toggleAll: () => void;
  clear: () => void;
  allSelected: boolean;
  count: number;
}

/**
 * 当前页批量选择。
 *
 * @param pageIds  当前页全部条目 id（全选只作用于这些）
 * @param resetKey 筛选 / 搜索 / 页码的联合键；变化即清空选择
 */
export function useBatchSelection(pageIds: string[], resetKey: string): BatchSelection {
  const [selected, setSelected] = useState<string[]>([]);

  useEffect(() => {
    setSelected([]);
  }, [resetKey]);

  // 当前页刷新后，已不在页内的选择自动剔除（避免对看不见的条目误操作）
  useEffect(() => {
    const visible = new Set(pageIds);
    setSelected((prev) => prev.filter((id) => visible.has(id)));
    // pageIds 每次渲染都是新数组，用 join 做稳定依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageIds.join(','), resetKey]);

  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const allSelected = pageIds.length > 0 && pageIds.every((id) => selectedSet.has(id));

  return {
    selected,
    selectedSet,
    toggle: (id) => setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])),
    toggleAll: () => setSelected(allSelected ? [] : [...pageIds]),
    clear: () => setSelected([]),
    allSelected,
    count: selected.length,
  };
}

/** 批量操作栏：只在有选中时出现，固定吸顶，避免长列表滚上去就找不到按钮 */
export const BatchBar: React.FC<{
  count: number;
  onClear: () => void;
  children: React.ReactNode;
}> = ({ count, onClear, children }) => {
  if (count <= 0) return null;
  return (
    <div
      className="card card-pad"
      style={{
        display: 'flex',
        gap: 8,
        alignItems: 'center',
        flexWrap: 'wrap',
        margin: '0 0 12px',
        position: 'sticky',
        top: 8,
        zIndex: 5,
        border: '1px solid var(--warn)',
      }}
    >
      <span className="small">
        已选 <b>{count}</b> 条（仅当前页）
      </span>
      {children}
      <button className="btn btn-ghost btn-sm" onClick={onClear}>
        取消选择
      </button>
    </div>
  );
};

/**
 * 批量结果弹窗。
 *
 * @param result   后端逐条结果；null 时关闭
 * @param idLabel  失败项左侧标识的标题，如「订单号」「售后单」
 */
export const BatchResultModal: React.FC<{
  result: BatchResultView | null;
  onClose: () => void;
  idLabel?: string;
}> = ({ result, onClose, idLabel = 'ID' }) => (
  <Modal
    open={result !== null}
    title="批量操作结果"
    onClose={onClose}
    footer={
      <button className="btn btn-primary" onClick={onClose}>
        知道了
      </button>
    }
  >
    {result ? (
      <div>
        <Alert
          kind={result.failedCount === 0 ? 'success' : result.successCount === 0 ? 'error' : 'warn'}
        >
          共 {result.total} 条：成功 {result.successCount} 条，失败 {result.failedCount} 条。
          {result.failedCount > 0
            ? '失败条目保持原状态，本次操作不会回滚已成功的部分；可修正原因后重新提交。'
            : ''}
        </Alert>
        {result.failedCount > 0 ? (
          <div className="stack" style={{ gap: 6, marginTop: 12 }}>
            {result.items
              .filter((item) => !item.success)
              .map((item) => (
                <div className="card card-pad" key={item.id} style={{ padding: 10 }}>
                  <div className="small mono">
                    {idLabel} {item.id || '—'}
                    {item.errorCode ? ` · ${item.errorCode}` : ''}
                  </div>
                  <div className="small muted">{item.message ?? '处理失败'}</div>
                </div>
              ))}
          </div>
        ) : null}
      </div>
    ) : null}
  </Modal>
);
