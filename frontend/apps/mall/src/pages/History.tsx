/**
 * 我的足迹（C 批）。
 *
 * <p>服务端按 (user, spu) 去重、只保留最近一次浏览：否则同一件商品点开三次就是三条，
 * 列表会失去「最近看过什么」的意义。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { historyApi, type PickerProduct } from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { formatBjDate, formatBjShort } from '@duoduo/shared/lib/time';

/** 足迹时间戳：今天/昨天用相对称呼，更早显示日期；时间一律北京时间（与全站口径一致） */
function viewStamp(iso: string | null | undefined): string {
  if (!iso) return '';
  const time = formatBjShort(iso).slice(6); // "HH:mm"
  const day = formatBjDate(iso);
  const now = new Date();
  if (day === formatBjDate(now.toISOString())) return `今天 ${time}`;
  if (day === formatBjDate(new Date(now.getTime() - 86_400_000).toISOString())) return `昨天 ${time}`;
  return `${day} ${time}`;
}

export const HistoryPage: React.FC = () => {
  const { push } = useToast();
  const [items, setItems] = useState<PickerProduct[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await historyApi.list({ page: 1, pageSize: 50 });
      setItems(page?.items ?? []);
      setError(null);
    } catch (e) {
      setError(toFriendlyMessage(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const clear = async () => {
    try {
      await historyApi.clear();
      setItems([]);
      push('已清空足迹', 'success');
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  if (loading) return <LoadingBlock text="正在加载足迹…" />;

  return (
    <div className="container" style={{ paddingTop: 16, paddingBottom: 32 }}>
      <div className="section-head">
        <h2 className="section-title">我的足迹</h2>
        <span className="section-sub">共 {items.length} 件</span>
        {items.length > 0 && (
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} onClick={() => void clear()}>
            清空
          </button>
        )}
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {items.length === 0 ? (
        <Empty title="还没有浏览记录" desc="逛过的商品会出现在这里" />
      ) : (
        <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
          {items.map((p) => (
            <Link key={p.id} to={`/product/${p.id}`} className="card card-pad" style={{ textDecoration: 'none' }}>
              {p.image && (
                <img
                  src={productImage(p.id, p.image)}
                  alt=""
                  style={{ width: '100%', aspectRatio: '1 / 1', objectFit: 'cover', borderRadius: 10 }}
                />
              )}
              <div className="small" style={{ marginTop: 8 }}>{p.title}</div>
              {p.price != null && (
                <div className="small" style={{ color: 'var(--brand-strong)' }}>¥{(p.price / 100).toFixed(2)}</div>
              )}
              {p.viewedAt && (
                <div className="small" style={{ color: 'var(--text-2)', marginTop: 2 }}>
                  浏览于 {viewStamp(p.viewedAt)}
                </div>
              )}
            </Link>
          ))}
        </div>
      )}
    </div>
  );
};
