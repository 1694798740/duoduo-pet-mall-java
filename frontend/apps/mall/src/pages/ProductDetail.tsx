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
import { detailImages, gallery } from '@duoduo/api-sdk/images';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { newIdempotentKey, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { addressApi, cartApi, productApi, reviewApi, seckillApi } from '@duoduo/api-sdk/endpoints';
import type { ProductDetail, ReviewSummary } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { CreditBadge } from '@duoduo/shared/components/CreditBadge';
import { ReviewSection } from '@duoduo/shared/components/ReviewSection';
import { SeckillCountdown, SeckillPrice, SeckillProgress, SeckillTag } from '@duoduo/shared/components/seckill';
import { useSpuSeckill } from '@duoduo/shared/store/seckill';
import {
  Alert,
  LoadingBlock,
  Modal,
  ProductGrid,
  Stars,
  SupplyTag,
  useToast,
} from '@duoduo/shared/components/ui';
import { useApp } from '@duoduo/shared/store/app';
import { useAuth } from '@duoduo/shared/store/auth';
import { notifyDataChanged, subscribeDataChanged } from '@duoduo/shared/lib/dataBus';
import { SupportEntry } from '../components/SupportEntry';

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
  /** 主图轮播的当前下标 + 放大预览开关（第七十三轮：对齐小程序端的「点击看大图」交互） */
  const [imgIndex, setImgIndex] = useState(0);
  /** 用户本人的秒杀限额是否已用完（抢购被「限购」拦下时置位 → 退回原价购买路径） */
  const [quotaReached, setQuotaReached] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  /** 秒杀下单进行中（与加购的 adding 分开：两个动作的禁用条件不同） */
  const [grabbing, setGrabbing] = useState(false);

  /**
   * 该 SPU 的秒杀信息（全站唯一数据源）。
   *
   * ⚠️ 价格展示与下单链路必须同源：这里显示了秒杀价，购买按钮就必须走**秒杀下单**，
   * 否则用户看到 ¥1 却按 ¥129 成交 —— 这是比不展示更严重的问题。
   */
  const seckill = useSpuSeckill(spuId);

  const fetcher = useCallback(() => productApi.detail(spuId), [spuId]);
  const { data, loading, error, reload } = useAsync<ProductDetail>(fetcher, hasSpuId, [spuId]);

  // 全站数据变更广播（2026-10-19）：别处发生写操作（下单/取消/支付）影响本页的
  // 库存与可售状态时立刻重拉 —— 不再等用户离开再回来或手动刷新。
  useEffect(() => {
    if (!hasSpuId) return undefined;
    return subscribeDataChanged(() => reload());
  }, [hasSpuId, reload]);

  // 头部评分改由评价域实时聚合驱动（第七十三轮）：t_spu.rating 原是种子假数据，
  // 与「商品评价」区的真实列表互相矛盾（头部 4.7 分、评价区「暂无评价」）。
  // 与 ReviewSection 各自请求一次 summary —— 接口是轻量聚合查询，重复一次换取组件独立。
  const summaryState = useAsync<ReviewSummary>(
    useCallback(() => reviewApi.summary(spuId), [spuId]),
    hasSpuId,
    [spuId],
  );

  const skus = data?.skus ?? [];

  // 默认选中第一个可售 SKU；若全部售罄则选第一个，用于展示价格。
  // 秒杀规格优先：用户从「每日秒杀」或商品卡的秒杀价进来，打开就该看到并选中那个规格，
  // 而不是先展示原价、让用户自己找哪个规格在秒杀。
  useEffect(() => {
    if (!skus.length) return;
    setSkuId((prev) => {
      if (prev !== null && skus.some((s) => s.id === prev && s.sellable)) return prev;
      if (seckill && skus.some((s) => s.id === seckill.skuId)) return seckill.skuId;
      const firstSellable = skus.find((s) => s.sellable);
      return (firstSellable ?? skus[0] ?? null)?.id ?? null;
    });
  }, [skus, seckill]);

  const selectedSku = useMemo(
    () => skus.find((s) => s.id === skuId) ?? null,
    [skus, skuId],
  );

  // 数量上限随 SKU 切换而收敛，避免切到低库存规格后还留着超量数字
  useEffect(() => {
    if (selectedSku) setQuantity((q) => Math.min(Math.max(1, q), Math.max(1, selectedSku.stock)));
  }, [selectedSku]);

  // 切商品时把轮播归位（选中规格由上方 effect 按新 SKU 列表自动收敛）
  useEffect(() => {
    setImgIndex(0);
  }, [spuId]);

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
  // 图集交给统一解析：后端的 /img/… 占位路径映射到本地图池（主图+4 变体），
  // 真实存在的（将来 CDN 下发的绝对 URL / 用户上传的 /uploads/）原样放行
  const images = data.images.some((u) => /^https?:|^\/uploads\//i.test(u))
    ? data.images
    : gallery(card.id, card.mainImage);
  const canBuy = selectedSku?.sellable === true;
  const price = selectedSku?.price ?? card.minPrice;

  /**
   * 当前选中规格是否正在参与秒杀。
   *
   * 只有「选中了秒杀规格」才换价：SPU 有秒杀但用户在看别的规格时，
   * 顶部价格必须是该规格的真实售价，否则会变成虚假宣传。
   */
  const skuSeckill = selectedSku ? seckill?.bySku[selectedSku.id] ?? null : null;
  const liveSeckill =
    seckill && skuSeckill && seckill.phase !== 'ENDED'
      ? { meta: seckill, sku: skuSeckill }
      : null;
  /**
   * 秒杀**售罄 / 限额用尽**后必须退回原价购买路径（2026-10-19）：
   * 秒杀的「货」是活动配额，与 SKU 库存是两套口径 —— 配额抢完不代表商品卖完了。
   * 此前配额一抢完，详情页只剩一个灰的「已抢完」按钮，原价加购/立即购买整个消失，
   * 剩下的库存只能干看着。现改为：秒杀条保留（如实显示已抢完），
   * 价格与购买按钮退回原价口径；用户抢购时被「限购」拦下也同等处理。
   */
  const seckillOver = Boolean(
    liveSeckill && (liveSeckill.sku.availableStock <= 0 || quotaReached),
  );
  /** 价格与购买按钮看到的秒杀：售罄/限购后视为没有秒杀，回到原价口径 */
  const buySeckill = seckillOver ? null : liveSeckill;
  /** SPU 有秒杀、但当前选中的不是秒杀规格时的「引回」提示 */
  const otherSeckill =
    seckill && !liveSeckill && seckill.phase !== 'ENDED' ? seckill : null;

  const grabSeckill = async () => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(`/product/${spuId}`)}`);
      return;
    }
    if (!liveSeckill) return;
    setGrabbing(true);
    try {
      const address = await addressApi.defaultAddress();
      if (!address) {
        push('还没有收货地址，请先到「我的地址」添加', 'info');
        navigate('/addresses');
        return;
      }
      const result = await seckillApi.place({
        activityId: liveSeckill.meta.activityId,
        skuId: liveSeckill.sku.skuId,
        addressId: address.id,
        idempotentKey: newIdempotentKey(),
      });
      push(
        result.replay ? '你已抢到过该商品，已为你打开原订单' : '抢购成功，请尽快付款！',
        'success',
      );
      // 余量变了：广播一次，首页/列表/其它详情实例立即重拉，不用等 60s 轮询
      notifyDataChanged();
      navigate(`/orders/${result.orderNo}`);
    } catch (err) {
      const msg = toFriendlyMessage(err);
      if (/限购/.test(msg)) setQuotaReached(true);
      push(msg, 'error');
    } finally {
      setGrabbing(false);
    }
  };

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
            {images[imgIndex] ? (
              <img
                src={images[imgIndex]}
                alt={card.title}
                style={{ cursor: 'zoom-in' }}
                onClick={() => setPreviewing(true)}
              />
            ) : null}
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
                    border:
                      index === imgIndex ? '2px solid var(--brand)' : '1px solid var(--border)',
                    cursor: 'pointer',
                  }}
                  onClick={() => setImgIndex(index)}
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
              {/* 图文配图（第七十三轮）：与小程序端同一映射池（base=seed+2 取 3 张），
                  两端「商品介绍」从此是同一批图；后端将来下发真实图时原样放行 */}
              {detailImages(card.id).map((img, index) => (
                <img
                  key={`${img}-${index}`}
                  src={img}
                  alt={`${card.title} 介绍图 ${index + 1}`}
                  style={{
                    width: '100%',
                    borderRadius: 12,
                    marginTop: index === 0 ? 12 : 10,
                    display: 'block',
                  }}
                />
              ))}
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

          {/* 包装规格（第七十三轮）：与小程序端逐行同构 —— spec 键值对 + SKU 编码 + 当前库存。
              之前只有小程序有这一块，两端详情页信息不对称 */}
          {selectedSku ? (
            <div className="card card-pad" style={{ marginTop: 16 }}>
              <h3 style={{ marginBottom: 10 }}>包装规格</h3>
              <dl className="spec-list">
                {Object.keys(selectedSku.spec).length ? (
                  Object.entries(selectedSku.spec)
                    .filter(([, v]) => v)
                    .map(([k, v]) => (
                      <React.Fragment key={k}>
                        <dt>{k}</dt>
                        <dd>{v}</dd>
                      </React.Fragment>
                    ))
                ) : (
                  <>
                    <dt>规格</dt>
                    <dd>默认规格</dd>
                  </>
                )}
                {selectedSku.skuCode ? (
                  <>
                    <dt>SKU 编码</dt>
                    <dd>{selectedSku.skuCode}</dd>
                  </>
                ) : null}
                <dt>当前库存</dt>
                <dd>{selectedSku.stock > 0 ? `${selectedSku.stock} 件` : '补货中'}</dd>
              </dl>
            </div>
          ) : null}
        </div>

        <div>
          <div className="pd-price-block">
            <div className="row-between row-wrap">
              <span className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                <SupplyTag supplyType={card.supplyType} />
                {/* 信用分是「卖家」的可信度：只对入驻商品展示（第七十三轮起自营商品 sellerId=0，
                    平台自营没有单一卖家，不再挂官方客服账号的信用徽章） */}
                {card.supplyType !== 'self' && card.sellerId != null && card.sellerId !== '' ? (
                  <CreditBadge sellerId={card.sellerId} label="卖家信用" />
                ) : null}
              </span>
              <span className="small muted">
                已售 {card.salesCount} ·{' '}
                {selectedSku ? `可售 ${selectedSku.stock}` : card.inStock ? '有货' : '已售罄'}
              </span>
            </div>
            <h1 style={{ fontSize: 20, margin: '10px 0 6px' }}>{card.title}</h1>
            {card.subtitle ? <div className="muted-2 small">{card.subtitle}</div> : null}

            {/* 秒杀条：标签 + 倒计时 + 余量进度。三样都在价格上方，
                让「现在多便宜、还有多久、还剩几件」在一屏内可读。
                配额抢完也保留（如实显示已抢完），但购买退回原价口径 —— 见 seckillOver。 */}
            {liveSeckill ? (
              <div className="pd-seckill-strip">
                <SeckillTag phase={liveSeckill.meta.phase} />
                <SeckillCountdown
                  startAt={liveSeckill.meta.startAt}
                  endAt={liveSeckill.meta.endAt}
                  phase={liveSeckill.meta.phase}
                />
                <SeckillProgress
                  available={liveSeckill.sku.availableStock}
                  total={liveSeckill.sku.totalStock}
                />
                {seckillOver ? (
                  <div className="small" style={{ marginTop: 4 }}>
                    本场秒杀已抢完{quotaReached ? '（你的限购额度已用完）' : ''}，
                    可按原价正常购买 ↓
                  </div>
                ) : null}
              </div>
            ) : null}

            <div className="row" style={{ marginTop: 12, alignItems: 'baseline' }}>
              {buySeckill ? (
                <SeckillPrice
                  price={buySeckill.sku.seckillPrice}
                  original={buySeckill.sku.originalPrice}
                  size="lg"
                />
              ) : (
                <span style={{ fontSize: 30, fontWeight: 700, color: 'var(--danger)' }}>
                  ¥{(price / 100).toFixed(2)}
                </span>
              )}
              {!buySeckill && card.maxPrice > card.minPrice ? (
                <span className="small muted">
                  规格不同，最高 ¥{(card.maxPrice / 100).toFixed(2)}
                </span>
              ) : null}
              {!buySeckill && selectedSku && selectedSku.marketPrice > selectedSku.price ? (
                <span className="small muted" style={{ textDecoration: 'line-through' }}>
                  ¥{(selectedSku.marketPrice / 100).toFixed(2)}
                </span>
              ) : null}
              <span style={{ marginLeft: 'auto' }}>
                {/* 评分与评价区同源（评价域实时聚合）：没有真实评价就如实显示「暂无评分」，
                    不再展示种子假分数 —— 与下方「商品评价」列表永远一致 */}
                {summaryState.data && summaryState.data.total > 0 ? (
                  <>
                    <Stars value={Number(summaryState.data.avgRating) || 0} showValue />
                    <span className="small muted" style={{ marginLeft: 6 }}>
                      {summaryState.data.total} 条评价
                    </span>
                  </>
                ) : (
                  <span className="small muted">暂无评分</span>
                )}
              </span>
            </div>

            {/* 选中的不是秒杀规格：明确告诉用户「秒杀的是哪个价」，一键切回去 */}
            {otherSeckill ? (
              <div className="pd-seckill-hint">
                另有规格参与秒杀，到手价
                <b> ¥{(otherSeckill.sku.seckillPrice / 100).toFixed(2)}</b>
                <button
                  type="button"
                  className="link-btn"
                  style={{ marginLeft: 6, color: 'var(--brand-dark)' }}
                  onClick={() => setSkuId(otherSeckill.skuId)}
                >
                  切到秒杀规格 →
                </button>
              </div>
            ) : null}
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
                const skuSk = seckill?.bySku[sku.id];
                /* 该规格的秒杀**还有货**才算「秒杀规格」：配额抢完后按原价展示，
                   否则规格按钮挂着「秒杀 ¥10」却只能按 ¥158 买 —— 自相矛盾 */
                const isSeckillSku = Boolean(
                  skuSk && seckill?.phase !== 'ENDED' && skuSk.availableStock > 0,
                );
                return (
                  <button
                    key={sku.id}
                    type="button"
                    className={`sku-option${sku.id === skuId ? ' active' : ''}`}
                    /* 秒杀规格不按商品库存禁用：秒杀的「货」是活动配额，与 SKU 库存是两套口径 */
                    disabled={!sku.sellable && !isSeckillSku}
                    onClick={() => setSkuId(sku.id)}
                  >
                    {specText}
                    <span
                      className="small"
                      style={{ marginLeft: 6, color: isSeckillSku ? 'var(--danger)' : undefined }}
                    >
                      ¥{((isSeckillSku && skuSk ? skuSk.seckillPrice : sku.price) / 100).toFixed(2)}
                    </span>
                    {isSeckillSku ? (
                      <span className="sk-tag sk-tag-live sk-tag-mini">秒杀</span>
                    ) : null}
                    {!sku.sellable && !isSeckillSku ? (
                      <span className="small muted"> · 售罄</span>
                    ) : null}
                  </button>
                );
              })}
            </div>

            {/* 秒杀按件抢购（下单接口不收数量），不展示数量选择器 —— 让用户以为能买 3 件
                却在提交时只成交 1 件，比直接不显示更糟。秒杀抢完后回到原价口径，数量选择恢复。 */}
            {buySeckill ? null : (
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
            )}

            <div className="row row-wrap" style={{ marginTop: 18 }}>
              {buySeckill ? (
                <>
                  <button
                    className="btn btn-pay"
                    disabled={
                      buySeckill.meta.phase !== 'ONGOING' ||
                      buySeckill.sku.availableStock <= 0 ||
                      grabbing
                    }
                    onClick={() => void grabSeckill()}
                  >
                    {buySeckill.meta.phase === 'UPCOMING'
                      ? '即将开抢'
                      : buySeckill.sku.availableStock <= 0
                        ? '已抢完'
                        : grabbing
                          ? '抢购中…'
                          : '马上抢'}
                  </button>
                  <span className="small muted">
                    秒杀商品按件抢购，不支持加入购物车
                    {buySeckill.sku.perLimit ? ` · 每人限购 ${buySeckill.sku.perLimit} 件` : ''}
                  </span>
                </>
              ) : (
                <>
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
                </>
              )}
              {/* 统一入口：位置固定放在购买动作右侧，售罄时同样可用（买不到也需要问） */}
              <SupportEntry />
            </div>

            {!canBuy && !buySeckill ? (
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

      {/* 主图放大预览（第七十三轮）：点击主图打开，Esc / 点遮罩 / 关闭键退出，支持左右切换 */}
      <Modal open={previewing} title={card.title} onClose={() => setPreviewing(false)}>
        <div style={{ textAlign: 'center' }}>
          {images[imgIndex] ? (
            <img
              src={images[imgIndex]}
              alt={`${card.title} 放大图`}
              style={{ maxWidth: '100%', maxHeight: '70vh', borderRadius: 12 }}
            />
          ) : null}
          {images.length > 1 ? (
            <div className="row" style={{ marginTop: 12, justifyContent: 'center' }}>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setImgIndex((i) => (i - 1 + images.length) % images.length)}
              >
                ← 上一张
              </button>
              <span className="small muted">
                {imgIndex + 1} / {images.length}
              </span>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setImgIndex((i) => (i + 1) % images.length)}
              >
                下一张 →
              </button>
            </div>
          ) : null}
        </div>
      </Modal>
    </div>
  );
};
