/**
 * 通用 UI 基件。
 *
 * 与 Node 原型版的差异：
 *  · 商品卡从统一的 GoodsCard 拆成 ProductCardView / ListingCardView 两种，
 *    因为 Java 后端返回的是两套结构（ProductCard 有 minPrice/maxPrice，
 *    ListingCard 有 conditionLevel/city/originalPrice），硬合并只会两头别扭
 *  · 星级、供货标签按实际字段渲染；不再有 adaptable / allergyWarning
 *    这类需要 AI 服务支撑的字段（duoduo-ai 未部署）
 *  · 订单状态步骤条改用 Java 的真实状态枚举
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { Link } from 'react-router-dom';
import { listingImage, productImage } from '@duoduo/api-sdk/images';
import { formatDate, formatPrice, formatServerTime } from '@duoduo/api-sdk/client';
import type { ListingCard, OrderStatus, ProductCard } from '@duoduo/api-sdk/types';
import { useSpuSeckill } from '../store/seckill';
import { SeckillPrice, SeckillTag } from './seckill';

export { formatDate, formatPrice, formatServerTime };

// ---------------- 基础件 ----------------

export const Spinner: React.FC<{ size?: 'sm' | 'lg' }> = ({ size = 'sm' }) => (
  <span className={`spinner${size === 'lg' ? ' spinner-lg' : ''}`} aria-label="加载中" />
);

export const LoadingBlock: React.FC<{ text?: string }> = ({ text = '加载中…' }) => (
  <div className="loading-block">
    <Spinner size="lg" />
    <span className="small">{text}</span>
  </div>
);

export const Empty: React.FC<{
  icon?: string;
  title: string;
  desc?: string;
  action?: React.ReactNode;
}> = ({ icon = '🐾', title, desc, action }) => (
  <div className="empty">
    <div className="empty-icon">{icon}</div>
    <div className="empty-title">{title}</div>
    {desc ? <div className="small">{desc}</div> : null}
    {action ? <div style={{ marginTop: 14 }}>{action}</div> : null}
  </div>
);

export const Field: React.FC<{
  label: string;
  required?: boolean;
  error?: string;
  hint?: string;
  children: React.ReactNode;
}> = ({ label, required, error, hint, children }) => (
  <div className="field">
    <label className="field-label">
      {label}
      {required ? <span className="req">*</span> : null}
    </label>
    {children}
    {error ? <div className="field-error">{error}</div> : null}
    {!error && hint ? <div className="field-hint">{hint}</div> : null}
  </div>
);

export const Alert: React.FC<{
  kind?: 'error' | 'warn' | 'info' | 'success';
  children: React.ReactNode;
}> = ({ kind = 'info', children }) => <div className={`alert alert-${kind}`}>{children}</div>;

/** 供货类型标签：self 自营 / merchant 品牌入驻 */
export const SupplyTag: React.FC<{ supplyType: string }> = ({ supplyType }) => {
  if (supplyType === 'merchant') return <span className="tag">品牌入驻</span>;
  return <span className="tag tag-self">多多自营</span>;
};

/**
 * 店铺名（第八十一轮）：优先显示商家自有店名（storeName，随卡片实时下发），
 * 没有时回退供货类型标签的文案。第三方店铺改名后这里立即显示新名 ——
 * 卡片不落店铺名副本，每次渲染都用接口返回的最新值。
 */
export const StoreNameText: React.FC<{ supplyType: string; storeName?: string | null }> = ({
  supplyType,
  storeName,
}) => {
  if (storeName) return <span className="small muted">{storeName}</span>;
  return <SupplyTag supplyType={supplyType} />;
};

export const Stars: React.FC<{ value: number; showValue?: boolean }> = ({ value, showValue }) => {
  const rounded = Math.max(0, Math.min(5, Math.round(value)));
  return (
    <span className="nowrap" style={{ color: '#f5a524' }}>
      {'★'.repeat(rounded)}
      <span style={{ color: 'var(--border-strong)' }}>{'★'.repeat(5 - rounded)}</span>
      {showValue ? (
        <span className="small muted" style={{ marginLeft: 4 }}>
          {value.toFixed(1)}
        </span>
      ) : null}
    </span>
  );
};

/** 售价区间文案：minPrice === maxPrice 时只显示一个价 */
export function priceRangeText(minPrice: number, maxPrice: number): string {
  const text = formatPrice(minPrice);
  if (maxPrice > minPrice) return `${text} 起`;
  return text;
}

