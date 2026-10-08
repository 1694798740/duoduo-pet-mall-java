/**
 * 登录 / 注册页。
 *
 * 与 Node 原型版的关键差异：
 *  · 只有「手机号 + 密码」一种方式 —— 后端没有短信验证码接口
 *  · 登录成功后无需前端存令牌：Cookie 由服务端 Set-Cookie 下发（httpOnly）
 *  · 注册成功即登录（后端直接下发 Cookie）
 *  · 支持 ?next= 回跳，登录后原路返回
 *
 * 安全约定：密码校验规则与后端保持一致（8–64 位），前端只做提示性预校验，
 * 真正的权威校验始终在服务端。
 */

import React, { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Field, PHONE_PATTERN, useToast } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

export const LoginPage: React.FC = () => {
  const [isRegister, setIsRegister] = useState(false);
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const { login, register, user } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next') ?? '/';

  const clientValidate = (): string | null => {
    if (!PHONE_PATTERN.test(phone)) return '请输入合法的 11 位手机号';
    if (isRegister && (password.length < 8 || password.length > 64)) {
      return '密码长度需为 8–64 位';
    }
    if (!password) return '请输入密码';
    if (password.length > 64) return '密码长度不能超过 64 位';
    return null;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const invalid = clientValidate();
    if (invalid) {
      setError(invalid);
      return;
    }

    setBusy(true);
    try {
      if (isRegister) {
        await register(phone, password, nickname.trim() || undefined);
        push('注册成功，已自动登录', 'success');
      } else {
        await login(phone, password);
        push('登录成功', 'success');
      }
      navigate(next, { replace: true });
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  // 演示库不预置账号（实测 1380000* 段无数据），改为生成随机体验号走真实注册流程
  const fillDemo = () => {
    const randPhone = `139${String(Math.floor(10000000 + Math.random() * 89999999))}`;
    setIsRegister(true);
    setError(null);
    setPhone(randPhone);
    setPassword('duoduo123');
    setNickname('体验用户');
  };

  if (user) {
    return (
      <div className="auth-shell">
        <div className="card card-pad" style={{ textAlign: 'center' }}>
          <h2 style={{ fontSize: 18, marginBottom: 8 }}>你已登录</h2>
          <p className="muted small">当前账号：{user.nickname}（{user.phoneMasked}）</p>
          <button className="btn btn-primary" onClick={() => navigate(next, { replace: true })}>
            继续
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-shell">
      <div className="card card-pad">
        <div className="row" style={{ marginBottom: 18 }}>
          <span className="brand-logo">🐾</span>
          <div>
            <h2 style={{ fontSize: 18 }}>{isRegister ? '注册多多宠物商店' : '欢迎回来'}</h2>
            <div className="small muted">自营保真 · 闲置担保交易</div>
          </div>
        </div>

        {error ? (
          <div style={{ marginBottom: 14 }}>
            <Alert kind="error">{error}</Alert>
          </div>
        ) : null}

        <form onSubmit={submit}>
          <Field label="手机号" required>
            <input
              className="input"
              value={phone}
              onChange={(e) => setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))}
              placeholder="11 位手机号"
              inputMode="numeric"
              autoComplete="tel"
            />
          </Field>

          {isRegister ? (
            <Field label="昵称" hint="不填由系统生成，例如「宠友0001」">
              <input
                className="input"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="给账号起个名字"
                maxLength={20}
              />
            </Field>
          ) : null}

          <Field
            label="密码"
            required
            hint={isRegister ? '8–64 位，建议字母 + 数字组合' : undefined}
          >
            <input
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              maxLength={64}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
            />
          </Field>

          <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
            {busy ? '处理中…' : isRegister ? '注册并登录' : '登录'}
          </button>
        </form>

        <div style={{ marginTop: 14, textAlign: 'center' }}>
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            onClick={() => {
              setIsRegister((v) => !v);
              setError(null);
            }}
          >
            {isRegister ? '已有账号？去登录' : '还没有账号？立即注册'}
          </button>
        </div>

        <div className="divider" />
        <div className="small muted" style={{ marginBottom: 8 }}>
          快速体验（生成随机体验账号，切到注册模式后点「注册」即可）
        </div>
        <div className="row row-wrap">
          <button className="btn btn-ghost btn-sm" type="button" onClick={fillDemo}>
            一键生成体验账号
          </button>
        </div>

        <div className="divider" />
        <div className="small muted">
          登录状态由 httpOnly Cookie 承载，前端不接触令牌，也不写入本地存储。
        </div>
      </div>
    </div>
  );
};
