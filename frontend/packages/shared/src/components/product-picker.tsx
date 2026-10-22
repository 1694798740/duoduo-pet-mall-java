/**
 * 「发送商品」选择器（C 批）。
 *
 * <h2>四个来源</h2>
 * 当前浏览 / 购物车 / 收藏夹 / 已购买 —— 对应淘宝客服的「+ → 商品」心智：
 * 用户想说的是**某一件具体的商品**，让他从自己的上下文里选，比让他描述准确得多。
 *
 * <p>当前浏览只在从商品页进入会话时存在（URL 里带 productId），没有就不显示这个页签。
 * 私信只提供「当前咨询的商品」，不走这个组件（私信的商品卡固定来自会话上下文）。
 *
 * <h2>下架商品</h2>
 * 仍允许发送：用户可能就是来问「我买的这个怎么下架了」。卡片上标注「已下架」，
 * 不让它显示成一个正常的在售商品。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { cartApi, favoriteApi, purchasedApi, type PickerProduct } from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { Empty, LoadingBlock, Modal } from '@duoduo/shared/components/ui';

type Tab = 'current' | 'cart' | 'favorite' | 'purchased';

const TABS: { value: Tab; label: string }[] = [
  { value: 'current', label: '当前浏览' },
  { value: 'cart', label: '购物车' },
  { value: 'favorite', label: '收藏夹' },
  { value: 'purchased', label: '已购买' },
];

export const ProductPicker: React.FC<{
  open: boolean;
  currentProductId?: string | null;
  onClose: () => void;
  onPick: (product: PickerProduct) => void;
}> = ({ open, currentProductId, onClose, onPick }) => {
  const [tab, setTab] = useState<Tab>(currentProductId ? 'current' : 'cart');
  const [items, setItems] = useState<PickerProduct[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (which: Tab) => {
      setLoading(true);
      setError(null);
      try {
        if (which === 'cart') {
          const cart = await cartApi.list();
          const rows = (cart?.items ?? []).map((it: any) => ({
            id: String(it.spuId ?? it.id),
            title: it.title,
            image: it.mainImage ?? it.image ?? null,
            price: it.price ?? null,
            status: it.status ?? null,
          }));
          setItems(rows);
        } else if (which === 'favorite') {
          const page = await favoriteApi.list({ page: 1, pageSize: 30 });
          setItems(page?.items ?? []);
        } else if (which === 'purchased') {
          const page = await purchasedApi.list({ page: 1, pageSize: 30 });
          setItems(page?.items ?? []);
        } else {
          // current：当前浏览的商品由会话上下文给出，这里只需要一条占位数据
          setItems(
            currentProductId
              ? [{ id: currentProductId, title: '当前浏览的商品', image: null, price: null }]
              : [],
          );
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败');
        setItems([]);
      } finally {
        setLoading(false);
      }
    },
    [currentProductId],
  );

  useEffect(() => {
    if (open) void load(tab);
  }, [open, tab, load]);

  return (
    <Modal open={open} title="发送商品" onClose={onClose}>
      <div className="row row-wrap" style={{ gap: 6, marginBottom: 10 }}>
        {TABS.filter((t) => t.value !== 'current' || currentProductId).map((t) => (
          <button
            key={t.value}
            type="button"
            className={`btn btn-sm ${tab === t.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setTab(t.value)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {loading ? (
        <LoadingBlock text="正在加载商品…" />
      ) : error ? (
        <p className="small" style={{ color: 'var(--danger)' }}>{error}</p>
      ) : items.length === 0 ? (
        <Empty title="这里还没有商品" desc="换个来源看看，或先去看看商品" />
      ) : (
        <div className="col" style={{ gap: 8, maxHeight: '48vh', overflowY: 'auto' }}>
          {items.map((p) => (
            <button
              key={p.id}
              type="button"
              className="card"
              style={{ display: 'flex', gap: 10, padding: 8, textAlign: 'left', cursor: 'pointer' }}
              onClick={() => onPick(p)}
            >
              {p.image ? (
                <img
                  src={productImage(p.id, p.image)}
                  alt=""
                  style={{ width: 48, height: 48, borderRadius: 8, objectFit: 'cover' }}
                />
              ) : null}
              <span style={{ minWidth: 0, flex: 1 }}>
                <span style={{ display: 'block', fontSize: 13 }}>{p.title || `商品 ${p.id}`}</span>
                {p.price != null && <span className="small" style={{ color: 'var(--brand-strong)' }}>¥{(p.price / 100).toFixed(2)}</span>}
                {p.status && p.status !== 'ON_SALE' && (
                  <span className="tag tag-warn" style={{ marginLeft: 6, fontSize: 11 }}>已下架</span>
                )}
              </span>
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
};
