/**
 * 通知中心（Web 端 · 第六十七轮功能对齐）。
 *
 * <p>与小程序 `pages/notification` 同一套数据源（duoduo-social `/api/socials/notices`）。
 * 定向通知与全站公告在**接口层**已合并为同一视图（`read` 对公告取已读集合表），
 * 前端只负责：分类筛选 → 点开标记已读（跳转）→ 全部已读。
 *
 * <p>跳转用服务端给的 {@code jumpPage}，前端**不自建映射** ——
 * 通知该跳哪是业务事实（发通知的那一侧最清楚），前端自己映射迟早和后端对不上。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { noticeApi } from '@duoduo/api-sdk/endpoints';
import type { NoticeView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';

const PAGE_SIZE = 15;

/**
 * 分类 chip：**必须与后端白名单一致**（`NoticeService.CATEGORIES` = SYSTEM / ANNOUNCEMENT / FRIEND / MERCHANT / ORDER），
 * 也与小程序 `pages/notification` 的 chip 保持一致。
 *
 * ⚠️ R74-4 修：这里原本写的是 `ORDER / AFTERSALE / SOCIAL / NOTICE` —— 其中**三个键后端根本不存在**：
 * 选「售后 / 互动」会筛出空列表，选「公告」也筛不到（真正的公告分类是 `ANNOUNCEMENT`）；
 * 反过来，真实存在的 `FRIEND / MERCHANT` 消息又只能以英文原名显示、无法筛选。
 * 根因是「前端自己发明了一套分类」而不是照抄服务端白名单 —— 分类是**业务事实**，只能有一处定义。
 */
const CATEGORIES: { key: string; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'ORDER', label: '订单' },
  { key: 'FRIEND', label: '好友' },
  { key: 'MERCHANT', label: '商家' },
  { key: 'SYSTEM', label: '系统' },
  { key: 'ANNOUNCEMENT', label: '公告' },
];

export const NotificationsPage: React.FC = () => {
  const navigate = useNavigate();
  const { push } = useToast();
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(1);
  const [tick, setTick] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);

  const fetcher = useCallback(
    () => noticeApi.list({ category: category || undefined, page, pageSize: PAGE_SIZE }),
    [category, page, tick],
  );
  const { data, loading, error } = useAsync(fetcher, true, [category, page, tick]);

  const items = data?.items ?? [];
  const unread = useMemo(() => items.filter((it) => !it.read).length, [items]);
  const pages = Math.max(1, Math.ceil((data?.total ?? 0) / PAGE_SIZE));

  /** 打开通知：未读则标记已读；jumpPage 是服务端建议的落地页 */
  const open = async (it: NoticeView) => {
    setBusyId(it.id);
    try {
      if (!it.read) await noticeApi.markRead(it.id);
      setTick((v) => v + 1);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusyId(null);
    }
    if (it.jumpPage) navigate(it.jumpPage);
  };

  const readAll = async () => {
    try {
      await noticeApi.readAll();
      push('已全部标记为已读', 'success');
      setTick((v) => v + 1);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">通知中心</h2>
        <span className="section-sub">
          本页未读 {unread} 条{data ? ` · 共 ${data.total} 条` : ''}
        </span>
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} onClick={readAll}>
          全部已读
        </button>
      </div>

      <div className="row row-wrap" style={{ gap: 8, marginBottom: 14 }}>
        {CATEGORIES.map((c) => (
          <button
            key={c.key}
            className={`btn btn-sm ${category === c.key ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setCategory(c.key);
              setPage(1);
            }}
          >
            {c.label}
          </button>
        ))}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !data ? <LoadingBlock text="正在加载通知…" /> : null}

      {!loading && items.length === 0 ? (
        <Empty icon="🔔" title="暂无通知" desc="订单 / 售后 / 互动 / 平台公告都会出现在这里" />
      ) : null}

      <div className="col" style={{ gap: 10 }}>
        {items.map((it) => (
          <button
            key={it.id}
            className="card card-pad"
            style={{
              textAlign: 'left', cursor: 'pointer', width: '100%',
              borderLeft: it.read ? undefined : '3px solid var(--brand)',
            }}
            disabled={busyId === it.id}
            onClick={() => void open(it)}
          >
            <div className="row" style={{ gap: 8, alignItems: 'center' }}>
              {!it.read ? <span className="tag tag-active">未读</span> : null}
              <span className="tag">{CATEGORIES.find((c) => c.key === it.category)?.label ?? it.category}</span>
              <b>{it.title}</b>
            </div>
            <div className="small" style={{ marginTop: 6 }}>{it.content}</div>
            <div className="small muted" style={{ marginTop: 6 }}>
              {it.createdAt?.replace('T', ' ').slice(0, 16)}
              {it.jumpPage ? ' · 点击查看详情' : ''}
            </div>
          </button>
        ))}
      </div>

      {pages > 1 ? (
        <div className="row" style={{ gap: 10, justifyContent: 'center', marginTop: 16 }}>
          <button className="btn btn-ghost btn-sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
            上一页
          </button>
          <span className="small muted">{page} / {pages}</span>
          <button className="btn btn-ghost btn-sm" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>
            下一页
          </button>
        </div>
      ) : null}
    </div>
  );
};
