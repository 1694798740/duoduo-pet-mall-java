/**
 * 优惠券：领券中心 + 我的券包（A5）。
 *
 * 数据源：
 *   GET  /api/coupons/templates          领券中心（**公开只读**，未登录也能看）
 *   POST /api/coupons/templates/{id}/claim   领券（需登录）
 *   GET  /api/coupons/mine?status=       我的券包（需登录）
 *
 * 三个刻意的设计：
 *   1. **「能不能领」由服务端判定**（`claimable` / `claimableReason`）：领取窗口、
 *      剩余量、以及「我是否已领过」三者里，最后一项依赖登录态。前端自己算必然与服务端分歧，
 *      表现出来就是「按钮能点、点了报错」。
 *   2. **重复领取不报错**：后端返回 `replay=true` 与已有那张券的券码。用户双击、
 *      网络重试、多端并发都是常态，把这些报成失败只会让用户以为操作没成功。
 *   3. **未登录时领券按钮引导去登录**，而不是禁用后什么都不说。
 */

import React, { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '../api/client';
import { couponApi } from '../api/endpoints';
import type { CouponStatus, CouponTemplateView, CouponView } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, Empty, LoadingBlock, formatDate, formatPrice, useToast } from '../components/ui';
import { useAuth } from '../store/auth';

type Tab = 'center' | 'mine';

/** 券包筛选页签；value 为空表示全部 */
const MINE_FILTERS: { label: string; value: '' | CouponStatus }[] = [
  { label: '全部', value: '' },
  { label: '未使用', value: 'UNUSED' },
  { label: '已锁定', value: 'LOCKED' },
  { label: '已使用', value: 'USED' },
  { label: '已过期', value: 'EXPIRED' },
];

/**
 * 券状态 → 标签配色。
 *
 * 只有「可用」与「已锁定」着色：已使用 / 已过期是终态，不需要再吸引注意力；
 * 未知状态同样走中性样式，绝不假设它一定是这四种之一。
 */
const statusTone = (status: string): string => {
  switch (status) {
    case 'UNUSED':
      return 'tag tag-success';
    case 'LOCKED':
      return 'tag tag-warn';
    default:
      return 'tag';
  }
};

