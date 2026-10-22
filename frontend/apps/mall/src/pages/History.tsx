/**
 * 我的足迹（C 批，2026-10-22 重构：按天分组 + 分页 + 批量删除 + 搜索）。
 *
 * <p>服务端按 (user, spu) 去重、只保留最近一次浏览：同一件商品点开三次就是一条，
 * 归入最后一次浏览的那天。接口返回扁平倒序列表，按天分组是纯前端行为
 * （与淘宝同构）；分组必须按北京时间切自然日（bjDateKey），不能拿 UTC 串截日期。
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { historyApi, type PickerProduct } from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import { bjDateKey, formatBjShort, historyGroupLabel } from '@duoduo/shared/lib/time';

const PAGE_SIZE = 50;

/** 组内时间：组标题已带日期，卡片里只留 HH:mm */
function timeOnly(iso: string | null | undefined): string {
  if (!iso) return '';
  return formatBjShort(iso).slice(6);
}

interface DayGroup {
  key: string;
  label: string;
  items: PickerProduct[];
}

const offShelf = (p: PickerProduct) => p.status != null && p.status !== 'ON_SALE';

export const HistoryPage: React.FC = () => {
  const { push } = useToast();
  const [items, setItems] = useState<PickerProduct[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [confirmAction, setConfirmAction] = useState<'batch' | 'clear' | null>(null);
  const [busy, setBusy] = useState(false);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(async (nextPage: number, replace: boolean) => {
    if (replace) setLoading(true);
    else setLoadingMore(true);
    try {
      const res = await historyApi.list({ page: nextPage, pageSize: PAGE_SIZE });
      const fresh = res?.items ?? [];
      setTotal(res?.total ?? fresh.length);
      setItems((prev) => {
        if (replace) return fresh;
        // 同一商品跨页不会重复（服务端已去重），这里按 id 兜底
        const seen = new Set(prev.map((i) => i.id));
        return [...prev, ...fresh.filter((i) => !seen.has(i.id))];
      });
      setPage(nextPage);
      setError(null);
    } catch (e) {
      setError(toFriendlyMessage(e));
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    void load(1, true);
  }, [load]);

  const hasMore = items.length < total;

  // 滚到底自动加载下一页（按钮作兜底）
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || !hasMore || loadingMore) return undefined;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((en) => en.isIntersecting)) void load(page + 1, false);
      },
      { rootMargin: '240px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore, loadingMore, page, load]);

  // 本地搜索：只过滤已加载列表（不动后端）
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? items.filter((i) => (i.title ?? '').toLowerCase().includes(q)) : items;
  }, [items, query]);

  // 按北京自然日分组（接口已倒序，Map 保序即组间降序）
  const groups = useMemo<DayGroup[]>(() => {
    const map = new Map<string, PickerProduct[]>();
    for (const item of filtered) {
      if (!item.viewedAt) continue;
      const key = bjDateKey(item.viewedAt);
      const bucket = map.get(key);
      if (bucket) bucket.push(item);
      else map.set(key, [item]);
    }
    return [...map.entries()].map(([key, list]) => ({
      key,
      label: list[0]?.viewedAt ? historyGroupLabel(list[0].viewedAt) : key,
      items: list,
    }));
  }, [filtered]);

  const checkedIds = useMemo(() => [...checked], [checked]);
  const toggle = (id: string) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const allLoadedChecked = filtered.length > 0 && filtered.every((i) => checked.has(i.id));
  const toggleAll = () =>
    setChecked(allLoadedChecked ? new Set() : new Set(filtered.map((i) => i.id)));

  const doBatchDelete = async () => {
    if (checkedIds.length === 0) return;
    setBusy(true);
    try {
      await historyApi.removeBatch(checkedIds);
      push(`已删除 ${checkedIds.length} 条足迹`, 'success');
      setChecked(new Set());
      setConfirmAction(null);
      await load(1, true);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doClear = async () => {
    setBusy(true);
    try {
      await historyApi.clear();
      push('已清空足迹', 'success');
      setChecked(new Set());
      setConfirmAction(null);
      await load(1, true);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <LoadingBlock text="正在加载足迹…" />;

  return (
    <div className="container" style={{ paddingTop: 16, paddingBottom: 32 }}>
      <div className="section-head" style={{ flexWrap: 'wrap', gap: 10 }}>
        <h2 className="section-title">我的足迹</h2>
        <span className="section-sub">共 {total} 件</span>
        {items.length > 0 && (
          <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10 }}>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索商品标题"
              className="input"
              style={{ height: 34, width: 180 }}
            />
            <label className="small" style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
              <input type="checkbox" checked={allLoadedChecked} onChange={toggleAll} />
              全选已加载
            </label>
            <button
              className="btn btn-ghost btn-sm"
              disabled={checkedIds.length === 0 || busy}
              onClick={() => setConfirmAction('batch')}
            >
              删除所选{checkedIds.length > 0 ? `（${checkedIds.length}）` : ''}
            </button>
            <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setConfirmAction('clear')}>
              清空
            </button>
          </div>
        )}
      </div>
      {error && <Alert kind="error">{error}</Alert>}

      {items.length === 0 ? (
        <Empty
          title="还没有浏览记录"
          desc="逛过的商品会出现在这里"
          action={
            <Link to="/" className="btn btn-primary">
              去逛逛
            </Link>
          }
        />
      ) : filtered.length === 0 ? (
        <Empty title="没有匹配的商品" desc={`找不到标题包含「${query.trim()}」的足迹`} />
      ) : (
        <>
          {groups.map((g) => (
            <section key={g.key} style={{ marginBottom: 20 }}>
              <div className="history-day">
                <span>{g.label}</span>
                <span className="history-day-count">（{g.items.length}件）</span>
              </div>
              <div
                className="grid"
                style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}
              >
                {g.items.map((p) => (
                  <div key={p.id} className={`card card-pad history-card${offShelf(p) ? ' history-off' : ''}`}>
                    <input
                      type="checkbox"
                      className="history-check"
                      aria-label="选择该足迹"
                      checked={checked.has(p.id)}
                      onChange={() => toggle(p.id)}
                    />
                    {offShelf(p) && <span className="history-off-badge">已下架</span>}
                    <Link to={`/product/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                      {p.image && (
                        <img
                          src={productImage(p.id, p.image)}
                          alt=""
                          style={{ width: '100%', aspectRatio: '1 / 1', objectFit: 'cover', borderRadius: 10 }}
                        />
                      )}
                      <div className="small" style={{ marginTop: 8 }}>{p.title}</div>
                      {p.price != null && (
                        <div className="small" style={{ color: 'var(--brand-strong)' }}>
                          ¥{(p.price / 100).toFixed(2)}
                        </div>
                      )}
                      {p.viewedAt && (
                        <div className="small" style={{ color: 'var(--text-2)', marginTop: 2 }}>
                          浏览于 {timeOnly(p.viewedAt)}
                        </div>
                      )}
                    </Link>
                  </div>
                ))}
              </div>
            </section>
          ))}
          <div ref={sentinelRef} />
          {hasMore && (
            <div style={{ textAlign: 'center', marginTop: 8 }}>
              <button className="btn btn-ghost" disabled={loadingMore} onClick={() => void load(page + 1, false)}>
                {loadingMore ? '正在加载…' : '加载更多'}
              </button>
            </div>
          )}
          {!hasMore && items.length > PAGE_SIZE && (
            <div className="small" style={{ textAlign: 'center', color: 'var(--text-2)', marginTop: 12 }}>
              已经到底啦，共 {items.length} 条
            </div>
          )}
        </>
      )}

      <Modal
        open={confirmAction != null}
        title={confirmAction === 'clear' ? '清空全部足迹' : '删除所选足迹'}
        onClose={() => setConfirmAction(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setConfirmAction(null)} disabled={busy}>
              取消
            </button>
            <button
              className="btn"
              style={{ background: 'var(--danger)' }}
              disabled={busy}
              onClick={() => void (confirmAction === 'clear' ? doClear() : doBatchDelete())}
            >
              {busy ? '处理中…' : '确认删除'}
            </button>
          </>
        }
      >
        <p className="small">
          {confirmAction === 'clear'
            ? `将删除全部 ${total} 条足迹，且不可恢复。删除后再浏览同类商品会重新记录。`
            : `将删除所选 ${checkedIds.length} 条足迹，不可恢复。删除后再浏览会重新记录。`}
        </p>
      </Modal>
    </div>
  );
};
