/**
 * 账号安全 · 两步验证面板（E21 / F-05）。
 *
 * <p>平台后台与商家工作台共用同一份实现：后端接口本来就是 `/api/auth/2fa/**` 自助管理，
 * 两端的差别只有「为什么建议开启」的一句文案 —— 复制一份组件会让两边的流程
 * （取密钥 → 验证 → 保存恢复码 → 关闭）慢慢漂移，而漂移的那一端往往正是出问题的一端。
 *
 * <p><b>不引入二维码库</b>：密钥用等宽字体分组展示 + 一键复制，
 * 认证器 App 都支持"手动输入密钥"；离线环境也能用。
 */
import { useCallback, useEffect, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { twoFactorApi } from '@duoduo/api-sdk/endpoints';
import type { TotpSetupView, TotpStatusView } from '@duoduo/api-sdk/types';
import { Alert } from './ui';

/** 把 Base32 密钥按 4 字符分组，便于人工抄写（认证器里也是这么显示的） */
const groupSecret = (secret: string) => secret.replace(/(.{4})/g, '$1 ').trim();

export const TwoFactorSetupPanel: React.FC<{ audience: 'admin' | 'merchant' }> = ({ audience }) => {
  const [status, setStatus] = useState<TotpStatusView | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  /** 绑定流程中的临时状态 */
  const [setup, setSetup] = useState<TotpSetupView | null>(null);
  const [code, setCode] = useState('');
  /** 绑定成功后一次性展示的恢复码；用户点"我已保存"后清空 */
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

  /** 关闭两步验证时的输入（动态码或恢复码二选一） */
  const [disableUseRecovery, setDisableUseRecovery] = useState(false);
  const [disableCode, setDisableCode] = useState('');

  const reload = useCallback(async () => {
    try {
      setStatus(await twoFactorApi.status());
      setError(null);
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const startSetup = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      setSetup(await twoFactorApi.setup());
      setCode('');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const confirmEnable = async () => {
    if (!code.trim()) {
      setError('请输入认证器 App 里显示的 6 位验证码');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await twoFactorApi.enable({ code: code.trim() });
      setRecoveryCodes(res.recoveryCodes);
      setSetup(null);
      setCode('');
      setNotice('两步验证已启用。下次登录时需要输入验证码。');
      await reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const confirmDisable = async () => {
    if (!disableCode.trim()) {
      setError(disableUseRecovery ? '请输入一枚恢复码' : '请输入当前验证码');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await twoFactorApi.disable(
        disableUseRecovery
          ? { recoveryCode: disableCode.trim() }
          : { code: disableCode.trim() },
      );
      setDisableCode('');
      setNotice('两步验证已关闭。建议尽快重新绑定 —— 关闭期间账号只由密码保护。');
      await reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const copy = async (text: string, what: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setNotice(`${what}已复制到剪贴板。`);
    } catch {
      setError('浏览器拒绝了剪贴板访问，请手动选中后复制。');
    }
  };

  return (
    <div className="card card-pad" style={{ display: 'grid', gap: 14, maxWidth: 720 }}>
      {error ? <Alert kind="error">{error}</Alert> : null}
      {notice ? <Alert kind="success">{notice}</Alert> : null}

      {loading ? (
        <div className="text-muted">正在读取状态…</div>
      ) : status ? (
        <div className="text-muted" style={{ fontSize: 13 }}>
          当前状态：
          <b style={{ marginLeft: 6 }}>{status.enabled ? '已启用' : '未启用'}</b>
          {status.enabled && status.boundAt ? (
            <span style={{ marginLeft: 10 }}>绑定于 {status.boundAt}</span>
          ) : null}
          {status.enabled ? (
            <span style={{ marginLeft: 10 }}>剩余恢复码 {status.recoveryCodesRemaining} 枚</span>
          ) : null}
        </div>
      ) : null}

      {/* ---------- 一次性恢复码（绑定成功后） ---------- */}
      {recoveryCodes ? (
        <div className="card card-pad" style={{ background: 'var(--bg-subtle, #fafafa)' }}>
          <h4 style={{ marginTop: 0 }}>请立即保存这些恢复码</h4>
          <p className="text-muted" style={{ fontSize: 13 }}>
            每枚只能用一次。手机丢失、换机没导出认证器时，它们是唯一的自助入口。
            <b>关掉本页后就再也查不到了</b> —— 服务端只保存它们的哈希值。
          </p>
          <div
            style={{
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              lineHeight: 1.9,
              userSelect: 'all',
            }}
          >
            {recoveryCodes.map((c) => (
              <div key={c}>{c}</div>
            ))}
          </div>
          <div className="row row-wrap" style={{ gap: 8, marginTop: 12 }}>
            <button className="btn btn-sm" onClick={() => void copy(recoveryCodes.join('\n'), '恢复码')}>
              复制全部
            </button>
            <button className="btn btn-sm btn-primary" onClick={() => setRecoveryCodes(null)}>
              我已保存
            </button>
          </div>
        </div>
      ) : null}

      {/* ---------- 未启用：引导绑定 ---------- */}
      {!loading && status && !status.enabled && !recoveryCodes ? (
        <div style={{ display: 'grid', gap: 12 }}>
          <Alert kind="warn">
            {audience === 'admin'
              ? '平台后台可以改动全站商品、订单与权限。'
              : '商家工作台可以改商品、发货、退款与回复客服。'}
            <b>强烈建议开启两步验证</b> —— 开启后，即使密码泄露，没有你手机上的验证码也进不来。
          </Alert>

          {!setup ? (
            <div>
              <button className="btn btn-primary" disabled={busy} onClick={() => void startSetup()}>
                {busy ? '准备中…' : '开始绑定'}
              </button>
            </div>
          ) : (
            <div style={{ display: 'grid', gap: 10 }}>
              <div>
                <div className="text-muted" style={{ fontSize: 13, marginBottom: 4 }}>
                  1. 在认证器 App（Google Authenticator / 1Password 等）里选择「手动输入密钥」，粘贴：
                </div>
                <div
                  style={{
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    fontSize: 15,
                    userSelect: 'all',
                    wordBreak: 'break-all',
                  }}
                >
                  {groupSecret(setup.secret)}
                </div>
                <button
                  className="btn btn-sm"
                  style={{ marginTop: 6 }}
                  onClick={() => void copy(setup.secret, '密钥')}
                >
                  复制密钥
                </button>
              </div>
              <div className="text-muted" style={{ fontSize: 12 }}>
                App 里若支持扫码，也可直接导入这个链接：
                <code style={{ wordBreak: 'break-all' }}>{setup.otpauthUri}</code>
              </div>
              <label style={{ display: 'block' }}>
                <span className="text-muted" style={{ fontSize: 13 }}>
                  2. 输入 App 里当前显示的 6 位数字
                </span>
                <input
                  className="input"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="6 位数字"
                  style={{ maxWidth: 220 }}
                />
              </label>
              <div className="row row-wrap" style={{ gap: 8 }}>
                <button className="btn btn-primary" disabled={busy} onClick={() => void confirmEnable()}>
                  {busy ? '校验中…' : '确认绑定'}
                </button>
                <button
                  className="btn btn-ghost"
                  disabled={busy}
                  onClick={() => {
                    setSetup(null);
                    setCode('');
                  }}
                >
                  取消
                </button>
              </div>
            </div>
          )}
        </div>
      ) : null}

      {/* ---------- 已启用：关闭入口 ---------- */}
      {!loading && status && status.enabled ? (
        <div style={{ display: 'grid', gap: 10 }}>
          <p className="text-muted" style={{ fontSize: 13, margin: 0 }}>
            关闭后账号将只由密码保护。若只是为了换手机，建议<b>先绑定新设备再关闭</b>。
          </p>
          <label style={{ display: 'block' }}>
            <span className="text-muted" style={{ fontSize: 13 }}>
              {disableUseRecovery ? '恢复码' : '当前验证码'}
            </span>
            <input
              className="input"
              autoComplete="one-time-code"
              value={disableCode}
              onChange={(e) => setDisableCode(e.target.value)}
              placeholder={disableUseRecovery ? '形如 ABCD-EFGH' : '6 位数字'}
              style={{ maxWidth: 260 }}
            />
          </label>
          <div className="row row-wrap" style={{ gap: 8 }}>
            <button className="btn" disabled={busy} onClick={() => void confirmDisable()}>
              {busy ? '处理中…' : '关闭两步验证'}
            </button>
            <button
              className="btn btn-ghost"
              disabled={busy}
              onClick={() => {
                setDisableUseRecovery((v) => !v);
                setDisableCode('');
                setError(null);
              }}
            >
              {disableUseRecovery ? '改用动态码' : '手机丢了？改用恢复码'}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
};
