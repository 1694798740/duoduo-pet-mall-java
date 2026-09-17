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

import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '../api/client';
import { cartApi } from '../api/endpoints';
import type { CartItemView, CartView } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, Empty, LoadingBlock, formatPrice, useToast } from '../components/ui';
import { useApp } from '../store/app';

const INVALID_REASON_TEXT: Record<string, string> = {
  SKU_NOT_FOUND: '商品已不存在',
  OFF_SHELF: '商品已下架',
  OUT_OF_STOCK: '已售罄',
  STOCK_NOT_ENOUGH: '库存不足',
};

/** 单条购物车行 */
const CartRow: React.FC<{
  item: CartItemView;
  onQuantity: (skuId: number, quantity: number) => void;
  onSelect: (skuId: number, selected: boolean) => void;
  onRemove: (skuId: number) => void;
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
            src={item.image}
            alt={item.title}
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
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
  const [pendingSku, setPendingSku] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const fetcher = useCallback(() => cartApi.list(), []);
  const { data, loading, error, reload } = useAsync<CartView>(fetcher);

  /** 所有写操作成功后统一刷新本地视图 + 头部角标 */
  const afterMutation = async () => {
    await reload();
    await refreshCart();
  };

  const changeQuantity = async (skuId: number, quantity: number) => {
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

  const changeSelection = async (skuId: number, selected: boolean) => {
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

  const removeItem = async (skuId: number) => {
    setPendingSku(skuId);
    setActionError(null);
    try {
      await cartApi.remove(skuId);
      push('已移出购物车', 'success');
      await afterMutation();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setPendingSku(null);
    }
  };

  const selectAll = async () => {
    if (!data) return;
    const validItems = data.items.filter((i) => i.valid);
    const allSelected = validItems.length > 0 && validItems.every((i) => i.selected);
    setActionError(null);
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

  const validItems = data.items.filter((i) => i.valid);
  const allSelected = validItems.length > 0 && validItems.every((i) => i.selected);

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
            onRemove={(skuId) => void removeItem(skuId)}
          />
        ))}
      </div>

      <div className="card card-pad" style={{ marginTop: 18, position: 'sticky', bottom: 12 }}>
        <div className="row-between row-wrap">
          <label className="row small" style={{ gap: 6 }}>
            <input
              type="checkbox"
              checked={allSelected}
              disabled={validItems.length === 0}
              onChange={() => void selectAll()}
            />
            全选（{validItems.length} 件可购买）
          </label>

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
    </div>
  );
};
