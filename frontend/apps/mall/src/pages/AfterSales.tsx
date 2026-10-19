/**
 * 我的售后（买家视角，A3）。
 *
 * 数据源：`GET /api/aftersales/mine`（仅返回登录用户名下的单，服务端限定归属）。
 *
 * 三态齐全：加载 / 空 / 错误。空数据不是错误 ——
 * 「还没提过售后」是绝大多数用户的常态，用提示错误的方式呈现会让人以为系统坏了。
 */

import React, { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { afterSaleApi } from '@duoduo/api-sdk/endpoints';
import { Alert, Empty, LoadingBlock, Pagination, formatDate, formatPrice } from '@duoduo/shared/components/ui';
import { useAsync, useLiveRefresh } from '@duoduo/shared/components/hooks';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '@duoduo/shared/components/afterSale';

export const AfterSalesPage: React.FC = () => {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);

  const { data, loading, error, reloadSilently } = useAsync(
    useCallback(
      () => afterSaleApi.mine({ status: status || undefined, page, pageSize: 10 }),
      [status, page],
    ),
    true,
    [status, page],
  );

  /** 售后由卖家/平台推进状态 —— 列表页同样三层静默刷新，不用手动按 F5 */
  useLiveRefresh(reloadSilently);

  return (
    <div>
      <div className="row-between" style={{ marginBottom: 12 }}>
        <h2 className="section-title">我的售后</h2>
        <Link className="btn btn-sm btn-ghost" to="/orders">
          去订单里申请
        </Link>
      </div>

      <Alert kind="info">
        售后从<b>订单</b>发起：在「我的订单」里找到已发货或已完成的订单，点「申请售后」。
        卖家拒绝时可以<b>申请平台仲裁</b>，由运营裁定。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {AFTER_SALE_STATUS_TABS.map((tab) => (
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
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty icon="🧾" title="还没有售后记录" desc="在订单里点「申请售后」即可发起" />
      ) : null}

      {!loading && data
        ? data.items.map((item) => (
            <div className="card card-pad" key={item.id} style={{ marginBottom: 12 }}>
              <div className="row-between">
                <div>
                  <div className="mono">{item.orderNo}</div>
                  <div className="small muted">
                    {item.typeLabel} · 申请于 {formatDate(item.createdAt)}
                  </div>
                </div>
                <AfterSaleStatusTag status={item.status} label={item.statusLabel} />
              </div>

              <div style={{ marginTop: 10 }}>{item.reason}</div>

              {item.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
                  {item.images.map((url) => (
                    <a key={url} className="small" href={url} target="_blank" rel="noreferrer">
                      举证图
                    </a>
                  ))}
                </div>
              ) : null}

              {item.sellerReply ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="warn">卖家处理说明：{item.sellerReply}</Alert>
                </div>
              ) : null}

              {item.arbitrateNote ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="info">平台裁定说明：{item.arbitrateNote}</Alert>
                </div>
              ) : null}

              <div className="row-between" style={{ marginTop: 12 }}>
                <span className="small muted">
                  {item.refundAmount !== null ? `已退款 ${formatPrice(item.refundAmount)}` : '退款金额待定'}
                </span>
                <Link className="btn btn-sm btn-ghost" to={`/after-sales/${item.id}`}>
                  查看详情
                </Link>
              </div>
            </div>
          ))
        : null}

      {data && data.total > data.pageSize ? (
        <Pagination
          page={page}
          pageCount={Math.max(1, data.pageCount)}
          total={data.total}
          onChange={setPage}
        />
      ) : null}
    </div>
  );
};
