/**
 * 平台管理后台 · 评价管理（H-02）。
 *
 * 数据源：
 *   GET  /api/admin/reviews              —— 全平台评价列表（审核态 / 星级 / 关键词筛选）
 *   POST /api/admin/reviews/{id}/audit   —— REJECT 下架（原因必填）/ APPROVE 恢复
 *
 * 一条必须让运营看懂的口径：下架不是「标记一下」——
 * 公开评价区、评分汇总、卖家信用分与商品页的评分列都只统计 APPROVED；
 * 下架后这条评价立即从这些口径中消失，并在事务提交后触发商品评分重算。
 * 因此「下架」必须填原因：它是事后复盘与客诉处理的唯一凭据。
 *
 * 恢复是下架的逆动作，用来纠正误判；恢复后评价重新参与评分计算。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { AdminReviewView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const STATUS_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已下架' },
];

const AUDIT_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已下架',
};

const RATING_OPTIONS: Array<{ value: number | null; label: string }> = [
  { value: null, label: '全部星级' },
  { value: 5, label: '5 星' },
  { value: 4, label: '4 星' },
  { value: 3, label: '3 星' },
  { value: 2, label: '2 星' },
  { value: 1, label: '1 星' },
];

function ReviewStatusTag({ status }: { status: string }) {
  const cls = status === 'REJECTED' ? 'tag tag-danger'
    : status === 'PENDING' ? 'tag tag-warn'
      : 'tag tag-success';
  return <span className={cls}>{AUDIT_LABEL[status] ?? status}</span>;
}

export const AdminReviewsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();
  const confirm = useConfirm();

  const [status, setStatus] = useState('');
  const [rating, setRating] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [detail, setDetail] = useState<AdminReviewView | null>(null);
  const [target, setTarget] = useState<AdminReviewView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.reviews({
        status: status || undefined,
        rating: rating ?? undefined,
        keyword: keyword || undefined,
        page,
        pageSize: 10,
      }),
      [status, rating, keyword, page],
    ),
    operator,
    [status, rating, keyword, page],
  );

  if (!operator) return <AdminDenied />;

  const openTakedown = (item: AdminReviewView) => {
    setTarget(item);
    setReason('');
    setActionError(null);
  };

  const submitTakedown = async () => {
    if (!target) return;
    const trimmed = reason.trim();
    if (!trimmed) {
      setActionError('下架评价必须填写原因');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await adminApi.auditReview(target.id, { action: 'REJECT', reason: trimmed });
      push('评价已下架，公开评价区与评分已同步重算', 'success');
      setTarget(null);
      setDetail(null);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const restore = async (item: AdminReviewView) => {
    const ok = await confirm({
      title: '恢复评价',
      message: '恢复后该评价会重新出现在商品评价区，并重新参与评分计算。',
      confirmText: '恢复',
    });
    if (!ok) return;
    try {
      await adminApi.auditReview(item.id, { action: 'APPROVE' });
      push('评价已恢复', 'success');
      setDetail(null);
      reload();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const renderActions = (item: AdminReviewView) => (
    <div className="row" style={{ gap: 6 }}>
      <button className="btn btn-sm btn-ghost" onClick={() => setDetail(item)}>
        查看
      </button>
      {item.auditStatus === 'REJECTED' ? (
        <button className="btn btn-sm btn-primary" onClick={() => void restore(item)}>
          恢复
        </button>
      ) : (
        <button className="btn btn-sm btn-danger" onClick={() => openTakedown(item)}>
          下架
        </button>
      )}
    </div>
  );

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 评价管理</h2>
      <AdminTabs />

      <Alert kind="info">
        只有<b>已通过</b>的评价会出现在商品评价区、评分汇总与卖家信用分里。
        下架会立即把这些口径中的该条评价移除，并在事务提交后重算商品评分；
        下架原因必填 —— 它是事后复盘与客诉处理的唯一凭据。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            className={`btn btn-sm ${status === tab.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setStatus(tab.value);
              setPage(1);
            }}
          >
            {tab.label}
          </button>
        ))}
        <span style={{ width: 8 }} />
        {RATING_OPTIONS.map((opt) => (
          <button
            key={String(opt.value)}
            className={`btn btn-sm ${rating === opt.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setRating(opt.value);
              setPage(1);
            }}
          >
            {opt.label}
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
          placeholder="搜评价内容 / 商品标题 / 订单号"
          width={340}
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
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的评价` : '没有符合条件的评价'}
          desc="买家确认收货并提交评价后会出现在这里"
        />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>商品</th>
                <th>评分</th>
                <th>评价内容</th>
                <th>用户</th>
                <th>时间</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.id}>
                  <td style={{ maxWidth: 240 }}>
                    <div className="row" style={{ gap: 8 }}>
                      {item.productImage ? (
                        <img
                          src={item.productImage}
                          alt=""
                          style={{ width: 36, height: 36, borderRadius: 6, objectFit: 'cover' }}
                        />
                      ) : null}
                      <div>
                        <div>{item.productTitle}</div>
                        <div className="small muted mono">{item.orderNo}</div>
                      </div>
                    </div>
                  </td>
                  <td>{item.rating} 星</td>
                  <td style={{ maxWidth: 300 }}>
                    <div
                      style={{
                        display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                      }}
                    >
                      {item.content || '（未填写文字）'}
                    </div>
                    {item.images.length > 0 ? (
                      <div className="small muted">附 {item.images.length} 张图</div>
                    ) : null}
                  </td>
                  <td style={{ maxWidth: 140 }}>
                    <div>{item.nickname}</div>
                    <div className="small muted mono">{item.userId}</div>
                  </td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td><ReviewStatusTag status={item.auditStatus} /></td>
                  <td>{renderActions(item)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {data ? (
        <Pagination
          page={page}
          pageCount={Math.max(1, data.pageCount)}
          total={data.total}
          onChange={setPage}
        />
      ) : null}

      {/* 详情：完整内容 / 晒图 / 追评 / 审核台账 */}
      <Modal
        open={detail !== null}
        title="评价详情"
        onClose={() => setDetail(null)}
        footer={detail ? (
          <>
            <button className="btn btn-ghost" onClick={() => setDetail(null)}>关闭</button>
            {detail.auditStatus === 'REJECTED' ? (
              <button className="btn btn-primary" onClick={() => void restore(detail)}>恢复评价</button>
            ) : (
              <button className="btn btn-danger" onClick={() => openTakedown(detail)}>下架评价</button>
            )}
          </>
        ) : null}
      >
        {detail ? (
          <div>
            <div className="row row-wrap" style={{ gap: 12, marginBottom: 12 }}>
              <ReviewStatusTag status={detail.auditStatus} />
              <span className="small muted mono">订单 {detail.orderNo}</span>
              <span className="small muted mono">商品 {detail.spuId}</span>
              <span>{detail.rating} 星</span>
            </div>
            <div className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="small muted">评价正文</div>
              <div style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>
                {detail.content || '（未填写文字）'}
              </div>
              {detail.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 10 }}>
                  {detail.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer">
                      <img
                        src={url}
                        alt="晒图"
                        style={{ width: 72, height: 72, borderRadius: 6, objectFit: 'cover' }}
                      />
                    </a>
                  ))}
                </div>
              ) : null}
              {detail.appendContent ? (
                <div style={{ marginTop: 12 }}>
                  <div className="small muted">追评（{formatDate(detail.appendAt)}）</div>
                  <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{detail.appendContent}</div>
                </div>
              ) : null}
            </div>
            <div className="small muted">
              评价人：{detail.nickname}（{detail.userId}）
              {detail.anonymous ? ' · 买家选择匿名展示' : ''}
              <br />
              发布时间：{formatDate(detail.createdAt)}
            </div>
            {detail.auditStatus === 'REJECTED' ? (
              <Alert kind="warn">
                已下架：{detail.auditReason || '（未记录原因）'}
                {detail.auditedAt ? ` · ${formatDate(detail.auditedAt)}` : ''}
              </Alert>
            ) : null}
          </div>
        ) : null}
      </Modal>

      {/* 下架：原因必填（站内弹窗，不用 window.prompt） */}
      <Modal
        open={target !== null}
        title="下架评价"
        onClose={() => setTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setTarget(null)}>取消</button>
            <button className="btn btn-danger" disabled={busy} onClick={submitTakedown}>
              {busy ? '提交中…' : '确认下架'}
            </button>
          </>
        }
      >
        {target ? (
          <div>
            <p style={{ marginBottom: 12 }}>
              评价 <b className="mono">{target.id}</b> · {target.productTitle} · {target.rating} 星
            </p>
            <div className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="small muted">评价正文</div>
              <div style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>
                {target.content || '（未填写文字）'}
              </div>
            </div>
            <Field
              label="下架原因（必填）"
              required
              hint="写清依据（如广告导流、人身攻击、泄露隐私），运营复盘与客诉处理都靠它"
            >
              <textarea
                className="textarea"
                rows={3}
                maxLength={255}
                placeholder="例如：评价正文含微信号导流，属站外引流"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
            <div className="small muted" style={{ marginBottom: 8 }}>
              下架后该评价会立即从公开评价区、评分汇总与卖家信用分中移除，并触发商品评分重算。
            </div>
            {actionError ? <Alert kind="error">{actionError}</Alert> : null}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
