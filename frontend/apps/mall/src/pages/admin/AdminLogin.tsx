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
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { isOperator } from './shared';

export const AdminLoginPage: React.FC = () => {
  const { login, logout } = useAuth();
  const navigate = useNavigate();
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!phone.trim() || !password) {
      setError('请输入手机号与密码');
      return;
    }
    setBusy(true);
    try {
      const me = await login(phone.trim(), password);
      if (!isOperator(me.role)) {
        // 账号能登录但不属于平台侧：明确告知并**退出会话**，
        // 避免留下"已登录却处处 403"的半成品状态。
        await logout();
        setError(`该账号（${me.role}）无平台后台权限，仅平台运营 / 管理员可登录`);
        return;
      }
      navigate('/admin', { replace: true });
    } catch (err) {
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
          <label style={{ display: 'block', marginBottom: 16 }}>
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
          <button className="btn btn-primary" type="submit" disabled={busy} style={{ width: '100%' }}>
            {busy ? '登录中…' : '登录'}
          </button>
        </form>
      </div>
    </div>
  );
};
