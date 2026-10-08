/**
 * 分享圈（Web 端 · 第七十一轮功能对齐 P1）。
 *
 * <p>与小程序 `pages/share-circle` 同一套数据源与交互逻辑：
 * <ul>
 *   <li>信息流公开可逛（匿名可看，互动标记恒 false）；scope = 推荐（全站）/ 关注（需登录）；</li>
 *   <li>发布**必须选一件商品**（后端 `spuId` @NotNull）—— 分享圈的内容锚点是「对某件商品的评价」；</li>
 *   <li>话题最多 2 个，输入后回车成 tag（与后端 `MAX_TOPICS` 对齐）；</li>
 *   <li>话题聚合走独立路由（`/share/topic/:name`），本页通过路由参数复用同一套渲染；</li>
 *   <li>信息流翻页用**追加**而不是替换：信息流是逛的，翻页跳回顶部体验差。</li>
 * </ul>
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { productApi, shareApi } from '@duoduo/api-sdk/endpoints';
import type { ProductCard, ShareTopicView, ShareView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { productImage } from '@duoduo/api-sdk/images';
import { Alert, Empty, LoadingBlock, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { ShareCard } from '@duoduo/shared/components/share';
import { useAsync } from '@duoduo/shared/components/hooks';

const PAGE_SIZE = 10;
const MAX_TOPICS = 2;
const MAX_CONTENT = 500;

export const ShareCirclePage: React.FC = () => {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const { push } = useToast();

  const [tab, setTab] = useState<'RECOMMEND' | 'FOLLOWING'>('RECOMMEND');
  const [page, setPage] = useState(1);
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState(false);

  // ── 发布表单状态 ──
  const [composing, setComposing] = useState(false);
  const [kw, setKw] = useState('');
  const [picked, setPicked] = useState<ProductCard | null>(null);
  const [content, setContent] = useState('');
  const [topicDraft, setTopicDraft] = useState('');
  const [topics, setTopics] = useState<string[]>([]);
  const [formError, setFormError] = useState<string | null>(null);

  const feedFetcher = useCallback(
    () =>
      name
        ? shareApi.topicFeed(name, { page, pageSize: PAGE_SIZE })
        : shareApi.feed({ page, pageSize: PAGE_SIZE, scope: tab }),
    [name, tab, page, tick],
  );
  const { data, loading, error } = useAsync(feedFetcher, true, [name, tab, page, tick]);
  const topicsFetcher = useCallback(() => shareApi.hotTopics(8), []);
  const hot = useAsync(topicsFetcher, true, [tick]);

  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  /** 追加式加载：第 1 页整体替换，后续页去重追加。
   *  ★ 依赖必须是 data 而不是 items —— items 是每次渲染新建的数组引用，
   *    放进依赖会让 effect 无限触发（Maximum update depth exceeded）。 */
  const [list, setList] = useState<ShareView[]>([]);
  useEffect(() => {
    const items = data?.items ?? [];
    if (page === 1) {
      setList(items);
      return;
    }
    setList((prev) => {
      const seen = new Set(prev.map((p) => p.id));
      return [...prev, ...items.filter((it) => !seen.has(it.id))];
    });
  }, [data, page]);

  const searchProducts = useCallback(
    (signal: AbortSignal) => productApi.search({ keyword: kw || undefined, page: 1, pageSize: 6, signal }),
    [kw],
  );
  const productSearch = useAsync(searchProducts, composing && !picked, [kw, composing, picked]);

  const addTopic = () => {
    const t = topicDraft.trim().replace(/^#/, '');
    if (!t) return;
    if (topics.includes(t)) {
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

  const publish = async () => {
    if (!picked) {
      setFormError('请先选择要分享的商品');
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await shareApi.create({
        spuId: picked.id,
        content: content.trim() || null,
        topics: topics.length ? topics : null,
      });
      push('发布成功', 'success');
      setComposing(false);
      setPicked(null);
      setContent('');
      setTopics([]);
      setKw('');
      setPage(1);
      setTab('RECOMMEND');
      setTick((v) => v + 1);
    } catch (err) {
      setFormError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  type EngagementPatch = Partial<Pick<ShareView, 'liked' | 'likeCount' | 'favorited' | 'favoriteCount'>>;

  /** 点赞/收藏：用 toggle 接口返回的新状态乐观更新本地列表（失败回拉整页纠偏） */
  const toggle = async (id: string, kind: 'like' | 'favorite') => {
    setBusy(true);
    const patch = (s: EngagementPatch) =>
      setList((prev) => prev.map((it) => (it.id === id ? { ...it, ...s } : it)));
    try {
      if (kind === 'like') {
        const st = await shareApi.toggleLike(id);
        patch({ liked: st.active, likeCount: st.count });
      } else {
        const st = await shareApi.toggleFavorite(id);
        patch({ favorited: st.active, favoriteCount: st.count });
      }
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
      setTick((v) => v + 1);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">{name ? `#${name}` : '分享圈'}</h2>
        <span className="section-sub">{total} 条分享 · 逛分享圈不需要登录，互动才需要</span>
        <div className="row" style={{ gap: 8, marginLeft: 'auto' }}>
          {!name ? (
            <>
              <button
                className={`btn btn-sm ${tab === 'RECOMMEND' ? 'btn-primary' : 'btn-ghost'}`}
                onClick={() => { setTab('RECOMMEND'); setPage(1); }}
              >
                推荐
              </button>
              <button
                className={`btn btn-sm ${tab === 'FOLLOWING' ? 'btn-primary' : 'btn-ghost'}`}
                onClick={() => { setTab('FOLLOWING'); setPage(1); }}
              >
                关注
              </button>
            </>
          ) : (
            <Link className="btn btn-ghost btn-sm" to="/share">← 返回分享圈</Link>
          )}
          <button className="btn btn-primary btn-sm" onClick={() => setComposing((v) => !v)}>
            {composing ? '收起' : '+ 发布分享'}
          </button>
        </div>
      </div>

      {/* 热门话题 */}
      <div className="row row-wrap" style={{ gap: 6, marginBottom: 14 }}>
        {(hot.data ?? []).map((tp: ShareTopicView) => (
          <Link key={tp.name} className="tag" to={`/share/topic/${encodeURIComponent(tp.name)}`}>
            #{tp.name} · {tp.shareCount}
          </Link>
        ))}
      </div>

      {/* 发布表单（折叠） */}
      {composing ? (
        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <h3 style={{ marginBottom: 10 }}>发布分享</h3>
          {formError ? <div style={{ marginBottom: 10 }}><Alert kind="error">{formError}</Alert></div> : null}

          <div style={{ marginBottom: 10 }}>
            <div className="small muted" style={{ marginBottom: 4 }}>① 选择商品（必选）</div>
            {picked ? (
              <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                <img
                  src={productImage(picked.id, picked.mainImage)}
                  alt="" style={{ width: 44, height: 44, borderRadius: 8, objectFit: 'cover' }}
                />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="small">{picked.title}</div>
                  <div className="price small">{formatPrice(picked.minPrice)}</div>
                </div>
                <button className="btn btn-ghost btn-sm" onClick={() => setPicked(null)}>重选</button>
              </div>
            ) : (
              <>
                <input
                  className="input"
                  placeholder="输入商品关键词搜索，如：幼犬粮"
                  value={kw}
                  onChange={(e) => setKw(e.target.value)}
                />
                {kw.trim() ? (
                  <div style={{ marginTop: 8 }}>
                    {productSearch.loading ? <span className="small muted">搜索中…</span> : null}
                    {(productSearch.data?.items ?? []).map((p) => (
                      <button
                        key={p.id}
                        className="card"
                        style={{
                          display: 'flex', gap: 8, padding: 6, width: '100%', marginBottom: 6,
                          alignItems: 'center', cursor: 'pointer', textAlign: 'left',
                        }}
                        onClick={() => { setPicked(p); setKw(''); }}
                      >
                        <img
                          src={productImage(p.id, p.mainImage)}
                          alt="" style={{ width: 36, height: 36, borderRadius: 6, objectFit: 'cover' }}
                        />
                        <span className="small" style={{ flex: 1, minWidth: 0 }}>{p.title}</span>
                        <span className="price small">{formatPrice(p.minPrice)}</span>
                      </button>
                    ))}
                    {!productSearch.loading && (productSearch.data?.items?.length ?? 0) === 0 ? (
                      <span className="small muted">没有匹配的商品，换个关键词试试</span>
                    ) : null}
                  </div>
                ) : null}
              </>
            )}
          </div>

          <div style={{ marginBottom: 10 }}>
            <div className="small muted" style={{ marginBottom: 4 }}>
              ② 说说你的使用感受（{content.length}/{MAX_CONTENT}）
            </div>
            <textarea
              className="input"
              rows={3}
              maxLength={MAX_CONTENT}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="是否值得买、适不适合你家宠物…"
            />
          </div>

          <div style={{ marginBottom: 12 }}>
            <div className="small muted" style={{ marginBottom: 4 }}>
              ③ 话题（最多 {MAX_TOPICS} 个，回车添加）
            </div>
            <div className="row row-wrap" style={{ gap: 6, alignItems: 'center' }}>
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
              <input
                className="input"
                style={{ width: 160, padding: '4px 8px' }}
                value={topicDraft}
                maxLength={20}
                onChange={(e) => setTopicDraft(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addTopic(); } }}
                placeholder="输入话题后回车"
              />
            </div>
          </div>

          <div className="row" style={{ gap: 8 }}>
            <button className="btn btn-primary btn-sm" disabled={busy} onClick={publish}>
              {busy ? '发布中…' : '发布'}
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setComposing(false)}>取消</button>
          </div>
        </div>
      ) : null}

      {error && !composing ? <Alert kind="error">{error}</Alert> : null}
      {loading && page === 1 ? <LoadingBlock text="正在加载分享…" /> : null}

      {!loading && list.length === 0 ? (
        <Empty
          icon="📣"
          title={tab === 'FOLLOWING' ? '关注的人还没有分享' : '还没有分享'}
          desc="把买到的好物分享出来，帮别人少踩坑"
          action={<button className="btn btn-primary" onClick={() => setComposing(true)}>发布第一篇分享</button>}
        />
      ) : null}

      <div className="col" style={{ gap: 12 }}>
        {list.map((it) => (
          <ShareCard
            key={it.id}
            item={it}
            busy={busy}
            onLike={() => void toggle(it.id, 'like')}
            onFavorite={() => void toggle(it.id, 'favorite')}
            onComment={() => navigate(`/share/${it.id}`)}
            onRepost={() => navigate(`/share/${it.id}`)}
          />
        ))}
      </div>

      {list.length > 0 && page < pages ? (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <button className="btn btn-ghost btn-sm" disabled={loading} onClick={() => setPage((p) => p + 1)}>
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      ) : null}
    </div>
  );
};
