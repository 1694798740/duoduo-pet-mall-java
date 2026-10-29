/**
 * 分享详情（Web 端 · 第七十一轮功能对齐 P1）。
 *
 * <p>与小程序 `pages/share-detail` 同一套数据源与交互：正文 + 商品卡 + 点赞/收藏 + **评论**。
 * 评论是「一级评论 + 回复」两级结构：一级分页加载；回复默认折叠，点「查看 N 条回复」再拉 ——
 * 一次性全量拉回复会让详情页的请求数随评论数线性增长。
 *
 * <p>写操作（评论/回复/点赞/转发）需要登录：未登录时输入框替换成登录引导，
 * 而不是让用户打完字提交时才被 401 弹回 —— 上下文全丢。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { coinsApi, shareApi, shareCommentApi } from '@duoduo/api-sdk/endpoints';
import type { ShareCommentView, ShareView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import { ShareCard, relativeTime } from '@duoduo/shared/components/share';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';

const COMMENT_PAGE = 20;

export const ShareDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { push } = useToast();
  const { user } = useAuth();
  const loggedIn = Boolean(user);

  const [busy, setBusy] = useState(false);
  const [comment, setComment] = useState('');
  const [replyTo, setReplyTo] = useState<ShareCommentView | null>(null);
  const [replies, setReplies] = useState<Record<string, ShareCommentView[]>>({});
  const [repostOpen, setRepostOpen] = useState(false);
  const [repostText, setRepostText] = useState('');
  const [tick, setTick] = useState(0);

  const detail = useAsync(
    useCallback(() => shareApi.detail(id as string), [id, tick]),
    Boolean(id),
    [id, tick],
  );
  const comments = useAsync(
    useCallback(() => shareCommentApi.list(id as string, { page: 1, pageSize: COMMENT_PAGE }), [id, tick]),
    Boolean(id),
    [id, tick],
  );

  const item = detail.data as ShareView | undefined;
  const topLevel = comments.data?.items ?? [];

  // 多多币任务：分享详情停留满 10 秒计一次「逛分享圈」（服务端按 shareId 每日去重）
  useEffect(() => {
    if (!user || !id || !item) return;
    const timer = window.setTimeout(() => {
      void coinsApi.reportTask({ taskCode: 'BROWSE_SHARE', bizId: id, dwellSeconds: 10 }).catch(() => {});
    }, 10000);
    return () => window.clearTimeout(timer);
  }, [user, id, item]);

  // 评论计数以详情返回的 commentCount 为准；发表/删除评论后刷新两个数据源
  const refresh = () => setTick((v) => v + 1);

  useEffect(() => {
    if (!detail.error) return;
    // 公开详情对不存在/已删除的条目 404：如实告知，别让人对着白屏猜
    push(toFriendlyMessage(detail.error), 'error');
  }, [detail.error, push]);

  const loadReplies = async (c: ShareCommentView) => {
    try {
      const res = await shareCommentApi.list(c.shareId, { parentId: c.id, page: 1, pageSize: 50 });
      setReplies((prev) => ({ ...prev, [c.id]: res.items }));
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const submitComment = async () => {
    const text = comment.trim();
    if (!text) return;
    setBusy(true);
    try {
      await shareCommentApi.create(id as string, text, replyTo?.id ?? null);
      setComment('');
      setReplyTo(null);
      refresh();
      push(replyTo ? '回复成功' : '评论成功', 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  const likeComment = async (c: ShareCommentView) => {
    try {
      await shareCommentApi.toggleLike(c.id);
      // 统一回源刷新而不是本地改 useAsync 的 data 对象 —— 那不会触发渲染
      refresh();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const repost = async () => {
    setBusy(true);
    try {
      await shareApi.repost(id as string, repostText.trim() || null);
      setRepostOpen(false);
      setRepostText('');
      push('转发成功，已发布到分享圈', 'success');
      navigate('/share');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (detail.loading && !item) return <LoadingBlock text="正在加载分享…" />;
  if (detail.error) {
    return (
      <div>
        <Alert kind="error">{detail.error}</Alert>
        <div style={{ marginTop: 12 }}>
          <Link className="btn btn-ghost btn-sm" to="/share">← 返回分享圈</Link>
        </div>
      </div>
    );
  }
  if (!item) return null;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">分享详情</h2>
        <Link className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} to="/share">← 返回分享圈</Link>
      </div>

      <div style={{ marginBottom: 14 }}>
        <ShareCard
          item={item}
          detailed
          busy={busy}
          onLike={loggedIn ? async () => {
            try {
              await shareApi.toggleLike(item.id);
              refresh();
            } catch (err) { push(toFriendlyMessage(err), 'error'); }
          } : undefined}
          onFavorite={loggedIn ? async () => {
            try {
              await shareApi.toggleFavorite(item.id);
              refresh();
            } catch (err) { push(toFriendlyMessage(err), 'error'); }
          } : undefined}
          onRepost={loggedIn ? () => setRepostOpen(true) : undefined}
          onComment={() => { /* 评论输入框就在下方 */ }}
        />
      </div>

      {/* ── 评论 ── */}
      <h3 style={{ margin: '16px 0 10px' }}>评论（{item.commentCount}）</h3>

      {loggedIn ? (
        <div className="card card-pad" style={{ marginBottom: 12 }}>
          {replyTo ? (
            <div className="small muted" style={{ marginBottom: 6 }}>
              回复 @{replyTo.nickname}
              <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={() => setReplyTo(null)}>
                取消回复
              </button>
            </div>
          ) : null}
          <textarea
            className="input"
            rows={2}
            maxLength={300}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder={replyTo ? `回复 @${replyTo.nickname}…` : '写下你的评论…'}
          />
          <div className="row" style={{ gap: 8, marginTop: 8, justifyContent: 'flex-end' }}>
            <span className="small muted" style={{ marginRight: 'auto' }}>{comment.length}/300</span>
            <button className="btn btn-primary btn-sm" disabled={busy || !comment.trim()} onClick={submitComment}>
              {replyTo ? '回复' : '发表评论'}
            </button>
          </div>
        </div>
      ) : (
        <div className="card card-pad" style={{ marginBottom: 12 }}>
          <Link className="btn btn-primary btn-sm" to="/login?next=%2Fshare">登录后参与评论</Link>
        </div>
      )}

      {comments.loading && !comments.data ? <LoadingBlock text="正在加载评论…" /> : null}

      {!comments.loading && topLevel.length === 0 ? (
        <Empty icon="💬" title="还没有评论" desc="来抢第一条" />
      ) : null}

      <div className="col" style={{ gap: 10 }}>
        {topLevel.map((c) => (
          <div className="card card-pad" key={c.id}>
            <div className="row" style={{ gap: 8, alignItems: 'center' }}>
              <b className="small">{c.nickname}</b>
              {c.mine ? <span className="tag">我</span> : null}
              <span className="small muted">{relativeTime(c.createdAt)}</span>
            </div>
            <div className="small" style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{c.content}</div>
            <div className="row" style={{ gap: 12, marginTop: 6 }}>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => (loggedIn ? void likeComment(c) : navigate('/login?next=' + encodeURIComponent('/share/' + id)))}
              >
                {c.liked ? '❤️' : '🤍'} {c.likeCount}
              </button>
              {loggedIn ? (
                <button className="btn btn-ghost btn-sm" onClick={() => { setReplyTo(c); setComment(''); }}>
                  回复
                </button>
              ) : null}
              {c.replyCount > 0 ? (
                <button className="btn btn-ghost btn-sm" onClick={() => void loadReplies(c)}>
                  查看 {c.replyCount} 条回复
                </button>
              ) : null}
            </div>
            {(replies[c.id] ?? []).length ? (
              <div style={{ marginTop: 8, paddingLeft: 12, borderLeft: '2px solid var(--surface-3)' }}>
                {(replies[c.id] ?? []).map((r) => (
                  <div key={r.id} style={{ marginBottom: 8 }}>
                    <div className="row" style={{ gap: 6, alignItems: 'center' }}>
                      <b className="small">{r.nickname}</b>
                      {r.replyToNickname ? <span className="small muted">回复 @{r.replyToNickname}</span> : null}
                      <span className="small muted">{relativeTime(r.createdAt)}</span>
                    </div>
                    <div className="small" style={{ marginTop: 2, whiteSpace: 'pre-wrap' }}>{r.content}</div>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ))}
      </div>

      <Modal open={repostOpen} title="转发到分享圈" onClose={() => setRepostOpen(false)}>
        <textarea
          className="input"
          rows={3}
          maxLength={500}
          value={repostText}
          onChange={(e) => setRepostText(e.target.value)}
          placeholder="附言（可选，最多 500 字）"
        />
        <div className="small muted" style={{ marginTop: 8 }}>
          转发会生成一条新分享，原帖以引用块展示；原帖被作者删除后引用块会显示「原内容已被作者删除」。
        </div>
        <div className="row" style={{ gap: 8, marginTop: 12, justifyContent: 'flex-end' }}>
          <button className="btn btn-ghost btn-sm" onClick={() => setRepostOpen(false)}>取消</button>
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={repost}>转发</button>
        </div>
      </Modal>
    </div>
  );
};
