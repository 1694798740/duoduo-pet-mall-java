import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Modal } from '@duoduo/shared/components/ui';

/**
 * 「问客服」统一入口（2026-10-18）。
 *
 * <h2>为什么先只做入口、不做会话</h2>
 * 客服会话涉及「谁接待、谁负责、消息归属」等尚未拍板的设计问题（见工作区
 * 《问客服-直连平台消息方案评估-2026-10-18.md》）。在规则定下来之前，做一个
 * 能点开、会说话的假客服，比没有入口更糟 —— 用户会以为有人在线，然后一直等。
 *
 * <p>所以这里的原则是：**入口真实存在、状态诚实可见**。点开明确告知"暂未开放人工接待"，
 * 并给出当下可用的替代路径（AI 导购），不伪造在线人数、不自动回复"亲"。
 */
export const SupportEntry: React.FC<{ small?: boolean }> = ({ small }) => {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className={`btn btn-ghost${small ? ' btn-sm' : ''}`}
        onClick={() => setOpen(true)}
      >
        问客服
      </button>
      <Modal
        open={open}
        title="在线客服"
        onClose={() => setOpen(false)}
        footer={
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
              知道了
            </button>
            <Link className="btn btn-primary" to="/ai" onClick={() => setOpen(false)}>
              去问 AI 导购
            </Link>
          </div>
        }
      >
        <p>人工客服正在建设中，暂未开放在线接待。</p>
        <p className="small muted" style={{ marginTop: 8 }}>
          商品参数、规格选择、订单进度这类问题，可以先问 AI 导购（7×24 小时在线）。
          需要人工处理时，请在订单备注里留言，运营会在工作时间查看。
        </p>
      </Modal>
    </>
  );
};
