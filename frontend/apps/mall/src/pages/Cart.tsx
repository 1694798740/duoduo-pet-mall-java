/**
 * 购物车页。
 *
 * 数据来源：/api/cart 系列（全部需要登录）
 *   GET    /api/cart                购物车全量（含失效项与合计）
 *   PATCH  /api/cart/items/{skuId}              改数量
 *   PATCH  /api/cart/items/{skuId}/selection    改勾选
 *   DELETE /api/cart/items/{skuId}              删条目
 *   DELETE /api/cart/selected                   清空已勾选
 *
 * 关键契约：
 *  · 条目主键是 **skuId**，不是行 id —— 所有变更接口路径里都用 skuId
 *  · valid=false 的条目不可勾选、不可结算，界面要显式标出原因
 *  · selectedAmount 由服务端算好（仅统计「已勾选且有效」），前端不重复累加，
 *    避免与后端口径出现分毫差异
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { skuImage } from '@duoduo/api-sdk/images';
import { cartApi } from '@duoduo/api-sdk/endpoints';
import type { CartItemView, CartView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, LoadingBlock, Modal, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useApp } from '@duoduo/shared/store/app';

const INVALID_REASON_TEXT: Record<string, string> = {
  SKU_NOT_FOUND: '商品已不存在',
  OFF_SHELF: '商品已下架',
  OUT_OF_STOCK: '已售罄',
  STOCK_NOT_ENOUGH: '库存不足',
};

/**
 * 单条购物车行。
 *
 * ⚠️ `skuId` 类型是 **string** 而不是 number：它是 18 位雪花 ID，
 * 超出 JS 安全整数上限，以数字形态流转会被静默四舍五入
 * （见 `types.ts` 顶部与 docs/agent/08 的 T-39）。只做拼 URL 与相等比较。
 */
const CartRow: React.FC<{
  item: CartItemView;
  onQuantity: (skuId: string, quantity: number) => void;
  onSelect: (skuId: string, selected: boolean) => void;
  onRemove: (skuId: string) => void;
  pending: boolean;
}> = ({ item, onQuantity, onSelect, onRemove, pending }) => {
  const [draft, setDraft] = useState(String(item.quantity));

  // 服务端数量变化（如库存收敛）时同步输入框
  useEffect(() => {
    setDraft(String(item.quantity));
  }, [item.quantity]);

  const commit = () => {
    const next = Number(draft);
    if (!Number.isFinite(next) || next < 1) {
      setDraft(String(item.quantity));
      return;
    }
    const clamped = Math.min(next, Math.min(200, Math.max(1, item.availableStock || 1)));
    if (clamped !== item.quantity) onQuantity(item.skuId, clamped);
    setDraft(String(clamped));
  };

  return (
    <div className="card card-pad" style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
      <input
        type="checkbox"
        style={{ marginTop: 6 }}
        checked={item.selected}
        disabled={!item.valid || pending}
        onChange={(e) => onSelect(item.skuId, e.target.checked)}
        aria-label={`选择 ${item.title}`}
      />

      <Link
        to={`/product/${item.spuId}`}
        style={{ width: 72, height: 72, flex: '0 0 auto', borderRadius: 10, overflow: 'hidden' }}
      >
        {item.image ? (
          <img
            src={skuImage(item.skuId, item.spuId, item.image)}
            alt={item.title}
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            loading="lazy"
            decoding="async"
          />
        ) : (
          <div className="skeleton" style={{ width: '100%', height: '100%' }} />
        )}
      </Link>

      <div style={{ flex: 1, minWidth: 0 }}>
        <Link to={`/product/${item.spuId}`} className="goods-title" style={{ display: 'block' }}>
          {item.title}
        </Link>
        <div className="small muted">{item.specText ?? '默认规格'}</div>

        {!item.valid ? (
          <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>
            ⚠️ {INVALID_REASON_TEXT[item.invalidReason ?? ''] ?? '该商品当前不可购买'}
          </div>
        ) : (
          <div className="small muted" style={{ marginTop: 4 }}>
            可售 {item.availableStock} 件
          </div>
        )}

        <div className="row row-wrap" style={{ marginTop: 8 }}>
          <span style={{ color: 'var(--danger)', fontWeight: 600 }}>{formatPrice(item.price)}</span>

          <div className="row">
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              disabled={pending || !item.valid || item.quantity <= 1}
              onClick={() => onQuantity(item.skuId, item.quantity - 1)}
            >
              −
            </button>
            <input
              className="input"
              style={{ width: 60, textAlign: 'center' }}
              value={draft}
              disabled={!item.valid || pending}
              inputMode="numeric"
              onChange={(e) => setDraft(e.target.value.replace(/\D/g, '').slice(0, 3))}
              onBlur={commit}
              onKeyDown={(e) => {
                if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
              }}
              aria-label="数量"
            />
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              disabled={pending || !item.valid || item.quantity >= Math.min(200, item.availableStock)}
              onClick={() => onQuantity(item.skuId, item.quantity + 1)}
            >
              +
            </button>
          </div>

          <span className="small muted" style={{ marginLeft: 'auto' }}>
            小计 {item.valid ? formatPrice(item.amount) : '—'}
          </span>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={pending}
            onClick={() => onRemove(item.skuId)}
          >
            删除
          </button>
        </div>
      </div>
    </div>
  );
};

