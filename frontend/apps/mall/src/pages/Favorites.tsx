/**
 * 我的收藏夹（C 批）。
 *
 * <p>与「发送商品」选择器同源：这里收藏的商品，在客服会话里可以直接发给客服。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { favoriteApi, type PickerProduct } from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock } from '@duoduo/shared/components/ui';

export const FavoritesPage: React.FC = () => {
  const [items, setItems] = useState<PickerProduct[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await favoriteApi.list({ page: 1, pageSize: 50 });
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

  if (loading) return <LoadingBlock text="正在加载收藏夹…" />;

  return (
    <div className="container" style={{ paddingTop: 16, paddingBottom: 32 }}>
      <div className="section-head">
        <h2 className="section-title">我的收藏夹</h2>
        <span className="section-sub">共 {items.length} 件</span>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {items.length === 0 ? (
        <Empty title="还没有收藏商品" desc="在商品详情点「收藏」，之后可以在客服会话里直接发出去" />
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
            </Link>
          ))}
        </div>
      )}
    </div>
  );
};
