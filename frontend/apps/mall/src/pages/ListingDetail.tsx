/**
 * 闲置详情页。
 *
 * 数据来源：GET /api/listings/{id} —— 返回卡片、描述、图集，
 * 以及「同款全新」（sameCategoryNew），用于把 C2C 流量导回自营商品。
 *
 * 关键约束：本期加购接口只接受 skuId，**不能把闲置加进购物车**。
 * 因此本页只做展示与「同款全新」跳转，不放下单按钮——
 * 避免给出一个点下去必然报错的假入口。
 */

import React, { useCallback } from 'react';
import { listingGallery } from '@duoduo/api-sdk/images';
import { Link, useParams } from 'react-router-dom';
import { productApi } from '@duoduo/api-sdk/endpoints';
import type { ListingDetail } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { CreditBadge } from '@duoduo/shared/components/CreditBadge';
import { Alert, LoadingBlock, ProductGrid, conditionLabelOf, formatPrice } from '@duoduo/shared/components/ui';

export const ListingDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  /**
   * 路由参数**保持字符串**（同 ProductDetail 的处理）。
   *
   * `Number(id)` 会把 18 位雪花值静默四舍五入成另一个整数，
   * 拼进 URL 就查不到那条闲置 —— 表现为「点开详情 404」且无任何报错（T-39）。
   */
  const listingId = id ?? '';
  const hasListingId = listingId !== '';

  const fetcher = useCallback(() => productApi.listing(listingId), [listingId]);
  const { data, loading, error, reload } = useAsync<ListingDetail>(fetcher, hasListingId, [
    listingId,
  ]);

  if (!hasListingId) return <Alert kind="error">闲置编号不合法</Alert>;
  if (loading && !data) return <LoadingBlock text="正在加载闲置详情…" />;

  if (error && !data) {
    return (
      <Alert kind="error">
        闲置加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  if (!data) return null;

  const { card } = data;
  // 闲置图集：用户真实上传的（/uploads/）原样用；后端占位路径映射到本地图池
  const images = listingGallery(card.id, data.images, card.cover);
  const discount =
    card.originalPrice > card.price
      ? Math.round(((card.originalPrice - card.price) / card.originalPrice) * 100)
      : 0;

  return (
    <div>
      <div className="small muted" style={{ marginBottom: 12 }}>
        <Link to="/">首页</Link> / <Link to="/listings">二手闲置</Link> / <span>{card.title}</span>
      </div>

      <div className="pd-layout">
        <div>
          <div className="pd-gallery-main">
            {images[0] ? <img src={images[0]} alt={card.title} loading="lazy" decoding="async" /> : null}
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
                  loading="lazy"
                  decoding="async"
                />
              ))}
            </div>
          ) : null}

          {data.description ? (
            <div className="card card-pad" style={{ marginTop: 18 }}>
              <h3 style={{ marginBottom: 8 }}>卖家描述</h3>
              <p className="muted-2" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {data.description}
              </p>
            </div>
          ) : null}
        </div>

        <div>
          <div className="pd-price-block">
            <div className="row-between row-wrap">
              <span className="tag tag-second">个人闲置</span>
              <span className="small muted">{card.viewCount} 次浏览</span>
            </div>
            <h1 style={{ fontSize: 20, margin: '10px 0 6px' }}>{card.title}</h1>
            <div className="row row-wrap" style={{ marginTop: 12, alignItems: 'baseline' }}>
              <span style={{ fontSize: 30, fontWeight: 700, color: 'var(--danger)' }}>
                ¥{(card.price / 100).toFixed(2)}
              </span>
              {card.originalPrice > card.price ? (
                <>
                  <span className="small muted" style={{ textDecoration: 'line-through' }}>
                    原价 {formatPrice(card.originalPrice)}
                  </span>
                  <span className="tag tag-success">省 {discount}%</span>
                </>
              ) : null}
            </div>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <dl className="spec-list">
              <dt>成色</dt>
              <dd>{conditionLabelOf(card.conditionLevel) || '未标注'}</dd>
              <dt>所在城市</dt>
              <dd>{card.city ?? '未标注'}</dd>
              <dt>物种</dt>
              <dd>
                {card.species === 'cat' ? '猫咪' : card.species === 'dog' ? '狗狗' : card.species ?? '未标注'}
              </dd>
              <dt>品种</dt>
              <dd>{card.breed ?? '未标注'}</dd>
              {/* C2C 是一对一交易，卖家可不可信比「成色」更影响决策 —— 放在规格表里最显眼 */}
              <dt>卖家信用</dt>
              <dd>
                {card.sellerId != null && card.sellerId !== '' ? (
                  <div className="row" style={{ gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                    <CreditBadge sellerId={card.sellerId} label="卖家信用" />
                    {/* S6-D1：进卖家主页。此前这里只能看到信用分，看不到"他是谁、还卖什么" ——
                        C2C 的决策信息差一截，而小程序侧这一跳一直是通的。 */}
                    <Link className="btn btn-ghost btn-sm" to={'/user/' + card.sellerId}>
                      查看卖家主页
                    </Link>
                  </div>
                ) : (
                  <span className="muted">暂无</span>
                )}
              </dd>
            </dl>
          </div>

          <div style={{ marginTop: 16 }}>
            <Alert kind="info">
              本期买家端仅支持浏览闲置，下单流程尚未开放（发布/下单入口在后续迭代）。
              可以先看看下方「同款全新」的自营商品，支持直接下单。
            </Alert>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 10 }}>担保交易规则</h3>
            <ol className="small muted-2" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.9 }}>
              <li>买家下单后货款冻结在平台，不直接打给卖家。</li>
              <li>卖家发货，买家确认收货后货款才结算给卖家。</li>
              <li>卖家未发货前，买家可自助申请退款，款项原路退回。</li>
            </ol>
          </div>
        </div>
      </div>

      {data.sameCategoryNew.length ? (
        <section className="section" style={{ marginTop: 28 }}>
          <div className="section-head">
            <h2 className="section-title">同款全新</h2>
            <span className="section-sub">正品保障 · 支持直接下单</span>
          </div>
          <ProductGrid items={data.sameCategoryNew} />
        </section>
      ) : null}
    </div>
  );
};
