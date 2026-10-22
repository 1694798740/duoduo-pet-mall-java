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

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { skuImage } from '@duoduo/api-sdk/images';
import { cartApi, couponApi } from '@duoduo/api-sdk/endpoints';
import type { CartItemView, CartView, UsableCouponResult } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, LoadingBlock, Modal, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useApp } from '@duoduo/shared/store/app';

const INVALID_REASON_TEXT: Record<string, string> = {
  SKU_NOT_FOUND: '商品已不存在',
  OFF_SHELF: '商品已下架',
  OUT_OF_STOCK: '已售罄',
  STOCK_NOT_ENOUGH: '库存不足',
};

/** 店铺分组（第八十一轮）：按 sellerId 分组；键为 null（快照缺失）时归入「未知店铺」 */
interface StoreGroup {
  key: string;
  /** 店铺显示名：第三方 = 商家自有店名；自营 = 多多自营；取名失败降级通用标识 */
  name: string;
  selfOwned: boolean;
  items: CartItemView[];
}

const groupByStore = (items: CartItemView[]): StoreGroup[] => {
  const map = new Map<string, StoreGroup>();
  for (const item of items) {
    const key = item.sellerId ?? 'unknown';
    let group = map.get(key);
    if (!group) {
      group = {
        key,
        name: item.storeName ?? (item.selfOwned ? '多多自营' : '品牌入驻'),
        selfOwned: item.selfOwned,
        items: [],
      };
      map.set(key, group);
    }
    group.items.push(item);
  }
  return [...map.values()];
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
  /**
   * 渲染顺序「一次锁定」：首次拿到列表时把 skuId 顺序记下来，后续 reload 一律按这个序重排。
   *
   * 后端已改成按 created_at 固定排序，这里再加一层前端护栏：
   * 勾选/改数量是「更新行」的操作，任何一次接口顺序抖动都不该让用户在视觉上看到商品跳位。
   * 新加入购物车的商品先按后端新顺序补齐，再按快照前缀保住老商品的相对位置。
   */
  const orderRef = useRef<string[]>([]);
  const orderedItems = useMemo(() => {
    const incoming = data?.items ?? [];
    if (!orderRef.current.length) {
      orderRef.current = incoming.map((i) => i.skuId);
      return incoming;
    }
    const rank = new Map(orderRef.current.map((skuId, idx) => [skuId, idx]));
    // 未知（新加购）的排在后面，保持后端给的相对顺序
    return [...incoming].sort((a, b) => {
      const ra = rank.get(a.skuId) ?? Number.MAX_SAFE_INTEGER;
      const rb = rank.get(b.skuId) ?? Number.MAX_SAFE_INTEGER;
      return ra - rb;
    });
  }, [data?.items]);
  useEffect(() => {
    if (!data) return;
    const current = orderRef.current;
    const present = new Set(data.items.map((i) => i.skuId));
    // 只保留仍在购物车里的，并把新出现的追加到尾部
    const next = current.filter((skuId) => present.has(skuId));
    for (const item of data.items) if (!next.includes(item.skuId)) next.push(item.skuId);
    orderRef.current = next;
  }, [data]);

  const validItems = orderedItems.filter((i) => i.valid);
  const allSelected = validItems.length > 0 && validItems.every((i) => i.selected);
  const someSelected = validItems.some((i) => i.selected);
  const selectedCount = data?.selectedCount ?? 0;
  /** 店铺分组：直接依赖 orderedItems，顺序快照护栏对分组同样生效 */
  const storeGroups = useMemo(() => groupByStore(orderedItems), [orderedItems]);

  // ── 结算明细（参考淘宝）：勾选金额实时驱动可用券查询，优惠即时汇总到应付 ──
  const usableFetcher = useCallback(
    () => (data && data.selectedAmount > 0
      ? couponApi.usable(data.selectedAmount)
      : Promise.resolve(null)),
    [data?.selectedAmount],
  );
  const { data: usable } = useAsync<UsableCouponResult | null>(usableFetcher);
  const bestCoupon = usable?.coupons?.find((c) => c.code === usable?.bestCouponCode && c.usable) ?? null;
  /** 优惠共减 = 当前最优可用券的抵扣（店铺优惠/平台优惠体系落地后在此累加） */
  const couponDiscount = bestCoupon?.discountAmount ?? 0;
  const payableAmount = Math.max(0, (data?.selectedAmount ?? 0) - couponDiscount);

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

      {/* 按店铺分组（参考淘宝）：每组 = 店铺名 + 组级全选 + 组内商品 */}
      {storeGroups.map((group) => {
        const groupValid = group.items.filter((i) => i.valid);
        const groupAllSelected = groupValid.length > 0 && groupValid.every((i) => i.selected);
        const groupSomeSelected = groupValid.some((i) => i.selected);
        const groupAmount = groupValid
          .filter((i) => i.selected)
          .reduce((sum, i) => sum + i.amount, 0);
        const selectGroup = async () => {
          const target = !groupAllSelected;
          setActionError(null);
          setBusy(true);
          try {
            for (const item of groupValid) {
              if (item.selected !== target) await cartApi.setSelected(item.skuId, target);
            }
            await afterMutation();
          } catch (err) {
            setActionError(toFriendlyMessage(err));
          } finally {
            setBusy(false);
          }
        };
        return (
          <section key={group.key} className="card" style={{ padding: '12px 16px 16px' }}>
            <div className="row-between" style={{ marginBottom: 10 }}>
              <label className="row small" style={{ gap: 6, fontWeight: 600 }}>
                <input
                  type="checkbox"
                  checked={groupAllSelected}
                  ref={(el) => {
                    if (el) el.indeterminate = groupSomeSelected && !groupAllSelected;
                  }}
                  disabled={groupValid.length === 0 || busy}
                  onChange={() => void selectGroup()}
                  aria-label={`选择 ${group.name} 全部商品`}
                />
                <span>{group.name}</span>
                {/* 第八十二轮：店名可点 → 店铺主页。外层是 <label>（点它会切全选框），
                    必须 preventDefault + stopPropagation，否则跳转会连带切换整组勾选。
                    自营（0 号卖家没有独立店铺主页数据）与「未知店铺」（快照缺失）不生成链接。 */}
                {!group.selfOwned && group.key !== 'unknown' ? (
                  <a
                    href={`/store/${group.key}`}
                    className="small"
                    style={{ color: 'var(--primary)' }}
                    aria-label={`进入 ${group.name} 店铺主页`}
                    onClick={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      navigate(`/store/${group.key}`);
                    }}
                  >
                    进店 ›
                  </a>
                ) : null}
                {group.selfOwned ? (
                  <span
                    className="small"
                    style={{ color: 'var(--primary)', border: '1px solid var(--primary)', borderRadius: 4, padding: '0 4px' }}
                  >
                    自营
                  </span>
                ) : null}
              </label>
              <span className="small muted">
                {group.items.length} 件{groupAmount > 0 ? ` · 已选 ${formatPrice(groupAmount)}` : ''}
              </span>
            </div>
            <div className="stack">
              {group.items.map((item) => (
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
          </section>
        );
      })}

      {/* 结算明细（参考淘宝）：优惠实时汇总，应付金额随勾选与券动态更新 */}
      <div className="card card-pad" style={{ marginTop: 18 }}>
        <h3 className="section-title" style={{ fontSize: 16, marginBottom: 10 }}>
          结算明细
          <span className="small muted" style={{ fontWeight: 400, marginLeft: 8 }}>
            实际优惠金额以下单页为准
          </span>
        </h3>
        <div className="stack" style={{ gap: 6 }}>
          <div className="row-between small">
            <span className="muted">商品总价（已选 {selectedCount} 件）</span>
            <span>{formatPrice(data.selectedAmount)}</span>
          </div>
          <div className="row-between small">
            <span className="muted">优惠券</span>
            {couponDiscount > 0 ? (
              <span style={{ color: 'var(--danger)' }}>
                {bestCoupon!.name} -{formatPrice(couponDiscount)}
              </span>
            ) : usable && usable.coupons.length > 0 ? (
              <span className="muted">暂无达门槛可用的券</span>
            ) : (
              <span className="muted">暂无可用优惠券</span>
            )}
          </div>
          {couponDiscount > 0 ? (
            <div className="row-between small" style={{ color: 'var(--danger)' }}>
              <span>优惠共减</span>
              <span>-{formatPrice(couponDiscount)}</span>
            </div>
          ) : null}
          <div className="row-between" style={{ borderTop: '1px solid var(--border)', paddingTop: 8, marginTop: 4 }}>
            <span style={{ fontWeight: 600 }}>合计：</span>
            <span style={{ fontSize: 20, fontWeight: 700, color: 'var(--danger)' }}>
              {formatPrice(payableAmount)}
            </span>
          </div>
        </div>
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
              已选 {selectedCount} 件 · 合计
            </span>
            <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--danger)' }}>
              {formatPrice(payableAmount)}
            </span>
            {couponDiscount > 0 ? (
              <span className="small" style={{ color: 'var(--danger)' }}>
                （已优惠 {formatPrice(couponDiscount)}）
              </span>
            ) : null}
            <button
              className="btn btn-primary"
              disabled={selectedCount === 0}
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
