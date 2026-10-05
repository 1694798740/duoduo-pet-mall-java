/**
 * 关注与粉丝（Web 端 · 第六十七轮功能对齐）。
 *
 * <p>与小程序 `pages/follow-list` 同一套数据源（duoduo-social `/api/socials/follows/**`）。
 * 一个页面两个页签（我关注的 / 关注我的），互关标记与小程序一致 ——
 * 拆成两个路由只会让「从个人中心进来」多一跳，没有信息增量。
 */

import React, { useCallback, useState } from 'react';
import { followApi } from '../api/endpoints';
import type { FollowView } from '../api/types';
import { toFriendlyMessage } from '../api/client';
import { Alert, Empty, LoadingBlock, useToast } from '../components/ui';
import { useAsync } from '../components/hooks';

const PAGE_SIZE = 20;

type Tab = 'following' | 'followers';

export const FollowPage: React.FC = () => {
  const { push } = useToast();
  const [tab, setTab] = useState<Tab>('following');
  const [page, setPage] = useState(1);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const fetcher = useCallback(
    () =>
      tab === 'following'
        ? followApi.following({ page, pageSize: PAGE_SIZE })
        : followApi.followers({ page, pageSize: PAGE_SIZE }),
    [tab, page, tick],
  );
  const { data, loading, error } = useAsync(fetcher, true, [tab, page, tick]);

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  /** 取关只出现在「我关注的」页签里；粉丝列表里不做反向关注快捷键，
   *  因为「关注一个人」应当来自看到 TA 的主页/内容，而不是在粉丝列表里顺手点 ——
   *  那会让「互相关注」变成无意识操作。 */
  const unfollow = async (userId: string, nickname: string) => {
    setBusyId(userId);
    try {
      await followApi.unfollow(userId);
      push(`已取消关注 ${nickname}`, 'success');
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
        <h2 className="section-title">关注与粉丝</h2>
        <span className="section-sub">{total} 人</span>
      </div>

      <div className="row row-wrap" style={{ gap: 8, marginBottom: 14 }}>
        <button
          className={`btn btn-sm ${tab === 'following' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => {
            setTab('following');
            setPage(1);
          }}
        >
          我关注的
        </button>
        <button
          className={`btn btn-sm ${tab === 'followers' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => {
            setTab('followers');
            setPage(1);
          }}
        >
          关注我的
        </button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !data ? <LoadingBlock text="正在加载…" /> : null}

      {!loading && items.length === 0 ? (
        <Empty
          icon="👥"
          title={tab === 'following' ? '还没有关注的人' : '还没有粉丝'}
          desc="在分享圈里看到有趣的人，可以关注 TA"
        />
      ) : null}

      <div className="col" style={{ gap: 10 }}>
        {items.map((it: FollowView) => (
          <div className="card card-pad" key={it.userId}>
            <div className="row" style={{ gap: 12, alignItems: 'center' }}>
              <div
                style={{
                  width: 40, height: 40, borderRadius: '50%', flex: 'none',
                  background: 'var(--surface-2)', display: 'grid', placeItems: 'center',
                  overflow: 'hidden',
                }}
              >
                {it.avatar ? (
                  <img src={it.avatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                ) : (
                  <span>🐾</span>
                )}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="row" style={{ gap: 8 }}>
                  <b>{it.nickname || `用户 ${it.userId}`}</b>
                  {it.mutual ? <span className="tag tag-success">互相关注</span> : null}
                </div>
                <div className="small muted">{it.followedAt?.replace('T', ' ').slice(0, 10)} 关注</div>
              </div>
              {tab === 'following' ? (
                <button
                  className="btn btn-ghost btn-sm"
                  disabled={busyId === it.userId}
                  onClick={() => void unfollow(it.userId, it.nickname || it.userId)}
                >
                  已关注
                </button>
              ) : null}
            </div>
          </div>
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
