/**
 * 我的闲置（Web 端 · 第六十六轮 A 方案补齐）。
 *
 * <p><b>为什么现在才补</b>：后端这六个端点（发布 / 我的发布 / 上下架 / 编辑 / 删除 / 重提）
 * 在第五十二、五十三轮就已建齐，**只要求「登录 + 实名」、不要求卖家角色**；
 * 小程序也有完整四页（flea-market / publish / mine / detail）——
 * 缺口只在 Web 页面没接。而 Web 的《二手闲置》页顶部当时还写着
 * 「本期不支持发布闲置（没有对应后端接口）」，那句注释早就过时了，本轮一并更正。
 *
 * <p><b>与「卖家身份」的关系</b>：发布和管理闲置**不需要**成为卖家。
 * 二手闲置是个人对个人（C2C），卖家身份是给「有店铺、有自营商品」的人用的，
 * 两条线刻意分开 —— 所以本页任何登录用户都能进，而不是只对 SELLER 开放。
 *
 * <p><b>筛选口径与小程序严格一致</b>（不重不漏的划分）：
 * 审核两标签只带 `auditStatus`；交易三标签必须**同时**带 `auditStatus=APPROVED` ——
 * 否则「在售」会把还没过审、甚至已被驳回的条目一起捞进来（第五十三轮踩过的坑）。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { listingImage } from '@duoduo/api-sdk/images';
import { Link } from 'react-router-dom';
import { listingApi } from '@duoduo/api-sdk/endpoints';
import type { ListingMineView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, Modal, Pagination, SearchBar, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';

const PAGE_SIZE = 10;

/** 筛选口径（见文件头注释）；`params` 直接拼进查询串 */
const FILTERS: { key: string; label: string; params: Record<string, string> }[] = [
  { key: 'ALL', label: '全部', params: {} },
  { key: 'ON_SALE', label: '在售', params: { status: 'ON_SALE', auditStatus: 'APPROVED' } },
  { key: 'AUDIT_PENDING', label: '审核中', params: { auditStatus: 'PENDING' } },
  { key: 'AUDIT_REJECTED', label: '审核失败', params: { auditStatus: 'REJECTED' } },
  { key: 'OFF_SHELF', label: '已下架', params: { status: 'OFF_SHELF', auditStatus: 'APPROVED' } },
  { key: 'SOLD', label: '已售出', params: { status: 'SOLD', auditStatus: 'APPROVED' } },
];

/** 卡片徽章：审核态优先于交易态 —— 未过审的条目显示「在售」会让卖家以为已经挂出去了 */
const badgeOf = (it: ListingMineView): { text: string; cls: string } => {
  if (it.auditStatus === 'PENDING') return { text: '审核中', cls: 'tag tag-second' };
  if (it.auditStatus === 'REJECTED') return { text: '审核未通过', cls: 'tag tag-danger' };
  if (it.status === 'SOLD') return { text: '已售出', cls: 'tag' };
  if (it.status === 'OFF_SHELF') return { text: '已下架', cls: 'tag' };
  return { text: '在售', cls: 'tag tag-success' };
};

