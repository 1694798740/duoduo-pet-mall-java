/**
 * 商家端共享件：角色判定（已抽到 `src/lib/roles.ts`，此处重导出）+ 「还不是卖家」引导。
 *
 * 为什么判定要单独放中立模块：商城顶栏也要按它显示「商家端」入口，
 * 若从本文件引会把整个商家 UI 拖进商城产物（E5/E6 各踩过一次）。
 */

import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { toFriendlyMessage, refreshSession } from '@duoduo/api-sdk/client';
import { userApi } from '@duoduo/api-sdk/endpoints';
import { Alert, useToast } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

export { canAccessSellerConsole } from '@duoduo/shared/lib/roles';

export const SellerApplyCard: React.FC<{ compact?: boolean }> = ({ compact }) => {
  const { user, refreshMe } = useAuth();
  const { push } = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const verified = Boolean(user?.realNameVerified);

  const apply = async () => {
    setError(null);
    setBusy(true);
    try {
      await userApi.applyMerchant();
      // ★ 角色写在访问令牌里：入驻成功后**立刻轮转一次令牌**，商家端权限即时生效
      //   （此前提示「重新登录后生效」，就是边界 B-08 —— 这里把它在网页端正式修掉）。
      await refreshSession();
      await refreshMe();
      push('已成为卖家，商家端已解锁', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card card-pad" style={compact ? undefined : { marginTop: 24 }}>
      <h3 style={{ marginBottom: 10 }}>商家端需要卖家身份</h3>

      {error ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="error">{error}</Alert>
        </div>
      ) : null}

      <Alert kind="info">
        成为卖家需要先完成<b>实名认证</b>：钱是放款给卖家的，放款给一个不可追责的主体，
        担保交易的信任基础就没了。后端会在入驻接口上强制校验，不认前端是否显示按钮。
      </Alert>

      <div className="row row-wrap" style={{ marginTop: 14 }}>
        {verified ? (
          <button className="btn btn-primary" onClick={apply} disabled={busy}>
            {busy ? '提交中…' : '申请成为卖家'}
          </button>
        ) : (
          <Link className="btn btn-primary" to="/me">
            先去实名认证
          </Link>
        )}
        <Link className="btn btn-ghost" to="/">
          返回首页
        </Link>
      </div>

      {verified ? null : (
        <div className="small muted" style={{ marginTop: 10 }}>
          当前账号：{user?.nickname ?? '—'}（角色 {user?.role ?? '—'}）
        </div>
      )}

      <div className="small muted" style={{ marginTop: 10 }}>
        说明：角色升级后 access token 里的角色仍是旧快照，需<b>重新登录</b>才在商家端全量生效；
        当前会话内刷新资料后即可进入概览页。
      </div>
    </div>
  );
};