export const CouponsPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>('center');
  const [mineStatus, setMineStatus] = useState<'' | CouponStatus>('UNUSED');
  const [claiming, setClaiming] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const center = useAsync<CouponTemplateView[]>(
    useCallback(() => couponApi.templates(), []),
    true,
    [],
  );

  const mine = useAsync<CouponView[]>(
    useCallback(
      () => (user ? couponApi.mine(mineStatus ? { status: mineStatus } : undefined) : Promise.resolve([])),
      [user, mineStatus],
    ),
    true,
    [user, mineStatus],
  );

  const claim = async (tpl: CouponTemplateView) => {
    setActionError(null);
    if (!user) {
      push('请先登录后再领券', 'info');
      navigate('/login');
      return;
    }
    setClaiming(tpl.id);
    try {
      const result = await couponApi.claim(tpl.id);
      push(
        result.replay
          ? `你已经领过这张券（券码 ${result.code}）`
          : `领券成功，券码 ${result.code}`,
        'success',
      );
      // 领完要同时刷新两个列表：券包的「未使用」里会出现它，领券中心的按钮要变成「已领取」
      center.reload();
      mine.reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setClaiming(null);
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">优惠券</h2>
        <span className="section-sub">领券下单立减，券在支付成功后核销</span>
      </div>

      <div className="tabs row row-wrap" style={{ gap: 8, marginBottom: 14 }}>
        <button
          className={tab === 'center' ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'}
          onClick={() => setTab('center')}
        >
          领券中心
        </button>
        <button
          className={tab === 'mine' ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'}
          onClick={() => setTab('mine')}
        >
          我的券包
        </button>
      </div>

      {actionError ? (
        <div style={{ marginBottom: 14 }}>
          <Alert kind="error">{actionError}</Alert>
        </div>
      ) : null}

      {tab === 'center' ? (
        <section>
          {center.loading && !center.data ? <LoadingBlock text="正在加载可领取的券…" /> : null}
          {center.error && !center.data ? <Alert kind="error">{center.error}</Alert> : null}
          {center.data && center.data.length === 0 ? (
            <Empty icon="🎟️" title="暂时没有可领取的优惠券" />
          ) : null}
          {center.data
            ? center.data.map((tpl) => (
                <div key={tpl.id} className="card card-pad" style={{ marginBottom: 10 }}>
                  <div className="row-between row-wrap">
                    <div style={{ minWidth: 0 }}>
                      <strong>{tpl.name}</strong>
                      <div className="small muted" style={{ marginTop: 4 }}>
                        {tpl.thresholdAmount > 0
                          ? `满 ${formatPrice(tpl.thresholdAmount)} 可用`
                          : '无门槛'}
                        {' · '}
                        {tpl.typeLabel}
                        {tpl.description ? ` · ${tpl.description}` : ''}
                      </div>
                      <div className="small muted" style={{ marginTop: 4 }}>
                        领取截止 {formatDate(tpl.endAt)} · 剩余 {tpl.remainingCount} / {tpl.totalCount}
                      </div>
                    </div>
                    <div style={{ textAlign: 'right', flex: '0 0 auto' }}>
                      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--danger)' }}>
                        {formatPrice(tpl.discountAmount)}
                      </div>
                      <button
                        className="btn btn-primary btn-sm"
                        style={{ marginTop: 6 }}
                        disabled={!tpl.claimable || claiming === tpl.id}
                        onClick={() => claim(tpl)}
                      >
                        {claiming === tpl.id ? '领取中…' : '立即领取'}
                      </button>
                      {tpl.claimableReason ? (
                        <div className="small muted" style={{ marginTop: 4 }}>
                          {tpl.claimableReason}
                        </div>
                      ) : null}
                    </div>
                  </div>
                </div>
              ))
            : null}
        </section>
      ) : (
        <section>
          {!user ? (
            <Empty
              icon="🔐"
              title="登录后查看我的券包"
              action={
                <Link className="btn btn-primary" to="/login">
                  去登录
                </Link>
              }
            />
          ) : (
            <>
              <div className="row row-wrap" style={{ gap: 8, marginBottom: 12 }}>
                {MINE_FILTERS.map((f) => (
                  <button
                    key={f.value || 'ALL'}
                    className={mineStatus === f.value ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'}
                    onClick={() => setMineStatus(f.value)}
                  >
                    {f.label}
                  </button>
                ))}
              </div>

              {mine.loading && !mine.data ? <LoadingBlock text="正在加载我的券…" /> : null}
              {mine.error && !mine.data ? <Alert kind="error">{mine.error}</Alert> : null}
              {mine.data && mine.data.length === 0 ? (
                <Empty
                  icon="🎫"
                  title="这里还没有券"
                  action={
                    <button className="btn btn-primary" onClick={() => setTab('center')}>
                      去领券中心看看
                    </button>
                  }
                />
              ) : null}

              {mine.data
                ? mine.data.map((c) => (
                    <div key={c.code} className="card card-pad" style={{ marginBottom: 10 }}>
                      <div className="row-between row-wrap">
                        <div style={{ minWidth: 0 }}>
                          <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                            <strong>{c.name ?? '优惠券'}</strong>
                            <span className={statusTone(c.status)}>{c.statusLabel}</span>
                          </div>
                          <div className="small muted" style={{ marginTop: 4 }}>
                            {c.thresholdAmount > 0
                              ? `满 ${formatPrice(c.thresholdAmount)} 可用`
                              : '无门槛'}
                            {' · 有效期至 '}
                            {formatDate(c.expireAt)}
                          </div>
                          <div className="small muted" style={{ marginTop: 4 }}>
                            券码 <code>{c.code}</code>
                            {c.lockedOrderNo ? ` · 已被订单 ${c.lockedOrderNo} 占用` : ''}
                            {c.usedOrderNo ? ` · 已用于订单 ${c.usedOrderNo}` : ''}
                          </div>
                        </div>
                        <div style={{ textAlign: 'right', flex: '0 0 auto' }}>
                          <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--danger)' }}>
                            {formatPrice(c.discountAmount)}
                          </div>
                          {c.status === 'UNUSED' ? (
                            <Link className="btn btn-ghost btn-sm" to="/cart" style={{ marginTop: 6 }}>
                              去使用
                            </Link>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  ))
                : null}
            </>
          )}
        </section>
      )}

      <div className="card card-pad" style={{ marginTop: 16 }}>
        <div className="small muted">
          券的使用规则：结算页会自动列出可用券并按最大抵扣**默认选中**；
          下单时券被锁定，支付成功核销、取消订单自动退回。
          已核销的券不随退款退回（v1 口径），退款金额为订单实付金额。
        </div>
      </div>
    </div>
  );
};
