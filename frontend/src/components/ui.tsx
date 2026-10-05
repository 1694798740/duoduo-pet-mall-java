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
import { formatDate, formatPrice } from '../api/client';
import type { ListingCard, OrderStatus, ProductCard } from '../api/types';

export { formatDate, formatPrice };

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
}> = ({ open, title, onClose, children, footer }) => {
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
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
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

export const ProductCardView: React.FC<{ item: ProductCard }> = ({ item }) => (
  <Link className="goods-card" to={`/product/${item.id}`}>
    <div className="goods-media">
      {item.mainImage ? <img src={item.mainImage} alt={item.title} loading="lazy" /> : null}
      <div className="goods-media-tags">
        <SupplyTag supplyType={item.supplyType} />
      </div>
    </div>
    <div className="goods-body">
      <div className="goods-title">{item.title}</div>
      <div className="goods-meta">
        {item.brandName ? <span>{item.brandName}</span> : null}
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
        <span className="small muted" style={{ marginLeft: 'auto' }}>
          {item.inStock ? `已售 ${item.salesCount}` : '已售罄'}
        </span>
      </div>
    </div>
  </Link>
);

export const ProductGrid: React.FC<{ items: ProductCard[] }> = ({ items }) => (
  <div className="grid grid-goods">
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
      {item.cover ? <img src={item.cover} alt={item.title} loading="lazy" /> : null}
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
  <div className="grid grid-goods">
    {items.map((item) => (
      <ListingCardView key={item.id} item={item} />
    ))}
  </div>
);

// ---------------- 分页 ----------------

export const Pagination: React.FC<{
  page: number;
  pageCount: number;
  total: number;
  onChange: (page: number) => void;
}> = ({ page, pageCount, total, onChange }) => {
  if (pageCount <= 1) {
    return (
      <div className="small muted" style={{ textAlign: 'center', marginTop: 16 }}>
        共 {total} 条
      </div>
    );
  }
  return (
    <div className="pagination">
      <button className="btn btn-ghost btn-sm" disabled={page <= 1} onClick={() => onChange(page - 1)}>
        上一页
      </button>
      <span className="small muted">
        第 {page} / {pageCount} 页 · 共 {total} 条
      </span>
      <button
        className="btn btn-ghost btn-sm"
        disabled={page >= pageCount}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </button>
    </div>
  );
};

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

