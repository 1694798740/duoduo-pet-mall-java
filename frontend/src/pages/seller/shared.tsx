/**
 * 商家端共享件：角色判定 + 「还不是卖家」引导。
 *
 * 为什么单独放一个文件：商家端四个页面都要用同一套判定与同一段引导文案。
 * 若各写一份，迟早出现「概览页认、订单页不认」这种不一致，
 * 而这类问题在演示时表现为「页面一会儿能进一会儿不能进」，极难定位。
 */

import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { toFriendlyMessage } from '../../api/client';
import { userApi } from '../../api/endpoints';
import { Alert, useToast } from '../../components/ui';
import { useAuth } from '../../store/auth';

/**
 * 能否进入商家端。
 *
 * <p>与后端 {@code LoginUser#canAccessSellerConsole()} 保持同一口径：
 * 卖家本人，或运营 / 管理员（代运营与排查）。
 * 角色在库里存的是大写（USER / SELLER / OPERATOR / ADMIN），
 * 这里一律忽略大小写 —— 早期前端曾用 {@code user.role === 'seller'}，
 * 在大写存储下永远为 false，属于「看起来有校验、实际形同虚设」。
 */
export function canAccessSellerConsole(role: string | null | undefined): boolean {
  if (!role) return false;
  const upper = role.toUpperCase();
  return upper === 'SELLER' || upper === 'OPERATOR' || upper === 'ADMIN';
}

/** 「还不是卖家」引导卡：实名前置由后端强制（未实名返回 422），这里提前说清楚 */
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
      await refreshMe();
      push('已成为卖家，重新登录后商家端完整生效', 'success');
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
