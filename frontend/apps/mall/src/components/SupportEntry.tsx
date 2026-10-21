import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { supportApi } from '@duoduo/api-sdk/endpoints';
import { Modal } from '@duoduo/shared/components/ui';

/**
 * 「问客服」统一入口。
 *
 * <h2>从前为什么是个空弹窗</h2>
 * 2026-10-18 那版只做了入口、点开告知「人工客服正在建设中」。原因写在这里备查：
 * 当时客服会话缺「谁接待、谁负责、消息归属」的设计口径，做一个能点开却没人接的假客服，
 * 比没有入口更糟 —— 用户会以为有人在线，然后一直等。
 *
 * <h2>现在为什么可以直连了</h2>
 * 口径定成「**不新造接待系统，直接把用户送进和官方客服账号的私信会话**」：
 *  - 收件人由后端下发（`GET /api/socials/support/agent`），前端不硬编码 userId，
 *    换客服账号只改服务端配置 `duoduo.social.official-cs-user-id`；
 *  - 走既有私信链路（`/messages/:peerId` → `/api/socials/messages/**`），
 *    未读、限发、上下文隔离这些规则全都复用，不产生第二套消息模型；
 *  - 客服账号同时是自营商品的咨询收件人，用户从商品页问和从这里问，进的是同一个会话。
 *
 * <p>**失败时必须诚实**：拿不到客服账号（服务端没配、网络断了）不能假装进去了，
 * 退回说明弹窗并给出当下真实可用的替代路径（AI 导购 7×24 在线）。
 */
export const SupportEntry: React.FC<{ small?: boolean }> = ({ small }) => {
  const navigate = useNavigate();
  const [fallbackOpen, setFallbackOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const open = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const agent = await supportApi.agent();
      const peerId = agent?.userId;
      if (peerId) {
        // 必须登录才能进会话；未登录时由路由守卫接手（/messages 是受保护页）
        navigate(`/messages/${peerId}`);
        return;
      }
      setFallbackOpen(true);
    } catch {
      setFallbackOpen(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className={`btn btn-ghost${small ? ' btn-sm' : ''}`}
        onClick={open}
        disabled={busy}
        title="与官方客服私信沟通"
      >
        {busy ? '正在接通…' : '问客服'}
      </button>
      <Modal
        open={fallbackOpen}
        title="客服暂时没接通"
        onClose={() => setFallbackOpen(false)}
        footer={
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button type="button" className="btn btn-ghost" onClick={() => setFallbackOpen(false)}>
              知道了
            </button>
            <Link className="btn btn-primary" to="/ai" onClick={() => setFallbackOpen(false)}>
              去问 AI 导购
            </Link>
          </div>
        }
      >
        <p>没能取到客服账号，可能是网络不稳定或客服尚未配置。</p>
        <p className="small muted" style={{ marginTop: 8 }}>
          商品参数、规格选择、订单进度这类问题，可以先问 AI 导购（7×24 小时在线）；
          需要人工处理时，也可以在「私信」里直接给平台客服留言，我们会在工作时间回复。
        </p>
      </Modal>
    </>
  );
};
