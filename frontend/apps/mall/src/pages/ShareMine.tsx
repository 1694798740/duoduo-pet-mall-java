/**
 * 我的分享（Web 端 · 第七十一轮功能对齐 P1）。
 *
 * <p>与小程序 `pages/share-mine` 同一套数据源（`/api/shares/mine/list`，服务端按登录态过滤）。
 * 编辑**只能改文字与话题、不能改商品**（后端口径：商品是快照，换商品等于删掉重发）；
 * 话题的三个取值必须区分：不改（null）/ 清空（[]）/ 替换（非空）——
 * 表单里「不动话题输入」就是不改，清空了再提交就是清空。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { shareApi } from '@duoduo/api-sdk/endpoints';
import type { ShareView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import { ShareCard } from '@duoduo/shared/components/share';
import { useAsync } from '@duoduo/shared/components/hooks';

const PAGE_SIZE = 10;
const MAX_TOPICS = 2;

export const ShareMinePage: React.FC = () => {
  const { push } = useToast();
  const [page, setPage] = useState(1);
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState(false);

  // ── 编辑弹窗 ──
  const [editing, setEditing] = useState<ShareView | null>(null);
  const [content, setContent] = useState('');
  const [topicDraft, setTopicDraft] = useState('');
  const [topics, setTopics] = useState<string[]>([]);

  const fetcher = useCallback(
    () => shareApi.mine({ page, pageSize: PAGE_SIZE }),
    [page, tick],
  );
  const { data, loading, error } = useAsync(fetcher, true, [page, tick]);

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  /** 打开编辑弹窗时回填一次；之后用户的输入不被覆盖 */
  useEffect(() => {
    if (!editing) return;
    setContent(editing.content ?? '');
    setTopics(editing.topics ?? []);
    setTopicDraft('');
  }, [editing]);

  const openEdit = (it: ShareView) => setEditing(it);

  const save = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      // topics 原样传（哪怕空数组）：用户清空了话题就该被清空，null 才表示「不改」
      await shareApi.edit(editing.id, { content: content.trim() || null, topics });
      push('已保存（内容变更在分享圈即时可见）', 'success');
      setEditing(null);
      setTick((v) => v + 1);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (it: ShareView) => {
    setBusy(true);
    try {
      await shareApi.remove(it.id);
      push('已删除', 'success');
      setTick((v) => v + 1);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  const addTopic = () => {
    const t = topicDraft.trim().replace(/^#/, '');
    if (!t || topics.includes(t)) {
      setTopicDraft('');
      return;
    }
    if (topics.length >= MAX_TOPICS) {
      push(`最多 ${MAX_TOPICS} 个话题`, 'error');
      return;
    }
    setTopics([...topics, t]);
    setTopicDraft('');
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的分享</h2>
        <span className="section-sub">{total} 条</span>
        <Link className="btn btn-primary btn-sm" style={{ marginLeft: 'auto' }} to="/share">← 返回分享圈</Link>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !data ? <LoadingBlock text="正在加载…" /> : null}

      {!loading && items.length === 0 ? (
        <Empty
          icon="📝"
          title="还没有发布过分享"
          desc="分享圈的内容锚点是「对某件商品的评价」"
          action={<Link className="btn btn-primary" to="/share">去发布</Link>}
        />
      ) : null}

      <div className="col" style={{ gap: 12 }}>
        {items.map((it) => (
          <div key={it.id} style={{ position: 'relative' }}>
            <ShareCard item={it} detailed busy={busy} />
            <div className="row" style={{ gap: 8, marginTop: 8 }}>
              <button className="btn btn-ghost btn-sm" onClick={() => openEdit(it)}>编辑</button>
              <button
                className="btn btn-ghost btn-sm"
                style={{ color: 'var(--danger)' }}
                disabled={busy}
                onClick={() => { if (window.confirm(`删除这条分享？（软删：别人转发单的引用块会显示「原内容已被作者删除」）`)) void remove(it); }}
              >
                删除
              </button>
            </div>
          </div>
        ))}
      </div>

      {pages > 1 ? (
        <div className="row" style={{ gap: 10, justifyContent: 'center', marginTop: 16 }}>
          <button className="btn btn-ghost btn-sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>上一页</button>
          <span className="small muted">{page} / {pages}</span>
          <button className="btn btn-ghost btn-sm" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>下一页</button>
        </div>
      ) : null}

      <Modal open={Boolean(editing)} title="编辑分享" onClose={() => setEditing(null)}>
        <div className="small muted" style={{ marginBottom: 8 }}>
          商品：{editing?.productTitle}（**不能改** —— 商品是快照，换商品请删掉重发）
        </div>
        <textarea
          className="input"
          rows={4}
          maxLength={500}
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder="分享内容（最多 500 字）"
        />
        <div className="row row-wrap" style={{ gap: 6, alignItems: 'center', marginTop: 8 }}>
          {topics.map((t) => (
            <span key={t} className="tag tag-active">
              #{t}
              <button
                style={{ marginLeft: 4, cursor: 'pointer' }}
                onClick={() => setTopics(topics.filter((x) => x !== t))}
                aria-label={`移除话题 ${t}`}
              >×</button>
            </span>
          ))}
          {topics.length < MAX_TOPICS ? (
            <input
              className="input"
              style={{ width: 150, padding: '4px 8px' }}
              value={topicDraft}
              maxLength={20}
              onChange={(e) => setTopicDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addTopic(); } }}
              placeholder="回车添加话题"
            />
          ) : null}
        </div>
        <div className="row" style={{ gap: 8, marginTop: 12, justifyContent: 'flex-end' }}>
          <button className="btn btn-ghost btn-sm" onClick={() => setEditing(null)}>取消</button>
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={save}>
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </Modal>
    </div>
  );
};