export const MyListingsPage: React.FC = () => {
  const { push } = useToast();
  const [filter, setFilter] = useState('ALL');
  const [page, setPage] = useState(1);
  const [tick, setTick] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<ListingMineView | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const params = useMemo(() => FILTERS.find((f) => f.key === filter)?.params ?? {}, [filter]);

  const fetcher = useCallback(
    () => listingApi.mine({ ...params, keyword: keyword || undefined, page, pageSize: PAGE_SIZE }),
    // 依赖只放 primitive：params 每次渲染都是新对象，放进去会变成每轮都重发请求
    [params.status, params.auditStatus, keyword, page, tick],
  );
  const { data, loading, error } = useAsync(fetcher, true, [params.status, params.auditStatus, keyword, page, tick]);

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  /** 所有写操作共用：置忙 → 调接口 → 提示 → 重拉列表 */
  const act = async (id: string, label: string, fn: () => Promise<unknown>) => {
    setBusyId(id);
    try {
      await fn();
      push(`${label}成功`, 'success');
      setTick((v) => v + 1);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的闲置</h2>
        <span className="section-sub">
          共 {total} 件 · 发布与管理二手闲置不需要卖家身份（个人对个人）
        </span>
      </div>

      <div className="row row-wrap" style={{ gap: 8, marginBottom: 14 }}>
        {FILTERS.map((f) => (
          <button
            key={f.key}
            className={`btn btn-sm ${filter === f.key ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setFilter(f.key);
              setPage(1);
            }}
          >
            {f.label}
          </button>
        ))}
        <Link className="btn btn-primary btn-sm" to="/listings/new" style={{ marginLeft: 'auto' }}>
          + 发布闲置
        </Link>
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜标题、品种或城市"
          width={300}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的闲置 {total} 件</span>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !data ? <LoadingBlock text="正在加载你的闲置…" /> : null}

      {!loading && items.length === 0 ? (
        <Empty
          icon="🏷️"
          title={
            keywordInput.trim()
              ? `没有匹配「${keywordInput.trim()}」的闲置`
              : filter === 'ALL'
                ? '还没有发布过闲置'
                : '这个分类下没有闲置'
          }
          desc="把闲置的猫爬架、旧猫包挂出来，让它到需要的人手里去"
          action={
            <Link className="btn btn-primary" to="/listings/new">
              立即发布
            </Link>
          }
        />
      ) : null}

      <div className="col" style={{ gap: 12 }}>
        {items.map((it) => {
          const badge = badgeOf(it);
          const canToggle = it.auditStatus === 'APPROVED' && it.status !== 'SOLD';
          const busy = busyId === it.id;
          return (
            <div className="card card-pad" key={it.id}>
              <div className="row" style={{ gap: 14, alignItems: 'flex-start' }}>
                <div
                  style={{
                    width: 84, height: 84, flex: 'none', borderRadius: 10, overflow: 'hidden',
                    background: 'var(--surface-2)', display: 'grid', placeItems: 'center',
                  }}
                >
                  {/* 与小程序一致：任何闲置都有图（无图时按 id 稳定取池图） */}
                  <img src={listingImage(it.id, it.cover)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} loading="lazy" decoding="async" />
                </div>

                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                    <span className={badge.cls}>{badge.text}</span>
                    <Link to={`/listing/${it.id}`} style={{ fontWeight: 500 }}>
                      {it.title}
                    </Link>
                  </div>
                  <div className="row row-wrap" style={{ gap: 14, marginTop: 6 }}>
                    <span className="price">{formatPrice(it.price)}</span>
                    {it.city ? <span className="small muted">{it.city}</span> : null}
                    <span className="small muted">{it.viewCount} 次浏览 · {it.wantCount} 人想要</span>
                  </div>

                  {/* 驳回原因必须显式展示：不告诉他为什么被拒，他就只能猜着改 */}
                  {it.auditStatus === 'REJECTED' && it.auditReason ? (
                    <div style={{ marginTop: 8 }}>
                      <Alert kind="error">审核未通过：{it.auditReason}</Alert>
                    </div>
                  ) : null}
                  {it.auditStatus === 'PENDING' ? (
                    <div className="small muted" style={{ marginTop: 6 }}>
                      已提交审核，通过后自动上架；期间只有你自己能看到。
                    </div>
                  ) : null}

                  <div className="row row-wrap" style={{ gap: 8, marginTop: 10 }}>
                    {canToggle ? (
                      <button
                        className="btn btn-ghost btn-sm"
                        disabled={busy}
                        onClick={() => act(it.id, it.status === 'ON_SALE' ? '下架' : '上架',
                          () => listingApi.changeStatus(it.id, it.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'))}
                      >
                        {it.status === 'ON_SALE' ? '下架' : '重新上架'}
                      </button>
                    ) : null}

                    <Link className="btn btn-ghost btn-sm" to={`/listings/${it.id}/edit`}>
                      编辑
                    </Link>

                    {it.auditStatus === 'REJECTED' ? (
                      <button
                        className="btn btn-primary btn-sm"
                        disabled={busy}
                        onClick={() => act(it.id, '重新提审', () => listingApi.resubmit(it.id, { title: it.title, price: it.price }))}
                      >
                        直接重新提审
                      </button>
                    ) : null}

                    <button
                      className="btn btn-ghost btn-sm"
                      style={{ color: 'var(--danger)' }}
                      disabled={busy}
                      onClick={() => setConfirming(it)}
                    >
                      删除
                    </button>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {data ? (
        <Pagination page={page} pageCount={pages} total={total} onChange={setPage} />
      ) : null}

      <Modal
        open={Boolean(confirming)}
        title="删除这件闲置？"
        onClose={() => setConfirming(null)}
        footer={
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirming(null)}>
              再想想
            </button>
            <button
              className="btn btn-primary btn-sm"
              onClick={async () => {
                const target = confirming;
                setConfirming(null);
                if (target) await act(target.id, '删除', () => listingApi.remove(target.id));
              }}
            >
              确认删除
            </button>
          </div>
        }
      >
        <p style={{ marginBottom: 8 }}>「{confirming?.title}」将下架并从你的列表里移除。</p>
        <div className="small muted">
          这里用的是<b>软删除</b>：别人的聊天记录与订单里对它的引用不会变成「查无此物」，
          只是你和买家都搜不到它了。
        </div>
      </Modal>
    </div>
  );
};