export const CartPage: React.FC = () => {
  const navigate = useNavigate();
  const { push } = useToast();
  const { refreshCart } = useApp();
  const [pendingSku, setPendingSku] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  /** 删除前确认：单条 / 已选批量（全选时文案升级为「清空购物车」） */
  const [confirm, setConfirm] = useState<
    | { kind: 'item'; skuId: string; title: string }
    | { kind: 'selected'; count: number; amount: number; all: boolean }
    | null
  >(null);
  const [busy, setBusy] = useState(false);
  const allRef = useRef<HTMLInputElement | null>(null);

  const fetcher = useCallback(() => cartApi.list(), []);
  const { data, loading, error, reload } = useAsync<CartView>(fetcher);

  // 派生状态放在 hooks 区（组件有 early return，hooks 不能在其后）
  const items = data?.items ?? [];
  const validItems = items.filter((i) => i.valid);
  const allSelected = validItems.length > 0 && validItems.every((i) => i.selected);
  const someSelected = validItems.some((i) => i.selected);
  const selectedCount = data?.selectedCount ?? 0;

  // 只选了一部分时，全选框显示为「半选」——否则用户以为没选
  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = someSelected && !allSelected;
  }, [someSelected, allSelected]);

  /** 所有写操作成功后统一刷新本地视图 + 头部角标（数量/合计/全选态一起变） */
  const afterMutation = async () => {
    await reload();
    await refreshCart();
  };

  const changeQuantity = async (skuId: string, quantity: number) => {
    setPendingSku(skuId);
    setActionError(null);
    try {
      await cartApi.updateQuantity(skuId, quantity);
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setPendingSku(null);
    }
  };

  const changeSelection = async (skuId: string, selected: boolean) => {
    setPendingSku(skuId);
    setActionError(null);
    try {
      await cartApi.setSelected(skuId, selected);
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setPendingSku(null);
    }
  };

  /** 真正执行删除（确认弹窗之后才调用） */
  const doRemoveItem = async (skuId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await cartApi.remove(skuId);
      push('已移出购物车', 'success');
      setConfirm(null);
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /** 删除已选（全选时即「清空已选中的全部商品」） */
  const doRemoveSelected = async () => {
    setBusy(true);
    setActionError(null);
    try {
      const res = await cartApi.removeSelected();
      push(`已删除 ${res?.removed ?? selectedCount} 件商品`, 'success');
      setConfirm(null);
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const selectAll = async () => {
    if (!data) return;
    setActionError(null);
    setBusy(true);
    try {
      // 逐条设置：后端没有批量勾选接口，串行保证每步都成功
      for (const item of validItems) {
        if (item.selected !== !allSelected) {
          await cartApi.setSelected(item.skuId, !allSelected);
        }
      }
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (loading && !data) return <LoadingBlock text="正在加载购物车…" />;

  if (error && !data) {
    return (
      <Alert kind="error">
        购物车加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  if (!data) return null;

  if (data.items.length === 0) {
    return (
      <Empty
        icon="🛒"
        title="购物车还是空的"
        desc="去逛逛，自营商品和个人闲置都在等你"
        action={
          <div className="row row-wrap" style={{ justifyContent: 'center' }}>
            <Link className="btn btn-primary" to="/search">
              逛商品
            </Link>
            <Link className="btn btn-ghost" to="/listings">
              看闲置
            </Link>
          </div>
        }
      />
    );
  }

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">购物车（{data.totalQuantity} 件）</h2>
        {data.invalidReasons.length ? (
          <span className="section-sub" style={{ color: 'var(--danger)' }}>
            有 {data.items.length - validItems.length} 件商品当前不可购买
          </span>
        ) : null}
      </div>

      {actionError ? (
        <div style={{ marginBottom: 14 }}>
          <Alert kind="error">{actionError}</Alert>
        </div>
      ) : null}

      <div className="stack">
        {data.items.map((item) => (
          <CartRow
            key={item.skuId}
            item={item}
            pending={pendingSku === item.skuId}
            onQuantity={(skuId, q) => void changeQuantity(skuId, q)}
            onSelect={(skuId, s) => void changeSelection(skuId, s)}
            onRemove={() => setConfirm({ kind: 'item', skuId: item.skuId, title: item.title })}
          />
        ))}
      </div>

      <div className="card card-pad" style={{ marginTop: 18, position: 'sticky', bottom: 12 }}>
        <div className="row-between row-wrap">
          <label className="row small" style={{ gap: 6 }}>
            <input
              ref={allRef}
              type="checkbox"
              checked={allSelected}
              disabled={validItems.length === 0 || busy}
              onChange={() => void selectAll()}
            />
            全选（{validItems.length} 件可购买）
          </label>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            style={{ color: 'var(--danger)' }}
            disabled={selectedCount === 0 || busy}
            onClick={() =>
              setConfirm({
                kind: 'selected',
                count: selectedCount,
                amount: data.selectedAmount,
                all: allSelected,
              })
            }
          >
            删除已选{selectedCount > 0 ? `（${selectedCount}）` : ''}
          </button>

          <div className="row row-wrap" style={{ marginLeft: 'auto' }}>
            <span className="small muted">
              已选 {data.selectedCount} 件 · 合计
            </span>
            <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--danger)' }}>
              {formatPrice(data.selectedAmount)}
            </span>
            <button
              className="btn btn-primary"
              disabled={data.selectedCount === 0}
              onClick={() => navigate('/checkout')}
            >
              去结算
            </button>
          </div>
        </div>
      </div>

      <Modal
        open={confirm != null}
        title={
          confirm?.kind === 'item'
            ? '移出购物车'
            : confirm?.all
              ? '清空已选商品'
              : '删除已选商品'
        }
        onClose={() => (busy ? undefined : setConfirm(null))}
        footer={
          <>
            <button className="btn btn-ghost" disabled={busy} onClick={() => setConfirm(null)}>
              取消
            </button>
            <button
              className="btn"
              style={{ background: 'var(--danger)' }}
              disabled={busy}
              onClick={() =>
                void (confirm?.kind === 'item' ? doRemoveItem(confirm.skuId) : doRemoveSelected())
              }
            >
              {busy ? '处理中…' : '确认删除'}
            </button>
          </>
        }
      >
        <p className="small">
          {confirm?.kind === 'item'
            ? `确定将「${confirm.title}」移出购物车吗？此操作不可撤销。`
            : confirm?.all
              ? `已全选 ${confirm?.count ?? 0} 件可购买商品（合计 ${formatPrice(confirm?.amount ?? 0)}），将全部删除且不可撤销。`
              : `将删除已选的 ${confirm?.count ?? 0} 件商品（合计 ${formatPrice(confirm?.amount ?? 0)}），此操作不可撤销。`}
        </p>
      </Modal>
    </div>
  );
};
