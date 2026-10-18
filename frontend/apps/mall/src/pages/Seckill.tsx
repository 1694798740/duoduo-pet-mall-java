/**
 * 限时秒杀（A14，交易域 duoduo-trade）。
 *
 * 数据源：
 *   GET  /api/seckill/activities    活动列表（**公开只读**，未登录也能看 —— 导流页）
 *   POST /api/seckill/orders        秒杀下单（需登录，服务端 Redis 预扣 + DB 兜底防超卖）
 *
 * 两个刻意的设计：
 *   1. **余量 / 限购 / 售罄全部以服务端判定为准**：列表上的 availableStock 只是展示值，
 *      与真实值可能存在瞬间偏差（Redis 预扣挡量的正常代价）；抢不到时后端会返回明确的
 *      业务错误（限购 / 已抢完），前端原样提示，不做本地「还能不能抢」的推断。
 *   2. **下单成功跳订单详情付款**：秒杀单就是普通 PENDING_PAYMENT 订单（order_type=SECKILL），
 *      支付 / 取消 / 超时关单复用既有链路，页面不重复造收银台。
 *
 * 未登录时抢购按钮引导去登录（与领券中心同口径），浏览不受影响。
 */

import React, { useCallback, useState } from 'react';
import { skuImage } from '@duoduo/api-sdk/images';
import { Link, useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { addressApi, couponApi, seckillApi } from '@duoduo/api-sdk/endpoints';
import type { CouponView, SeckillActivityView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, LoadingBlock, formatDate, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

const phaseTone = (phase: string): string => {
  switch (phase) {
    case 'ONGOING':
      return 'tag tag-warn';
    case 'UPCOMING':
      return 'tag tag-success';
    default:
      return 'tag';
  }
};

const phaseLabel = (phase: string): string => {
  switch (phase) {
    case 'ONGOING':
      return '抢购中';
    case 'UPCOMING':
      return '即将开始';
    case 'ENDED':
      return '已结束';
    default:
      return phase;
  }
};

const newIdempotentKey = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `sk-${Date.now()}-${Math.random()}`;

export const SeckillPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const [placingSku, setPlacingSku] = useState<string | null>(null);

  const state = useAsync<SeckillActivityView[]>(
    (signal) => seckillApi.activities({ signal }),
    true,
    [user?.id],
  );

  /**
   * 我的未使用券（2026-10-18 起秒杀可用券）：一次拉回本地按秒杀价挑最优，
   * 真正能不能用仍由服务端锁券决定 —— 这里只负责展示与预选，不做最终裁决。
   */
  const coupons = useAsync<CouponView[]>(
    (signal) => (user ? couponApi.mine({ status: 'UNUSED' }, { signal }) : Promise.resolve([])),
    true,
    [user?.id],
  );

  const bestCouponFor = useCallback(
    (price: number): CouponView | null => {
      let best: CouponView | null = null;
      for (const c of coupons.data ?? []) {
        if (Number(c.thresholdAmount) > price) continue;
        if (!best || Number(c.discountAmount) > Number(best.discountAmount)) best = c;
      }
      return best;
    },
    [coupons.data],
  );

  const grab = useCallback(
    async (activity: SeckillActivityView, skuId: string) => {
      if (!user) {
        push('请先登录再抢购', 'info');
        navigate('/login', { state: { from: '/seckill' } });
        return;
      }
      setPlacingSku(skuId);
      try {
        const address = await addressApi.defaultAddress();
        if (!address) {
          push('还没有收货地址，请先到「我的地址」添加', 'info');
          navigate('/addresses');
          return;
        }
        const result = await seckillApi.place({
          activityId: activity.id,
          skuId,
          addressId: address.id,
          idempotentKey: newIdempotentKey(),
          couponCode: bestCouponFor(Number(activity.skus.find((s) => s.skuId === skuId)?.seckillPrice ?? 0))?.code,
        });
        push(result.replay ? '你已抢到过该商品，已为你打开原订单' : '抢购成功，请尽快付款！', 'success');
        navigate(`/orders/${result.orderNo}`);
      } catch (err) {
        push(toFriendlyMessage(err), 'error');
        // 失败后刷新余量（可能已被抢完 / 触发限购）
        state.reload();
      } finally {
        setPlacingSku(null);
      }
    },
    [user, navigate, push, state, bestCouponFor],
  );

  if (state.loading) return <LoadingBlock />;
  if (state.error) return <Alert kind="error">{state.error}</Alert>;
  const activities = state.data ?? [];

  return (
    <div className="page">
      <div className="page-head">
        <h1>限时秒杀</h1>
        <p className="page-sub">手快有手慢无 · 余量以服务端实时判定为准</p>
        <button
          type="button"
          className="link-btn"
          onClick={() =>
            user ? navigate('/orders?type=SECKILL') : navigate('/login', { state: { from: '/seckill' } })
          }
        >
          我的抢购记录 <b>→</b>
        </button>
      </div>

      {activities.length === 0 ? (
        <Empty title="暂无秒杀活动" desc="暂时没有进行中的秒杀活动，过阵子再来看看～" />
      ) : (
        activities.map((act) => (
          <section key={act.id} className="card seckill-card">
            <header className="seckill-head">
              <h2>{act.title}</h2>
              <span className={phaseTone(act.phase)}>{phaseLabel(act.phase)}</span>
              <span className="muted">
                {formatDate(act.startAt)} ~ {formatDate(act.endAt)}
              </span>
            </header>
            {act.skus.length === 0 ? (
              <p className="muted">该活动暂无商品</p>
            ) : (
              <ul className="seckill-sku-list">
                {act.skus.map((sku) => {
                  const soldOut = sku.availableStock <= 0;
                  const disabled = act.phase !== 'ONGOING' || soldOut;
                  // 秒杀价与券叠加：展示"用券后再减多少"，下单时把券码一起带上
                  const coupon = bestCouponFor(Number(sku.seckillPrice));
                  return (
                    <li key={sku.skuId} className="seckill-sku">
                      {sku.image && <img src={skuImage(sku.skuId, sku.skuId, sku.image)} alt={sku.spuTitle} className="seckill-img" />}
                      <div className="seckill-info">
                        <Link to={`/product/${sku.skuId}`} className="seckill-title">
                          {sku.spuTitle}
                        </Link>
                        <span className="muted">
                          {sku.specText || '默认规格'} · 限购 {sku.perLimit} 件
                        </span>
                        <div className="seckill-price-row">
                          <span className="price seckill-price">{formatPrice(sku.seckillPrice)}</span>
                          <span className="muted seckill-original">
                            <s>{formatPrice(sku.originalPrice)}</s>
                          </span>
                          <span className={soldOut ? 'tag' : 'tag tag-success'}>
                            {soldOut ? '已抢完' : `仅剩 ${sku.availableStock} 件`}
                          </span>
                          {coupon && !soldOut ? (
                            <span className="tag tag-warn" title={coupon.name ?? '优惠券'}>
                              可用券 -{formatPrice(Number(coupon.discountAmount))}
                            </span>
                          ) : null}
                        </div>
                      </div>
                      <button
                        className="btn btn-accent"
                        disabled={disabled || placingSku === sku.skuId}
                        onClick={() => grab(act, sku.skuId)}
                      >
                        {placingSku === sku.skuId ? '抢购中…' : act.phase === 'UPCOMING' ? '未开始' : soldOut ? '已抢完' : '马上抢'}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        ))
      )}
    </div>
  );
};
