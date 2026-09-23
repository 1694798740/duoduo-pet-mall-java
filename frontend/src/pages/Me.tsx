/**
 * 个人中心。
 *
 * 数据来源：GET /api/users/me（经 AuthProvider 拿到）、
 *          PATCH /api/users/me（改昵称/头像）、POST /api/users/me/realname（实名）
 *
 * 关于实名认证：后端只保存身份证掩码，明文不入库、不回传。
 * 因此界面上的身份证输入框只做即时校验，提交后只回显 xxx****xxxx。
 */

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { toFriendlyMessage } from '../api/client';
import { userApi } from '../api/endpoints';
import { formatDate } from '../api/client';
import { Alert, Field, LoadingBlock, useToast } from '../components/ui';
import { CreditPanel } from '../components/CreditBadge';
import { canAccessSellerConsole } from './seller/shared';
import { useAuth } from '../store/auth';

/** 身份证 18 位：6 位地区 + 8 位生日 + 3 位顺序 + 1 位校验（末位可为 X） */
const ID_CARD_PATTERN = /^\d{17}[\dXx]$/;

/**
 * 角色中文名。
 * 库里存的是大写（USER / SELLER / OPERATOR / ADMIN），这里先转大写再比对 ——
 * 直接拿 'seller' 去等于一个大写值永远为 false，会把卖家显示成「买家」。
 */
const ROLE_LABELS: Record<string, string> = {
  USER: '买家',
  SELLER: '卖家',
  OPERATOR: '运营',
  ADMIN: '管理员',
};

export function roleLabelOf(role: string): string {
  return ROLE_LABELS[role.toUpperCase()] ?? role;
}

export const MePage: React.FC = () => {
  const { user, pets, refreshMe, loading } = useAuth();
  const { push } = useToast();

  const [nickname, setNickname] = useState('');
  const [avatar, setAvatar] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [realName, setRealName] = useState('');
  const [idCard, setIdCard] = useState('');
  const [realBusy, setRealBusy] = useState(false);
  const [realError, setRealError] = useState<string | null>(null);

  useEffect(() => {
    if (user) {
      setNickname(user.nickname);
      setAvatar(user.avatar ?? '');
    }
  }, [user]);

  if (loading) return <LoadingBlock text="正在加载个人信息…" />;
  if (!user) return null;

  const saveProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!nickname.trim()) {
      setError('昵称不能为空');
      return;
    }
    setBusy(true);
    try {
      await userApi.update({ nickname: nickname.trim(), avatar: avatar.trim() || undefined });
      await refreshMe();
      push('资料已更新', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitRealName = async (e: React.FormEvent) => {
    e.preventDefault();
    setRealError(null);
    if (!realName.trim()) {
      setRealError('请填写真实姓名');
      return;
    }
    if (!ID_CARD_PATTERN.test(idCard.trim())) {
      setRealError('请输入合法的 18 位身份证号');
      return;
    }
    setRealBusy(true);
    try {
      await userApi.realName({ realName: realName.trim(), idCard: idCard.trim().toUpperCase() });
      await refreshMe();
      setIdCard('');
      setRealName('');
      push('实名认证已完成', 'success');
    } catch (err) {
      setRealError(toFriendlyMessage(err));
    } finally {
      setRealBusy(false);
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">个人中心</h2>
        <span className="section-sub">账号 ID {user.id}</span>
      </div>

      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>基本资料</h3>

          {error ? (
            <div style={{ marginBottom: 12 }}>
              <Alert kind="error">{error}</Alert>
            </div>
          ) : null}

          <form onSubmit={saveProfile}>
            <Field label="手机号" hint="手机号不可修改，如需更换请联系客服">
              <input className="input" value={user.phoneMasked} disabled />
            </Field>

            <Field label="昵称" required>
              <input
                className="input"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                maxLength={20}
              />
            </Field>

            <Field label="头像地址" hint="填写图片 URL，留空则使用默认头像">
              <input
                className="input"
                value={avatar}
                onChange={(e) => setAvatar(e.target.value)}
                placeholder="https://…"
              />
            </Field>

            <button className="btn btn-primary" type="submit" disabled={busy}>
              {busy ? '保存中…' : '保存资料'}
            </button>
          </form>
        </div>

        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>账号状态</h3>
            <dl className="spec-list">
              <dt>角色</dt>
              <dd>{roleLabelOf(user.role)}</dd>
              <dt>账号状态</dt>
              <dd>{user.status === 'ACTIVE' ? '正常' : user.status}</dd>
              <dt>注册时间</dt>
              <dd>{formatDate(user.createdAt)}</dd>
              <dt>宠物档案</dt>
              <dd>
                {pets.length ? (
                  <>
                    {pets.length} 个 · {pets.map((p) => p.name).join('、')}
                    <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                      管理
                    </Link>
                  </>
                ) : (
                  <>
                    未建档
                    <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                      去建档
                    </Link>
                  </>
                )}
              </dd>
            </dl>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 10 }}>信用分</h3>
            {/* 这里刻意**不**用 user.creditScore：那是用户库里的一份副本（定时任务回写），
                可能落后于履约域的实时计算值。同一页面上放两个来源的同一指标，
                迟早会出现「上面 100、下面 60」—— 所以只留这一个权威来源。 */}
            <CreditPanel userId={user.id} />
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>实名认证</h3>

            {user.realNameVerified ? (
              <dl className="spec-list">
                <dt>真实姓名</dt>
                <dd>{user.realName ?? '—'}</dd>
                <dt>身份证号</dt>
                <dd>{user.idCardMasked ?? '—'}</dd>
                <dt>状态</dt>
                <dd>
                  <span className="tag tag-success">已认证</span>
                </dd>
              </dl>
            ) : (
              <>
                <Alert kind="info">
                  实名信息仅用于交易合规校验，服务端只存储掩码，不回传明文。
                </Alert>
                {realError ? (
                  <div style={{ margin: '12px 0' }}>
                    <Alert kind="error">{realError}</Alert>
                  </div>
                ) : null}
                <form onSubmit={submitRealName} style={{ marginTop: 12 }}>
                  <Field label="真实姓名" required>
                    <input
                      className="input"
                      value={realName}
                      onChange={(e) => setRealName(e.target.value)}
                      maxLength={20}
                    />
                  </Field>
                  <Field label="身份证号" required hint="18 位，末位可为 X">
                    <input
                      className="input"
                      value={idCard}
                      onChange={(e) => setIdCard(e.target.value.replace(/[^\dxX]/g, '').slice(0, 18))}
                      placeholder="请输入身份证号"
                    />
                  </Field>
                  <button className="btn btn-primary" type="submit" disabled={realBusy}>
                    {realBusy ? '提交中…' : '提交认证'}
                  </button>
                </form>
              </>
            )}
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 10 }}>快捷入口</h3>
            <div className="row row-wrap">
              {canAccessSellerConsole(user.role) ? (
                <Link className="btn btn-primary btn-sm" to="/seller">
                  商家端
                </Link>
              ) : null}
              <Link className="btn btn-ghost btn-sm" to="/orders">
                我的订单
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/coupons">
                我的券包
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/after-sales">
                我的售后
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/reviews/mine">
                我的评价
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/cart">
                购物车
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/pets">
                宠物档案
              </Link>
              <Link className="btn btn-ghost btn-sm" to="/listings">
                二手闲置
              </Link>
            </div>
            {canAccessSellerConsole(user.role) ? null : (
              <div className="small muted" style={{ marginTop: 10 }}>
                想卖东西？完成实名后到<Link to="/seller" className="text-brand"> 商家端 </Link>
                一键申请成为卖家。
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
