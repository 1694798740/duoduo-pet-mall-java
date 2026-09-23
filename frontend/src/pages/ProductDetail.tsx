/**
 * 商品详情页（SPU）。
 *
 * 数据来源：GET /api/products/{spuId} —— 一次返回卡片信息、图集、SKU 列表、
 * 适用属性（Suitability）以及「同类」「看过又看」两组推荐。
 *
 * 关键契约：
 *  · skus[].sellable 由服务端算好，前端不再自行判断「库存 0 / 已下架」两套规则
 *  · skus[].stock 是「可售余量」（已扣掉他人未付款的冻结量），而非常规库存
 *  · suitability.allergens 是商品所含过敏原，前端拿它跟宠物档案做提示比对
 *    （纯提示，不阻断下单——真实拦截规则在服务端）
 *  · 加购接口只收 skuId，不能加二手闲置；因此本页不出现闲置入口
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toFriendlyMessage } from '../api/client';
import { cartApi, productApi } from '../api/endpoints';
import type { ProductDetail } from '../api/types';
import { useAsync } from '../components/hooks';
import { CreditBadge } from '../components/CreditBadge';
import { ReviewSection } from '../components/ReviewSection';
import {
  Alert,
  LoadingBlock,
  ProductGrid,
  Stars,
  SupplyTag,
  useToast,
} from '../components/ui';
import { useApp } from '../store/app';
import { useAuth } from '../store/auth';

const SPECIES_LABEL: Record<string, string> = {
  cat: '猫',
  dog: '狗',
  other: '其他',
};

const LIFE_STAGE_LABEL: Record<string, string> = {
  BABY: '幼年',
  ADULT: '成年',
  SENIOR: '老年',
};

export const ProductDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  /**
   * 路由参数**保持字符串**。
   *
   * ⚠️ 这里刻意不写 `const spuId = Number(id)`：spuId 是 18 位雪花值
   * （如 `361084524781768704`），超出 JS 安全整数上限，`Number()` 会静默
   * 四舍五入到另一个整数，于是「点开商品详情」直接 404 —— 且不报任何错（T-39）。
   * 下游只把它拼进 URL，用字符串完全够用。
   */
  const spuId = id ?? '';
  const hasSpuId = spuId !== '';
  const navigate = useNavigate();
  const { push } = useToast();
  const { refreshCart } = useApp();
  const { user, pet } = useAuth();

  /** 选中的 SKU id；同为 18 位雪花值，保持 string（见上方 spuId 的说明） */
  const [skuId, setSkuId] = useState<string | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [adding, setAdding] = useState(false);

  const fetcher = useCallback(() => productApi.detail(spuId), [spuId]);
  const { data, loading, error, reload } = useAsync<ProductDetail>(fetcher, hasSpuId, [spuId]);

  const skus = data?.skus ?? [];

  // 默认选中第一个可售 SKU；若全部售罄则选第一个，用于展示价格
  useEffect(() => {
    if (!skus.length) return;
    setSkuId((prev) => {
      if (prev !== null && skus.some((s) => s.id === prev && s.sellable)) return prev;
      const firstSellable = skus.find((s) => s.sellable);
      return (firstSellable ?? skus[0] ?? null)?.id ?? null;
    });
  }, [skus]);

  const selectedSku = useMemo(
    () => skus.find((s) => s.id === skuId) ?? null,
    [skus, skuId],
  );

  // 数量上限随 SKU 切换而收敛，避免切到低库存规格后还留着超量数字
  useEffect(() => {
    if (selectedSku) setQuantity((q) => Math.min(Math.max(1, q), Math.max(1, selectedSku.stock)));
  }, [selectedSku]);

  if (!hasSpuId) {
    return <Alert kind="error">商品编号不合法</Alert>;
  }

  if (loading && !data) return <LoadingBlock text="正在加载商品详情…" />;

  if (error && !data) {
    return (
      <Alert kind="error">
        商品加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  if (!data) return null;

  const { card, suitability } = data;
  const images = data.images.length ? data.images : card.mainImage ? [card.mainImage] : [];
  const canBuy = selectedSku?.sellable === true;
  const price = selectedSku?.price ?? card.minPrice;

  // 过敏原提示：与宠物档案的记录做交集（档案为空时不做提示）
  const allergyHits = pet?.allergens?.length
    ? suitability.allergens.filter((a) => pet.allergens.includes(a))
    : [];

  const addToCart = async () => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(`/product/${spuId}`)}`);
      return;
    }
    if (!selectedSku || !selectedSku.sellable) {
      push('当前规格不可购买', 'error');
      return;
    }
    setAdding(true);
    try {
      await cartApi.add({ skuId: selectedSku.id, quantity });
      await refreshCart();
      push('已加入购物车', 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setAdding(false);
    }
  };

  return (
    <div>
      <div className="small muted" style={{ marginBottom: 12 }}>
        <Link to="/">首页</Link> / <Link to="/search">商品</Link> / <span>{card.title}</span>
      </div>

      <div className="pd-layout">
        <div>
          <div className="pd-gallery-main">
            {images[0] ? <img src={images[0]} alt={card.title} /> : null}
          </div>
          {images.length > 1 ? (
            <div className="row row-wrap" style={{ marginTop: 10 }}>
              {images.slice(0, 4).map((img, index) => (
                <img
                  key={`${img}-${index}`}
                  src={img}
                  alt={`${card.title} 图 ${index + 1}`}
                  style={{
                    width: 64,
                    height: 64,
                    objectFit: 'cover',
                    borderRadius: 10,
                    border: '1px solid var(--border)',
                  }}
                />
              ))}
            </div>
          ) : null}

          {data.detail ? (
            <div className="card card-pad" style={{ marginTop: 18 }}>
              <h3 style={{ marginBottom: 8 }}>商品介绍</h3>
              <p className="muted-2" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {data.detail}
              </p>
            </div>
          ) : null}

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 10 }}>规格参数</h3>
            <dl className="spec-list">
              <dt>品牌</dt>
              <dd>{card.brandName ?? '—'}</dd>
              <dt>分类</dt>
              <dd>{card.categoryName ?? '—'}</dd>
              <dt>适用宠物</dt>
              <dd>
                {suitability.species.length
                  ? suitability.species.map((s) => SPECIES_LABEL[s] ?? s).join(' / ')
                  : '通用'}
              </dd>
              <dt>适用阶段</dt>
              <dd>
                {suitability.lifeStages.length
                  ? suitability.lifeStages.map((l) => LIFE_STAGE_LABEL[l] ?? l).join(' / ')
                  : '全阶段'}
              </dd>
              <dt>过敏原</dt>
              <dd>{suitability.allergens.length ? suitability.allergens.join('、') : '未标注'}</dd>
              <dt>食品类</dt>
              <dd>{suitability.food ? '是（不适用 7 天无理由）' : '否（支持 7 天无理由）'}</dd>
            </dl>
            {suitability.food ? (
              <div style={{ marginTop: 12 }}>
                <Alert kind="warn">
                  食品类商品不支持 7 天无理由退货（依据《消费者权益保护法》第 25 条）。
                  如收到时已变质、破损或临期，可申请退款并提供照片凭证。
                </Alert>
              </div>
            ) : null}
          </div>
        </div>

        <div>
          <div className="pd-price-block">
            <div className="row-between row-wrap">
              <span className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                <SupplyTag supplyType={card.supplyType} />
                {/* 信用分是「卖家」的可信度：放在下单决策最显眼的位置（价格区），而不是塞进规格表底部。
                    supplyType=self 的商品归属平台自营（sellerId=0），标签随之改成「平台自营信用」。 */}
                {card.sellerId != null && card.sellerId !== '' ? (
                  <CreditBadge
                    sellerId={card.sellerId}
                    label={card.supplyType === 'self' ? '平台自营信用' : '卖家信用'}
                  />
                ) : null}
              </span>
              <span className="small muted">
                已售 {card.salesCount} ·{' '}
                {selectedSku ? `可售 ${selectedSku.stock}` : card.inStock ? '有货' : '已售罄'}
              </span>
            </div>
            <h1 style={{ fontSize: 20, margin: '10px 0 6px' }}>{card.title}</h1>
            {card.subtitle ? <div className="muted-2 small">{card.subtitle}</div> : null}
            <div className="row" style={{ marginTop: 12, alignItems: 'baseline' }}>
              <span style={{ fontSize: 30, fontWeight: 700, color: 'var(--danger)' }}>
                ¥{(price / 100).toFixed(2)}
              </span>
              {card.maxPrice > card.minPrice ? (
                <span className="small muted">
                  规格不同，最高 ¥{(card.maxPrice / 100).toFixed(2)}
                </span>
              ) : null}
              {selectedSku && selectedSku.marketPrice > selectedSku.price ? (
                <span className="small muted" style={{ textDecoration: 'line-through' }}>
                  ¥{(selectedSku.marketPrice / 100).toFixed(2)}
                </span>
              ) : null}
              <span style={{ marginLeft: 'auto' }}>
                <Stars value={card.rating} showValue />
                <span className="small muted" style={{ marginLeft: 6 }}>
                  {card.ratingCount} 条评价
                </span>
              </span>
            </div>
          </div>

          {allergyHits.length ? (
            <div style={{ marginTop: 14 }}>
              <Alert kind="error">
                ⛔ 该商品含过敏原「{allergyHits.join('、')}」，与宠物档案「{pet?.name}」的记录冲突，
                请谨慎购买。
              </Alert>
            </div>
          ) : null}

          {pet && !allergyHits.length && suitability.food ? (
            <div style={{ marginTop: 14 }}>
              <Alert kind="success">
                已比对「{pet.name}
                」的档案：未发现过敏原冲突
                {pet.lifeStage
                  ? `，适用阶段${LIFE_STAGE_LABEL[pet.lifeStage] ?? pet.lifeStage}`
                  : ''}
              </Alert>
            </div>
          ) : null}

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <div className="field-label">选择规格</div>
            <div className="row row-wrap">
              {skus.map((sku) => {
                const specText = Object.values(sku.spec).join(' / ') || '默认规格';
                return (
                  <button
                    key={sku.id}
                    type="button"
                    className={`sku-option${sku.id === skuId ? ' active' : ''}`}
                    disabled={!sku.sellable}
                    onClick={() => setSkuId(sku.id)}
                  >
                    {specText}
                    <span className="small muted" style={{ marginLeft: 6 }}>
                      ¥{(sku.price / 100).toFixed(2)}
                    </span>
                    {!sku.sellable ? <span className="small muted"> · 售罄</span> : null}
                  </button>
                );
              })}
            </div>

            <div className="row" style={{ marginTop: 16 }}>
              <span className="field-label" style={{ margin: 0 }}>
                数量
              </span>
              <div className="row">
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                >
                  −
                </button>
                <span style={{ minWidth: 36, textAlign: 'center' }}>{quantity}</span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() =>
                    setQuantity((q) => Math.min(selectedSku?.stock ?? 1, Math.min(99, q + 1)))
                  }
                >
                  +
                </button>
              </div>
              <span className="small muted">
                {selectedSku ? `该规格可售 ${selectedSku.stock}` : ''}
              </span>
            </div>

            <div className="row row-wrap" style={{ marginTop: 18 }}>
              <button
                className="btn btn-primary"
                disabled={!canBuy || adding}
                onClick={() => void addToCart()}
              >
                {canBuy ? (adding ? '加入中…' : '加入购物车') : '已售罄'}
              </button>
              <button
                className="btn btn-ghost"
                disabled={!canBuy || adding}
                onClick={async () => {
                  await addToCart();
                  navigate('/cart');
                }}
              >
                立即购买
              </button>
            </div>

            {!canBuy ? (
              <div style={{ marginTop: 12 }}>
                <Alert kind="warn">该规格暂时售罄。可以看看下方的「同类推荐」。</Alert>
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {/* 商品评价（公开可见，未登录也能看）：紧跟主体内容，属于决策信息而非补充信息 */}
      <ReviewSection spuId={data.card.id} />

      {data.sameCategory.length ? (
        <section className="section" style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2 className="section-title">同类商品</h2>
            <Link className="small muted" to={`/search?categoryId=${card.categoryId ?? ''}`}>
              更多 →
            </Link>
          </div>
          <ProductGrid items={data.sameCategory} />
        </section>
      ) : null}

      {data.alsoViewed.length ? (
        <section className="section">
          <div className="section-head">
            <h2 className="section-title">看过又看</h2>
          </div>
          <ProductGrid items={data.alsoViewed} />
        </section>
      ) : null}
    </div>
  );
};
