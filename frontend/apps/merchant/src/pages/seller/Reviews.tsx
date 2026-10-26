/**
 * 商家端 · 评价管理（A-16）。
 *
 * 数据源：
 *   GET  /api/reviews/seller           —— 我店铺的评价（可见范围由登录态推导，传不了 sellerId）
 *   POST /api/reviews/{id}/reply       —— 回复评价（每条一次，回复后不可修改）
 *
 * 三条口径：
 *  ① 只展示**审核通过**的评价：待审 / 已下架内容不公开，也不该出现在商家工作台；
 *  ② 匿名评价由服务端脱敏为「匿名用户」，且列表里没有 userId / avatar —— 商家无法反查身份；
 *  ③ 回复是公开内容，会过与买家评价同一套机审（命中风险直接 422 退回，不进人工队列）。
 */

import React, { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { reviewApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { SellerReviewView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, Stars, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { SellerDenied, canAccessSellerConsole } from './shared';

const REPLY_TABS: Array<{ value: '' | 'false' | 'true'; label: string }> = [
  { value: 'false', label: '待回复' },
  { value: 'true', label: '已回复' },
  { value: '', label: '全部' },
];

const MAX_REPLY = 500;

export const SellerReviewsPage: React.FC = () => {
  const { user } = useAuth();
  const allowed = canAccessSellerConsole(user?.role);
  const { push } = useToast();

  const [tab, setTab] = useState<'' | 'false' | 'true'>('false');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [replying, setReplying] = useState<SellerReviewView | null>(null);
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => reviewApi.sellerList({
        replied: tab === '' ? undefined : tab === 'true',
        keyword: keyword || undefined,
        page,
        pageSize: 10,
      }),
      [tab, keyword, page],
    ),
    allowed,
    [tab, keyword, page],
  );

  if (!allowed) {
    return (
      <div>
        <h2 className="section-title">商家端 · 评价管理</h2>
        <SellerDenied reason="评价回复仅对店主开放（店员可查看商品与订单，但不能以店铺名义公开回复）" />
      </div>
    );
  }

  const openReply = (item: SellerReviewView) => {
    setReplying(item);
    setContent('');
    setActionError(null);
  };

  const submitReply = async () => {
    if (!replying) return;
    const text = content.trim();
    if (!text) {
      // 后端同样会拒（400）—— 这里先拦一次是为了省一个来回，不是安全边界
      setActionError('回复内容不能为空');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await reviewApi.sellerReply(replying.id, text);
      push('回复已发布，买家与访客都能在商品评价区看到', 'success');
      setReplying(null);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="row-between" style={{ marginBottom: 12 }}>
        <h2 className="section-title">商家端 · 评价管理</h2>
        <Link className="btn btn-sm btn-ghost" to="/seller/orders">
          我的订单
        </Link>
      </div>

      <Alert kind="info">
        评价展示的是商品口碑，<b>回复会公开显示在商品评价区</b>。每条评价只能回复一次、回复后不可修改，请确认措辞后再提交。
        匿名评价不会向商家暴露买家身份。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {REPLY_TABS.map((t) => (
          <button
            key={t.label}
            className={`btn btn-sm ${tab === t.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setTab(t.value);
              setPage(1);
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜评价内容、商品名或订单号"
          width={320}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的评价 {data ? data.total : 0} 条</span>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载评价…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty
          icon="💬"
          title={tab === 'false' ? '没有待回复的评价' : tab === 'true' ? '还没有回复过评价' : '还没有评价'}
        />
      ) : null}

      {!loading && data
        ? data.items.map((rv) => (
            <div key={rv.id} className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                <Stars value={rv.rating} />
                <span className="small">{rv.nickname}</span>
                {rv.anonymous ? <span className="tag">匿名</span> : null}
                <span className="small muted">{formatDate(rv.createdAt)}</span>
                <span className="small muted">订单 {rv.orderNo}</span>
              </div>

              <div className="row" style={{ gap: 10, marginTop: 8, alignItems: 'center' }}>
                {rv.productImage ? (
                  <img
                    src={rv.productImage}
                    alt=""
                    style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 6 }}
                    loading="lazy"
                    decoding="async"
                  />
                ) : null}
                <Link className="small" to={`/seller/product-insights?spuId=${rv.spuId}`} style={{ minWidth: 0 }}>
                  {rv.productTitle}
                </Link>
              </div>

              {rv.content ? <p style={{ margin: '8px 0 0' }}>{rv.content}</p> : null}

              {rv.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 6, marginTop: 8 }}>
                  {rv.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer">
                      <img
                        src={url}
                        alt="评价图片"
                        style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 6 }}
                        loading="lazy"
                        decoding="async"
                      />
                    </a>
                  ))}
                </div>
              ) : null}

              {rv.appendContent ? (
                <div
                  style={{
                    marginTop: 10,
                    padding: '8px 10px',
                    borderLeft: '3px solid var(--border-strong, #ddd)',
                    background: 'var(--surface-2, #fafafa)',
                  }}
                >
                  <div className="small muted">追评 · {formatDate(rv.appendAt)}</div>
                  <div className="small" style={{ marginTop: 4 }}>{rv.appendContent}</div>
                </div>
              ) : null}

              {rv.replyContent ? (
                <div
                  style={{
                    marginTop: 10,
                    padding: '8px 10px',
                    borderLeft: '3px solid var(--success, #2f9e44)',
                    background: 'var(--surface-2, #fafafa)',
                  }}
                >
                  <div className="small muted">商家回复 · {formatDate(rv.replyAt)}</div>
                  <div className="small" style={{ marginTop: 4 }}>{rv.replyContent}</div>
                </div>
              ) : null}

              <div className="row" style={{ gap: 8, marginTop: 10 }}>
                {rv.replyable ? (
                  <button className="btn btn-primary btn-sm" onClick={() => openReply(rv)}>
                    回复评价
                  </button>
                ) : (
                  <span className="small muted">已回复，不可修改</span>
                )}
                <Link className="btn btn-ghost btn-sm" to={`/seller/product-insights?spuId=${rv.spuId}`}>
                  商品分析
                </Link>
              </div>
            </div>
          ))
        : null}

      {data ? (
        <Pagination page={page} pageCount={data.pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={replying !== null}
        title="回复评价"
        onClose={() => setReplying(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setReplying(null)}>取消</button>
            <button
              className="btn btn-primary"
              disabled={busy || content.trim().length === 0}
              onClick={submitReply}
            >
              {busy ? '发布中…' : '发布回复'}
            </button>
          </>
        }
      >
        {replying ? (
          <div>
            <p className="small muted" style={{ marginBottom: 10 }}>
              为「{replying.productTitle}」的这条 {replying.rating} 星评价回复。
              提交后立即公开显示，且<b>只能回复一次、不可修改</b>。
            </p>
            {actionError ? <Alert kind="error">{actionError}</Alert> : null}
            <Field label={`回复内容（${content.length}/${MAX_REPLY}）`}>
              <textarea
                className="input"
                rows={4}
                maxLength={MAX_REPLY}
                placeholder="例如：很抱歉给您带来不好的体验，已为您安排补发，请注意查收。"
                value={content}
                onChange={(e) => setContent(e.target.value)}
              />
            </Field>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
