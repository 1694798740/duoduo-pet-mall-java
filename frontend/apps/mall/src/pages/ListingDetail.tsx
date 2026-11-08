/**
 * 闲置详情页。
 *
 * 数据来源：GET /api/listings/{id} —— 返回卡片、描述、图集，
 * 以及「同款全新」（sameCategoryNew），用于把 C2C 流量导回自营商品。
 *
 * 担保下单（B-11）：POST /api/listings/{id}/orders —— 路径 id 为准，
 * 收货三要素 + 幂等键走请求体；价格由服务端按闲置快照取（这里不传）。
 * 下单成功跳既有订单支付页（/orders/{orderNo}），支付/确认收货全复用商城链路。
 * 单件防超卖在服务端 CAS —— 被并发买走时返回 422「手慢了」，如实提示即可。
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { listingGallery } from '@duoduo/api-sdk/images';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { addressApi, listingApi, productApi } from '@duoduo/api-sdk/endpoints';
import { newIdempotentKey, toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { AddressView, ListingDetail } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { CreditBadge } from '@duoduo/shared/components/CreditBadge';
import {
  Alert,
  Field,
  LoadingBlock,
  ProductGrid,
  conditionLabelOf,
  formatPrice,
  useToast,
} from '@duoduo/shared/components/ui';

const PHONE_PATTERN = /^1[3-9]\d{9}$/;

export const ListingDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { push } = useToast();
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

  // ---- 担保下单（B-11）----
  const [receiverName, setReceiverName] = useState('');
  const [receiverPhone, setReceiverPhone] = useState('');
  const [receiverAddress, setReceiverAddress] = useState('');
  const [orderBusy, setOrderBusy] = useState(false);
  const [orderError, setOrderError] = useState<string | null>(null);
  // 幂等键：进入本页生成一次，提交失败重试保持不变（连点/网络重试不会一物两单）
  const idempotentKeyRef = useRef<string>(newIdempotentKey());

  /** 地址簿预填：有默认地址就填进表单（用户可改），没有就留空让用户手填 —— 首单不被挡 */
  const addrFetcher = useCallback(() => addressApi.list(), []);
  const { data: addressList } = useAsync<AddressView[]>(addrFetcher, true, []);
  const prefilledRef = useRef(false);
  useEffect(() => {
    if (prefilledRef.current || !addressList || addressList.length === 0) return;
    prefilledRef.current = true;
    const preferred = addressList.find((a) => a.isDefault) ?? addressList[0];
    if (preferred) {
      setReceiverName((v) => v || preferred.receiverName);
      setReceiverPhone((v) => v || preferred.receiverPhone);
      setReceiverAddress((v) => v || preferred.fullAddress);
    }
  }, [addressList]);

  const submitOrder = async () => {
    setOrderError(null);
    if (!receiverName.trim()) {
      setOrderError('请填写收货人姓名');
      return;
    }
    if (!PHONE_PATTERN.test(receiverPhone.trim())) {
      setOrderError('请填写合法的 11 位收货手机号');
      return;
    }
    if (receiverAddress.trim().length < 6) {
      setOrderError('收货地址太短，请填写详细地址');
      return;
    }
    setOrderBusy(true);
    try {
      const created = await listingApi.placeOrder(listingId, {
        receiverName: receiverName.trim(),
        receiverPhone: receiverPhone.trim(),
        receiverAddress: receiverAddress.trim(),
        idempotentKey: idempotentKeyRef.current,
      });
      push('下单成功，请在 30 分钟内完成支付', 'success');
      navigate(`/orders/${created.orderNo}`);
    } catch (err) {
      // 422「手慢了」= 被并发买走（CAS 落空）；403 = 未实名；401 = 未登录 —— 都带原因文案
      setOrderError(toFriendlyMessage(err));
    } finally {
      setOrderBusy(false);
    }
  };

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

          {/* 担保下单（B-11）：单件直购，不需要独立结算页 —— 收货信息 + 一键下单 */}
          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 6 }}>担保下单</h3>
            <p className="small muted" style={{ margin: '0 0 12px' }}>
              货款由平台托管，确认收货后结算给卖家；30 分钟未支付订单自动关闭。
              地址已从地址簿预填（有默认地址时），可自行修改。
            </p>

            {orderError ? (
              <div style={{ marginBottom: 12 }}>
                <Alert kind="error">{orderError}</Alert>
              </div>
            ) : null}

            <div className="grid grid-2">
              <Field label="收货人" required>
                <input
                  className="input"
                  value={receiverName}
                  onChange={(e) => setReceiverName(e.target.value)}
                  placeholder="姓名"
                  maxLength={30}
                />
              </Field>
              <Field label="手机号" required>
                <input
                  className="input"
                  value={receiverPhone}
                  onChange={(e) => setReceiverPhone(e.target.value.replace(/\D/g, '').slice(0, 11))}
                  placeholder="11 位手机号"
                  inputMode="numeric"
                />
              </Field>
            </div>
            <Field label="收货地址" required>
              <textarea
                className="textarea"
                rows={2}
                value={receiverAddress}
                onChange={(e) => setReceiverAddress(e.target.value)}
                placeholder="省 / 市 / 区 + 详细地址"
                maxLength={180}
              />
            </Field>

            <button
              className="btn btn-primary btn-block"
              style={{ marginTop: 6 }}
              disabled={orderBusy}
              onClick={submitOrder}
            >
              {orderBusy ? '提交中…' : `担保下单 · ¥${(card.price / 100).toFixed(2)}`}
            </button>
            <div className="small muted" style={{ marginTop: 8 }}>
              单件商品先到先得；重复点击不会重复下单（幂等键已固定）。
            </div>
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
