/**
 * 通知中心（Web 端 · 第六十七轮功能对齐；2026-10-27 补齐筛选/搜索/批量）。
 *
 * <p>与小程序 `pages/notification` 同一套数据源（duoduo-social `/api/socials/notices`）。
 * 定向通知与全站公告在**接口层**已合并为同一视图（`read` 对公告取已读集合表），
 * 前端只负责：分类筛选 → 点开标记已读（跳转）→ 全部已读。
 *
 * <p>跳转用服务端给的 {@code webPath}，前端**不自建映射** ——
 * 通知该跳哪是业务事实（发通知的那一侧最清楚），前端自己映射迟早和后端对不上。
 *
 * <h2>2026-10-27 新增（对齐「站内消息中心」形态）</h2>
 * <ul>
 *   <li><b>未读 / 已读筛选</b>与<b>标题搜索</b>：两者都可以与分类叠加，且**全部走服务端**
 *       （前端在当前页 filter 会把分页外的记录全漏掉，还会让 total 与列表对不上）；</li>
 *   <li><b>复选框 + 批量操作</b>：全选只选当前页；未勾选时批量按钮置灰；
 *       批量结果一次请求同时更新列表与角标（服务端返回最新未读数）；</li>
 *   <li><b>全部删除</b>：走同一个 batch 端点（`all=true`），与「全部已读」是并列动作。</li>
 * </ul>
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { noticeApi } from '@duoduo/api-sdk/endpoints';
import type { NoticeView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import {
  Alert, Empty, LoadingBlock, Pagination, SearchBar, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { Badge } from '@duoduo/shared/components/badge';
import { useApp } from '@duoduo/shared/store/app';
import { formatBjDateTime } from '@duoduo/shared/lib/time';

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

/** 已读状态筛选（2026-10-27）。空 = 全部 */
const READ_FILTERS: { key: '' | 'UNREAD' | 'READ'; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'UNREAD', label: '未读' },
  { key: 'READ', label: '已读' },
];