/** 手机号格式：与后端 RegisterRequest 的校验口径保持一致 */
export const PHONE_PATTERN = /^1[3-9]\d{9}$/;

// ---------------- Toast ----------------

type ToastKind = 'info' | 'success' | 'error';

interface ToastItem {
  id: number;
  kind: ToastKind;
  text: string;
}

const ToastContext = createContext<{ push: (text: string, kind?: ToastKind) => void }>({
  push: () => undefined,
});

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [items, setItems] = useState<ToastItem[]>([]);

  const push = useCallback((text: string, kind: ToastKind = 'info') => {
    const id = Date.now() + Math.random();
    setItems((prev) => [...prev.slice(-2), { id, kind, text }]);
    setTimeout(() => setItems((prev) => prev.filter((t) => t.id !== id)), 3200);
  }, []);

  const value = useMemo(() => ({ push }), [push]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-host">
        {items.map((item) => (
          <div key={item.id} className={`toast toast-${item.kind}`}>
            {item.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};

export const useToast = () => useContext(ToastContext);

// ---------------- Modal ----------------

export const Modal: React.FC<{
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
  /** 宽形态（860px）：字段多的表单用，避免 520px 把输入框挤成锯齿 */
  wide?: boolean;
}> = ({ open, title, onClose, children, footer, wide }) => {
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="modal-mask" onClick={onClose} role="presentation">
      <div
        className={`modal${wide ? ' modal-wide' : ''}`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="modal-head">
          <h3 className="modal-title">{title}</h3>
          <button className="icon-btn" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer ? <div className="modal-foot">{footer}</div> : null}
      </div>
    </div>
  );
};

// ---------------- 商品卡（SPU） ----------------

/**
 * 全站商品卡。
 *
 * <p>秒杀信息来自全局 `SeckillProvider`（按 SPU 建索引）：参与秒杀的商品
 * 自动挂「秒杀」标签，价格换成「秒杀价 + 划线原价」。
 * 这里**不做任何本地推算** —— 首页、搜索、推荐、物种精选必须显示同一个价，
 * 否则用户会认为秒杀是假的。
 */
export const ProductCardView: React.FC<{ item: ProductCard }> = ({ item }) => {
  const seckill = useSpuSeckill(item.id);
  return (
    <Link className="goods-card" to={`/product/${item.id}`}>
      <div className="goods-media">
        {item.mainImage ? <img src={productImage(item.id, item.mainImage)} alt={item.title} loading="lazy" decoding="async" /> : null}
        <div className="goods-media-tags">
          {seckill ? <SeckillTag phase={seckill.phase} /> : null}
          <SupplyTag supplyType={item.supplyType} />
        </div>
      </div>
      <div className="goods-body">
        <div className="goods-title">{item.title}</div>
        <div className="goods-meta">
          <StoreNameText supplyType={item.supplyType} storeName={item.storeName} />
          {item.categoryName ? <span>{item.categoryName}</span> : null}
        </div>
        {item.ratingCount > 0 ? (
          <div className="goods-meta">
            <Stars value={item.rating} showValue />
            <span>{item.ratingCount} 条评价</span>
          </div>
        ) : item.salesCount > 0 ? (
          <div className="goods-meta">已售 {item.salesCount}</div>
        ) : null}
        <div className="goods-footer">
          {seckill ? (
            <SeckillPrice
              price={seckill.sku.seckillPrice}
              original={seckill.sku.originalPrice}
              size="md"
              from={seckill.multiPrice}
            />
          ) : (
            <span className="goods-price">
              {item.minPrice === item.maxPrice ? (
                <>
                  <small>¥</small>
                  {(item.minPrice / 100).toFixed(2)}
                </>
              ) : (
                <>
                  <small>¥</small>
                  {(item.minPrice / 100).toFixed(2)}
                  <span className="small muted"> 起</span>
                </>
              )}
            </span>
          )}
          <span className="small muted goods-sales" style={{ marginLeft: 'auto' }}>
            {item.inStock ? `已售 ${item.salesCount}` : '已售罄'}
          </span>
        </div>
      </div>
    </Link>
  );
};

/**
 * 商品网格（自营）。
 *
 * `stagger` 让卡片依次错峰淡入（前 8 张每张差 28ms，第 9 张起统一封顶 224ms）——
 * 一次性刷出整屏卡片时，眼睛需要一个落点；齐刷刷一起亮反而像"闪了一下"。
 */
export const ProductGrid: React.FC<{ items: ProductCard[] }> = ({ items }) => (
  <div className="grid grid-goods stagger">
    {items.map((item) => (
      <ProductCardView key={item.id} item={item} />
    ))}
  </div>
);

// ---------------- 商品卡（C2C 闲置） ----------------

export const ListingCardView: React.FC<{ item: ListingCard; conditionLabel?: string }> = ({
  item,
  conditionLabel,
}) => (
  <Link className="goods-card" to={`/listing/${item.id}`}>
    <div className="goods-media">
      {/* 与小程序一致：任何闲置都有图 —— cover 为空时按 id 稳定取池图，不出现「无图灰块」 */}
      <img src={listingImage(item.id, item.cover)} alt={item.title} loading="lazy" decoding="async" />
      <div className="goods-media-tags">
        <span className="tag tag-second">个人闲置</span>
      </div>
    </div>
    <div className="goods-body">
      <div className="goods-title">{item.title}</div>
      <div className="goods-meta">
        {conditionLabel ?? item.conditionLevel ? (
          <span>{conditionLabel ?? conditionLabelOf(item.conditionLevel)}</span>
        ) : null}
        {item.city ? <span>{item.city}</span> : null}
        {item.breed ? <span>{item.breed}</span> : null}
      </div>
      <div className="goods-footer">
        <span className="goods-price">
          <small>¥</small>
          {(item.price / 100).toFixed(2)}
        </span>
        {item.originalPrice > item.price ? (
          <span className="small muted" style={{ textDecoration: 'line-through' }}>
            {formatPrice(item.originalPrice)}
          </span>
        ) : null}
        <span className="small muted" style={{ marginLeft: 'auto' }}>
          {item.viewCount} 次浏览
        </span>
      </div>
    </div>
  </Link>
);

/** 成色兜底文案：与 /api/meta/dictionaries 的 conditionLevel 保持一致 */
export const CONDITION_LABELS: Record<string, string> = {
  NEW: '全新未拆',
  LIKE_NEW: '几乎全新',
  GOOD: '成色良好',
  FAIR: '有明显使用痕迹',
};

export function conditionLabelOf(code: string | null | undefined): string {
  if (!code) return '';
  return CONDITION_LABELS[code] ?? code;
}

export const ListingGrid: React.FC<{ items: ListingCard[] }> = ({ items }) => (
  <div className="grid grid-goods stagger">
    {items.map((item) => (
      <ListingCardView key={item.id} item={item} />
    ))}
  </div>
);

// ---------------- 分页 ----------------

/**
 * 页码窗口：始终含首页、末页与当前页左右各 2 个，空隙用 `'…'` 占位。
 *
 * <p>为什么不是「全部页码平铺」：后台有列表能到几百页，平铺会把工具栏撑爆，
 * 也会让「当前页在哪」更难看清。为什么不是「只显示上一页/下一页」：
 * 用户明确要求能**直接跳到某一页**，翻十几次才能到第 12 页是不可接受的。</p>
 */
export function pageWindow(page: number, pageCount: number, span = 2): Array<number | 'gap'> {
  const total = Math.max(1, pageCount);
  const current = Math.min(Math.max(1, page), total);
  const from = Math.max(2, current - span);
  const to = Math.min(total - 1, current + span);
  const out: Array<number | 'gap'> = [1];
  if (from > 2) out.push('gap');
  for (let i = from; i <= to; i += 1) out.push(i);
  if (to < total - 1) out.push('gap');
  if (total > 1) out.push(total);
  // 页数很少时（≤7）窗口已覆盖全量，去掉多余的 gap
  return out.filter((v, i, arr) => !(v === 'gap' && arr[i - 1] === 'gap'));
}

/**
 * 分页条：**上一页 / 数字页码 / 下一页**。
 *
 * <p>三端共用同一个组件（用户端、商家端、平台后台）。此前只有「第 x / y 页」
 * 加前后翻页，页数一多就没法直接跳转；各端又各自复制了一份样式，
 * 交互细节（是否禁用、是否显示总数）不一致。现在统一在这里，页面只传 4 个参数。</p>
 */
export const Pagination: React.FC<{
  page: number;
  pageCount: number;
  total: number;
  onChange: (page: number) => void;
  /** 列表很短时也显示「共 N 条」，默认 true */
  showTotalWhenSingle?: boolean;
}> = ({ page, pageCount, total, onChange, showTotalWhenSingle = true }) => {
  const count = Math.max(1, pageCount);
  const current = Math.min(Math.max(1, page), count);
  if (count <= 1 && !showTotalWhenSingle) return null;
  const items = pageWindow(current, count);
  return (
    <nav className="pagination" aria-label="分页">
      <button
        type="button"
        className="page-btn page-btn-arrow"
        disabled={current <= 1}
        onClick={() => onChange(current - 1)}
        aria-label="上一页"
      >
        上一页
      </button>
      {count > 1
        ? items.map((item, i) =>
            item === 'gap' ? (
              <span key={`gap-${i}`} className="page-gap" aria-hidden="true">…</span>
            ) : (
              <button
                key={item}
                type="button"
                className={`page-btn${item === current ? ' is-active' : ''}`}
                aria-current={item === current ? 'page' : undefined}
                onClick={() => onChange(item)}
              >
                {item}
              </button>
            ),
          )
        : null}
      <button
        type="button"
        className="page-btn page-btn-arrow"
        disabled={current >= count}
        onClick={() => onChange(current + 1)}
        aria-label="下一页"
      >
        下一页
      </button>
      <span className="page-total">
        共 {total} 条 · 第 {current}/{count} 页
      </span>
    </nav>
  );
};

/**
 * 列表搜索框（三端统一交互）。
 *
 * <p>输入即搜（页面配合 {@code useDebounced}），Enter 立即生效，右侧 × 一键清空。
 * 不做「必须点搜索按钮」：列表页的搜索是持续性的筛选，多一次点击只会让人以为没生效。</p>
 */
export const SearchBar: React.FC<{
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  /** 请求进行中：只做轻提示，不禁用输入（否则打字会被吞） */
  busy?: boolean;
  width?: number;
}> = ({ value, onChange, placeholder = '搜索', busy = false, width = 260 }) => (
  <div className="search-bar" style={{ width }}>
    <span className="search-bar-icon" aria-hidden="true">⌕</span>
    <input
      className="search-bar-input"
      value={value}
      placeholder={placeholder}
      aria-label={placeholder}
      onChange={(e) => onChange(e.target.value)}
    />
    {busy ? <span className="spinner" aria-label="搜索中" /> : null}
    {value ? (
      <button type="button" className="search-bar-clear" aria-label="清空搜索" onClick={() => onChange('')}>
        ×
      </button>
    ) : null}
  </div>
);

// ---------------- 订单状态步骤条 ----------------

/** Java 后端的订单主链路状态，顺序即流转顺序 */
const ORDER_FLOW: { status: OrderStatus; label: string }[] = [
  { status: 'PENDING_PAYMENT', label: '待支付' },
  { status: 'PAID', label: '待发货' },
  { status: 'SHIPPED', label: '待收货' },
  { status: 'COMPLETED', label: '已完成' },
];

export const StatusSteps: React.FC<{ status: OrderStatus }> = ({ status }) => {
  const aborted = status === 'CLOSED' || status === 'REFUNDED';
  const index = ORDER_FLOW.findIndex((s) => s.status === status);

  return (
    <div className="steps">
      {ORDER_FLOW.map((step, i) => {
        // 终止态（已完成）允许点亮到最后一步；中途终止则只点亮已走过的部分
        const done = aborted
          ? false
          : index >= 0
            ? i <= index
            : status === 'COMPLETED';
        return (
          <React.Fragment key={step.status}>
            {i > 0 ? <span className="step-line" /> : null}
            <span className={`step${done ? ' done' : ''}`}>
              <span className="step-dot">{done ? '✓' : i + 1}</span>
              {step.label}
            </span>
          </React.Fragment>
        );
      })}
      {aborted ? (
        <>
          <span className="step-line" />
          <span className="step done">
            <span className="step-dot">{status === 'REFUNDED' ? '↩' : '✕'}</span>
            {status === 'REFUNDED' ? '已退款' : '已关闭'}
          </span>
        </>
      ) : null}
    </div>
  );
};

// ---------------- 统计条 ----------------
//
// 原先这里有一个手写的 `MiniBars`（纯 div + 百分比高度）。第六十六轮已由
// `components/charts.tsx` 的 ECharts 版本取代，原实现与配套的 `.bars` 系列样式一并删除。
// 删掉而不是留着，理由有两条：
//   ① 它的悬停提示用的是 HTML 原生 `title`，延迟由操作系统决定（1–2 秒）且不可配置 ——
//      留着就是留一个「下次有人再踩一遍」的坑；
//   ② 它的百分比高度依赖一个高度确定的父元素，改样式时极易再次退化成「柱子全平」
//      （第六十五轮刚修过一次）。同一件事不维护两套实现。

