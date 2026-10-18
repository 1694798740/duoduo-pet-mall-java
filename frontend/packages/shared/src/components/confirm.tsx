import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { Modal } from './ui';

/**
 * 全局确认 / 提示弹窗（2026-10-18）。
 *
 * <h2>为什么不再用 window.confirm / alert</h2>
 * 浏览器原生弹窗有三个无法回避的问题：
 *   · 样式不可控 —— 与站内卡片、按钮、圆角、主色完全两张皮（截图对比非常明显）；
 *   · 阻塞主线程 —— 弹窗期间页面动画、轮询全部停摆；
 *   · 文案结构受限 —— 只能一段纯文本，放不下「标题 + 说明 + 危险操作色」这套信息层级。
 *
 * <h2>行为等价</h2>
 * confirm 返回 Promise&lt;boolean&gt;：确定 → true，取消 / 关闭 / Esc → false；
 * alert 返回 Promise&lt;void&gt;，只保证「用户看到了并关闭」。调用方写法与 window.confirm 一一对应：
 *
 * <pre>
 *   if (!(await confirm({ title: '取消订单', message: '…' }))) return;
 * </pre>
 *
 * <p>弹窗本体复用站内 {@link Modal}：同一张白卡、同一个右上角关闭按钮、同一套 footer 按钮，
 * 因此与「在线客服」等弹窗的视觉完全一致。
 */
export interface ConfirmOptions {
  title?: string;
  message: React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  /** 危险操作（删除 / 取消订单）：确定按钮用红色 */
  danger?: boolean;
}

export interface AlertOptions {
  title?: string;
  message: React.ReactNode;
  okText?: string;
}

type DialogState =
  | ({ mode: 'confirm' } & ConfirmOptions)
  | ({ mode: 'alert' } & AlertOptions);

interface DialogApi {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
  alert: (options: AlertOptions) => Promise<void>;
}

const DialogContext = createContext<DialogApi | null>(null);

export const ConfirmProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [state, setState] = useState<DialogState | null>(null);
  const resolver = useRef<((confirmed: boolean) => void) | null>(null);

  const open = useCallback((next: DialogState) => {
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
      setState(next);
    });
  }, []);

  const close = useCallback((confirmed: boolean) => {
    setState(null);
    const resolve = resolver.current;
    resolver.current = null;
    resolve?.(confirmed);
  }, []);

  const api = useMemo<DialogApi>(
    () => ({
      confirm: (options) => open({ mode: 'confirm', ...options }),
      alert: async (options) => {
        await open({ mode: 'alert', ...options });
      },
    }),
    [open],
  );

  return (
    <DialogContext.Provider value={api}>
      {children}
      <Modal
        open={state !== null}
        title={state?.title ?? (state?.mode === 'alert' ? '提示' : '请确认')}
        onClose={() => close(false)}
        footer={
          state ? (
            <div className="row" style={{ gap: 10, justifyContent: 'flex-end' }}>
              {state.mode === 'confirm' ? (
                <button type="button" className="btn btn-ghost" onClick={() => close(false)}>
                  {state.cancelText ?? '取消'}
                </button>
              ) : null}
              <button
                type="button"
                className={`btn ${state.mode === 'confirm' && state.danger ? 'btn-danger' : 'btn-primary'}`}
                onClick={() => close(true)}
              >
                {state.mode === 'confirm' ? state.confirmText ?? '确定' : state.okText ?? '知道了'}
              </button>
            </div>
          ) : null
        }
      >
        <div style={{ fontSize: 15, lineHeight: 1.75, whiteSpace: 'pre-wrap' }}>{state?.message}</div>
      </Modal>
    </DialogContext.Provider>
  );
};

function useDialogApi(): DialogApi {
  const ctx = useContext(DialogContext);
  if (!ctx) {
    // 忘记挂 Provider 时，报错比静默退化成 window.confirm 更好 —— 后者会让样式问题在下一次截图中重现
    throw new Error('ConfirmProvider 未挂载：请在应用入口包一层 <ConfirmProvider>');
  }
  return ctx;
}

/** 确认弹窗：确定 → true；取消 / 关闭 / Esc → false */
export function useConfirm(): (options: ConfirmOptions) => Promise<boolean> {
  return useDialogApi().confirm;
}

/** 提示弹窗：只有「知道了」一个按钮 */
export function useAlert(): (options: AlertOptions) => Promise<void> {
  return useDialogApi().alert;
}
