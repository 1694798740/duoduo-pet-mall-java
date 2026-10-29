/**
 * 铃铛未读弹窗（2026-10-27）。
 *
 * <h2>为什么不是「点头像看消息」</h2>
 * 头像菜单回答的是「我是谁、我能去哪」，把未读通知塞进去，用户要点两层才能看到
 * 「谁找我了」；而铃铛是消息系统的通用隐喻 —— 一眼就知道那里有新东西。
 *
 * <h2>三条口径</h2>
 * <ul>
 *   <li><b>只拉未读</b>（`read=UNREAD,pageSize=5`）：弹窗的语义是「还没看的」，
 *       混进已读只会让用户以为还有事没处理；</li>
 *   <li><b>点开即已读并立刻刷新顶栏</b>：不等轮询，否则用户点完回来数字还在；</li>
 *   <li><b>数字来自 `unread-summary`</b>（父组件传入），弹窗自己不另算一遍 ——
 *       两处各算一次，迟早出现「铃铛 3、弹窗里 5 条」。</li>
 * </ul>
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { noticeApi } from '@duoduo/api-sdk/endpoints';
import type { NoticeView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useToast } from '@duoduo/shared/components/ui';
import { Badge } from '@duoduo/shared/components/badge';
import { formatBjShort } from '@duoduo/shared/lib/time';

export const BellPopover: React.FC<{
  /** 合并未读总数（通知 + 私信 + 客服），来自全局 store */
  total: number;
  /** 点开某条后通知父级刷新角标（父级持有唯一数据源） */
  onChanged: () => void;
}> = ({ total, onChanged }) => {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<NoticeView[]>([]);
  const [loading, setLoading] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { push } = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await noticeApi.list({ read: 'UNREAD', pageSize: 5 });
      setItems(page.items ?? []);
    } catch {
      // 弹窗是「顺便看一眼」的场景：拉取失败就保持空列表，不弹错误打断用户
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  /**
   * 点外部关闭。
   *
   * <p>用 `mousedown` 而不是 `click`：按下与抬起之间鼠标移出面板时，
   * click 会在外部元素上触发 —— 表现为「想选中文字却把面板关了」。
   */
  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  /** Esc 关闭（键盘用户） */
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  const openItem = async (it: NoticeView) => {
    try {
      if (!it.read) await noticeApi.markRead(it.id);
      onChanged();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
    setOpen(false);
    // webPath 为空 = 网页端没有对应详情页（纯公告等）——去通知中心，而不是跳 404
    navigate(it.webPath || '/notifications');
  };

  return (
    <div className="bell-wrap" ref={boxRef}>
      <button
        type="button"
        className="icon-btn bell-btn"
        aria-label={total > 0 ? `通知：${total} 条未读` : '通知：暂无未读'}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((v) => !v)}
      >
        <span aria-hidden>🔔</span>
        <Badge count={total} />
      </button>

      {open ? (
        <div className="bell-popover" role="dialog" aria-label="未读消息">
          <div className="bell-head">
            <strong>未读消息{total > 0 ? `（${total}）` : ''}</strong>
          </div>

          {loading ? (
            <div className="small muted" style={{ padding: '14px 12px' }}>正在加载…</div>
          ) : items.length === 0 ? (
            <div className="small muted" style={{ padding: '14px 12px' }}>
              暂无未读消息，都看完啦
            </div>
          ) : (
            <ul className="bell-list">
              {items.map((it) => (
                <li key={it.id}>
                  <button type="button" className="bell-item" onClick={() => void openItem(it)}>
                    <span className="bell-item-title">{it.title}</span>
                    <span className="bell-item-time">{formatBjShort(it.createdAt)}</span>
                    <span className="bell-item-body">{it.content}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="bell-foot">
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setOpen(false);
                navigate('/notifications');
              }}
            >
              查看更多
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
};
