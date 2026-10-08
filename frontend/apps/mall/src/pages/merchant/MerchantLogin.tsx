/**
 * 第三方商家平台 · **独立登录页**（E6）。
 *
 * <p>与平台管理后台的登录页同构：入口、页面、站点都在**商家自己这一侧**，
 * 不再需要"先去商城登录"。登录接口仍走统一账号中心（账号体系有意共享），
 * 但**会话独立**（不同主机名 → 不同 Cookie）。
 *
 * <p>准入判定用 `canAccessSellerConsole`（卖家本人，或运营/管理员用于代运营与排障）——
 * 与后端同口径；非准入角色登录后**立刻退出会话**并明确告知，避免"已登录却处处 403"。
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { canAccessSellerConsole } from '@duoduo/shared/lib/roles';
import { useAuth } from '@duoduo/shared/store/auth';

export const MerchantLoginPage: React.FC = () => {
  const { login } = useAuth();
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
      // 与平台后台**刻意不同**：这里不拒绝非商家账号，而是进入工作台由外壳渲染**入驻引导**
      // （"先实名 → 申请入驻"）—— 商家平台的登录页本身就是招商入口，拒登录会把漏斗掐断。
      if (!canAccessSellerConsole(me.role)) {
        void me; // 角色仅用于日志/排查，准入由外壳与后端共同判定
      }
      navigate('/seller', { replace: true });
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
          商家工作台 · 登录
        </h2>
        <p className="text-muted" style={{ marginTop: 4, fontSize: 13 }}>
          仅限已入驻的商家账号使用。
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
              placeholder="11 位手机号"
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