export const NotificationsPage: React.FC = () => {
  const navigate = useNavigate();
  /** AI 卡片可以带 `?category=ORDER` 直达某个分类：初始筛选必须从 URL 读 */
  const [searchParams] = useSearchParams();
  const { push } = useToast();
  /** useConfirm 直接返回「ask(options) => Promise<boolean>」，不是 { confirm } —— 别按其他库的习惯解构 */
  const confirm = useConfirm();
  /** 顶栏未读角标的数据源；标记已读后必须立刻刷新，不能等 10 秒兜底轮询 */
  const { refreshUnread } = useApp();
  const [category, setCategory] = useState(() => {
    // 只接受白名单里的分类：URL 是外部输入，拼错的值会让列表看起来「一条都没有」
    const raw = searchParams.get('category') ?? '';
    return CATEGORIES.some((item) => item.key === raw) ? raw : '';
  });
  const [readFilter, setReadFilter] = useState<'' | 'UNREAD' | 'READ'>('');
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);
  const [tick, setTick] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  /** 勾选的 id（只可能是当前页里可见的；换页/换筛选即清空） */
  const [selected, setSelected] = useState<Set<string>>(new Set());
  /** 分类未读数（Q9，2026-10-22）：键 = ORDER/FRIEND/MERCHANT/SYSTEM/ANNOUNCEMENT，来自服务端分桶 */
  const [categoryUnread, setCategoryUnread] = useState<Record<string, number>>({});

  const fetcher = useCallback(
    () =>
      noticeApi.list({
        category: category || undefined,
        read: readFilter || undefined,
        keyword: keyword || undefined,
        page,
        pageSize: PAGE_SIZE,
      }),
    [category, readFilter, keyword, page],
  );
  const { data, loading, error } = useAsync(fetcher, true, [category, readFilter, keyword, page, tick]);

  // 分类角标与列表联动刷新：进入页面 / 标记已读 / 全部已读 / 批量后都会重取
  useEffect(() => {
    let alive = true;
    noticeApi
      .unreadByCategory()
      .then((rows) => {
        if (alive) setCategoryUnread(rows ?? {});
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [tick]);

  // 换分类 / 换筛选 / 换关键词都回到第 1 页并清空勾选 ——
  // 否则用户勾的第 2 页记录在筛选后已经不可见，批量按钮却还亮着「已选 3 条」
  useEffect(() => {
    setPage(1);
    setSelected(new Set());
  }, [category, readFilter, keyword]);

  const items = data?.items ?? [];
  const unread = useMemo(() => items.filter((it) => !it.read).length, [items]);
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const allChecked = items.length > 0 && selected.size === items.length;
  const someChecked = selected.size > 0 && !allChecked;

  const toggleOne = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelected(allChecked ? new Set() : new Set(items.map((it) => it.id)));
  };

  /** 打开通知：未读则标记已读并**立即刷新顶栏角标**；落地页只用服务端给的网页路径 */
  const open = async (it: NoticeView) => {
    setBusyId(it.id);
    try {
      if (!it.read) await noticeApi.markRead(it.id);
      setTick((v) => v + 1);
      // 等角标刷新完成再跳转：否则页面已经离开，用户回头仍看到旧数字
      await refreshUnread();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusyId(null);
    }
    // webPath 为空 = 网页端没有对应页面（如纯公告）——只标已读，不跳到 404
    if (it.webPath) navigate(it.webPath);
  };

  const readAll = async () => {
    setBatchBusy(true);
    try {
      await noticeApi.readAll();
      push('已全部标记为已读', 'success');
      setTick((v) => v + 1);
      setSelected(new Set());
      await refreshUnread();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBatchBusy(false);
    }
  };

  /** 勾选批量：标记已读 / 删除（一次请求，服务端同时回最新未读数） */
  const batchSelected = async (action: 'READ' | 'DELETE') => {
    if (selected.size === 0) return;
    setBatchBusy(true);
    try {
      const res = await noticeApi.batch({ action, ids: Array.from(selected) });
      push(
        action === 'READ' ? `已把 ${res.affected} 条标记为已读` : `已删除 ${res.affected} 条通知`,
        'success',
      );
      setSelected(new Set());
      setTick((v) => v + 1);
      await refreshUnread();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBatchBusy(false);
    }
  };

  /** 全部删除：不可逆（服务端只写「对我不可见」集合，公告行本身不会消失） */
  const deleteAll = async () => {
    const ok = await confirm({
      title: '删除全部通知？',
      message: '删除后这些通知不再出现在你的通知中心（其他用户不受影响），且无法恢复。',
      confirmText: '全部删除',
      cancelText: '取消',
      danger: true,
    });
    if (!ok) return;
    setBatchBusy(true);
    try {
      const res = await noticeApi.batch({ action: 'DELETE', all: true });
      push(`已删除 ${res.affected} 条通知`, 'success');
      setSelected(new Set());
      setTick((v) => v + 1);
      await refreshUnread();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBatchBusy(false);
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">通知中心</h2>
        <span className="section-sub">
          本页未读 {unread} 条{data ? ` · 共 ${total} 条` : ''}
        </span>
        <div className="row" style={{ marginLeft: 'auto', gap: 8 }}>
          <button className="btn btn-ghost btn-sm" disabled={batchBusy} onClick={readAll}>
            全部已读
          </button>
          <button className="btn btn-ghost btn-sm" disabled={batchBusy || total === 0} onClick={deleteAll}>
            全部删除
          </button>
        </div>
      </div>

      {/* 分类 chip + 分类未读角标 */}
      <div className="row row-wrap" style={{ gap: 8, marginBottom: 10 }}>
        {CATEGORIES.map((c) => (
          <button
            key={c.key || 'all'}
            className={`btn btn-sm ${category === c.key ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setCategory(c.key)}
          >
            {c.label}
            <Badge count={c.key ? Number(categoryUnread[c.key]) : 0} />
          </button>
        ))}
      </div>

      {/* 已读状态筛选 + 标题搜索（两者可与分类叠加，全部走服务端） */}
      <div className="list-toolbar">
        <div className="row" style={{ gap: 8 }}>
          {READ_FILTERS.map((f) => (
            <button
              key={f.key || 'all'}
              className={`btn btn-sm ${readFilter === f.key ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setReadFilter(f.key)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <SearchBar
          value={keywordInput}
          onChange={setKeywordInput}
          busy={loading && Boolean(keyword.trim())}
          placeholder="搜标题或正文"
          width={260}
        />
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !data ? <LoadingBlock text="正在加载通知…" /> : null}

      {!loading && items.length === 0 ? (
        <Empty
          icon="🔔"
          title={keyword || readFilter || category ? '没有符合条件的通知' : '暂无通知'}
          desc={
            keyword || readFilter || category
              ? '换个分类、已读状态或关键词试试'
              : '订单 / 售后 / 互动 / 平台公告都会出现在这里'
          }
        />
      ) : null}

      {items.length > 0 ? (
        <>
          {/* 全选（只选当前页）：indeterminate 用 ref 设置，React 没有对应属性 */}
          <div className="row" style={{ gap: 10, margin: '4px 0 10px' }}>
            <label className="row" style={{ gap: 6, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={allChecked}
                ref={(el) => {
                  if (el) el.indeterminate = someChecked;
                }}
                onChange={toggleAll}
              />
              <span className="small muted">全选本页（{items.length} 条）</span>
            </label>
          </div>

          {selected.size > 0 ? (
            <div
              className="card card-pad"
              style={{
                marginBottom: 10, display: 'flex', alignItems: 'center',
                gap: 10, flexWrap: 'wrap',
              }}
            >
              <span className="strong">已选 {selected.size} 条</span>
              <button
                className="btn btn-primary btn-sm"
                disabled={batchBusy}
                onClick={() => void batchSelected('READ')}
              >
                标记已读
              </button>
              <button
                className="btn btn-ghost btn-sm"
                disabled={batchBusy}
                onClick={() => void batchSelected('DELETE')}
              >
                删除
              </button>
              <button
                className="btn btn-ghost btn-sm"
                disabled={batchBusy}
                onClick={() => setSelected(new Set())}
              >
                取消选择
              </button>
            </div>
          ) : null}

          <div className="col" style={{ gap: 10 }}>
            {items.map((it) => (
              <div
                key={it.id}
                className="card card-pad"
                style={{
                  display: 'flex', gap: 10, alignItems: 'flex-start',
                  borderLeft: it.read ? undefined : '3px solid var(--brand)',
                  opacity: busyId === it.id ? 0.6 : 1,
                }}
              >
                <input
                  type="checkbox"
                  aria-label={`选择通知：${it.title}`}
                  checked={selected.has(it.id)}
                  onChange={() => toggleOne(it.id)}
                  style={{ marginTop: 4 }}
                />
                {/*
                  正文用 div + role=button 而不是 <button>：复选框不能嵌套在按钮里
                  （交互元素嵌套在 HTML 里是非法的，浏览器行为也不一致）。
                */}
                <div
                  role="button"
                  tabIndex={0}
                  style={{ flex: 1, cursor: 'pointer' }}
                  onClick={() => void open(it)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      void open(it);
                    }
                  }}
                >
                  <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                    {!it.read ? <span className="tag tag-active">未读</span> : null}
                    <span className="tag">
                      {CATEGORIES.find((c) => c.key === it.category)?.label ?? it.category}
                    </span>
                    <b>{it.title}</b>
                  </div>
                  <div className="small" style={{ marginTop: 6 }}>{it.content}</div>
                  <div className="small muted" style={{ marginTop: 6 }}>
                    {formatBjDateTime(it.createdAt)}
                    {it.webPath ? ' · 点击查看详情' : ''}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </>
      ) : null}

      {data ? (
        <Pagination page={page} pageCount={pages} total={total} onChange={setPage} />
      ) : null}
    </div>
  );
};
