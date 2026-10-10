/**
 * 平台管理后台 · **独立登录页**（T-ARCH 第一步）。
 *
 * <p>此前后台入口未登录时提示"去商城登录" —— 那是**平台依赖商城**的耦合点。
 * 现在后台站点有**自己的登录页**：登录接口仍是统一的账号中心（同一套账号体系），
 * 但**入口、页面与运行站点都在后台这一侧**，商城站点下线也不影响后台使用。
 *
 * <p>会话为什么天然独立：Cookie 是**主机级**作用域（不带 Domain 属性），且**不区分端口** ——
 * 所以两个站点必须用**不同主机名**（商城 localhost:8088 / 后台 admin.localhost:8090），
 * 换端口是不够的。落地与证据见《三平台独立部署-架构调整方案》。
 *
 * <p><b>E21 · 两步验证</b>：账号一旦启用 2FA，密码正确还不足以登录 ——
 * 后端会返回 401 且 {@code code=TOTP_REQUIRED}，本页据此切到"第二步"。
 * 两个刻意的处理：
 * <ul>
 *   <li>切换判据是**错误码**而不是提示文案：文案随时可能改，而错误码是契约；</li>
 *   <li>"需要验证码"与"密码错误"给出**不同**的提示。混为一谈的后果是用户
 *       在"是不是密码记错了"里反复重输，而正确动作其实是去翻认证器 App。</li>
 * </ul>
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { isOperator } from './shared';

export const AdminLoginPage: React.FC = () => {
  const { login, logout } = useAuth();
  const navigate = useNavigate();
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  /** 第二步里改用恢复码（手机丢了 / 换机没导出认证器） */
  const [useRecovery, setUseRecovery] = useState(false);
  /**
   * 是否已进入第二步。
   *
   * <p>**只由后端确认**（收到 TOTP_REQUIRED）才置 true —— 前端不自行猜测"这个号看着像运营"，
   * 否则会给没开 2FA 的账号也弹一个验证码框，用户直接懵。
   */
  const [needSecondStep, setNeedSecondStep] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!phone.trim() || !password) {
      setError('请输入手机号与密码');
      return;
    }
    if (needSecondStep && !code.trim()) {
      setError(useRecovery ? '请输入一枚恢复码' : '请输入认证器 App 里的 6 位验证码');
      return;
    }
    setBusy(true);
    try {
      const me = await login(
        phone.trim(),
        password,
        needSecondStep
          ? useRecovery
            ? { recoveryCode: code.trim() }
            : { totpCode: code.trim() }
          : undefined,
      );
      if (!isOperator(me.role)) {
        // 账号能登录但不属于平台侧：明确告知并**退出会话**，
        // 避免留下"已登录却处处 403"的半成品状态。
        await logout();
        setError(`该账号（${me.role}）无平台后台权限，仅平台运营 / 管理员可登录`);
        return;
      }
      navigate('/admin', { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'TOTP_REQUIRED') {
        setNeedSecondStep(true);
        setCode('');
        return;
      }
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="container" style={{ paddingTop: 64, display: 'flex', justifyContent: 'center' }}>
      <div className="card" style={{ width: '100%', maxWidth: 420 }}>
        <h2 className="section-title" style={{ marginTop: 0 }}>
          平台管理后台 · 登录
        </h2>
        <p className="text-muted" style={{ marginTop: 4, fontSize: 13 }}>
          仅限平台运营与管理员使用。
        </p>
        {error ? (
          <div className="alert alert-error" style={{ margin: '12px 0' }} role="alert">
            {error}
          </div>
        ) : null}
        {needSecondStep ? (
          <div className="alert" style={{ margin: '12px 0', fontSize: 13 }} role="status">
            该账号已启用两步验证。请打开认证器 App，输入当前显示的 6 位数字。
          </div>
        ) : null}
        <form onSubmit={submit} style={{ marginTop: 12 }}>
          <label style={{ display: 'block', marginBottom: 10 }}>
            <span className="text-muted" style={{ fontSize: 13 }}>
              手机号
            </span>
            <input
              className="input"
              inputMode="numeric"
              autoComplete="username"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="请输入平台账号手机号"
            />
          </label>
          <label style={{ display: 'block', marginBottom: needSecondStep ? 10 : 16 }}>
            <span className="text-muted" style={{ fontSize: 13 }}>
              密码
            </span>
            <input
              className="input"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
            />
          </label>
          {needSecondStep ? (
            <label style={{ display: 'block', marginBottom: 16 }}>
              <span className="text-muted" style={{ fontSize: 13 }}>
                {useRecovery ? '恢复码' : '验证码'}
              </span>
              <input
                className="input"
                autoComplete="one-time-code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder={useRecovery ? '请输入恢复码（形如 ABCD-EFGH）' : '请输入 6 位数字'}
              />
            </label>
          ) : null}
          <button className="btn btn-primary" type="submit" disabled={busy} style={{ width: '100%' }}>
            {busy ? '登录中…' : needSecondStep ? '验证并登录' : '登录'}
          </button>
          {needSecondStep ? (
            <div style={{ marginTop: 10, textAlign: 'center' }}>
              <button
                type="button"
                className="btn"
                style={{ fontSize: 13, background: 'none', border: 'none', textDecoration: 'underline' }}
                onClick={() => {
                  setUseRecovery((v) => !v);
                  setCode('');
                  setError(null);
                }}
              >
                {useRecovery ? '改用认证器验证码' : '手机丢了？改用恢复码'}
              </button>
            </div>
          ) : null}
        </form>
      </div>
    </div>
  );
};
