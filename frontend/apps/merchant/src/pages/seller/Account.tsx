/**
 * 商家工作台 · 账号中心。
 *
 * <h2>为什么不是跳到商城「个人中心」</h2>
 * 三个站点是独立入口，各有独立登录态（2026-10-21 起 Cookie 名也按入口分开：
 * `dd_at` / `dd_at_m` / `dd_at_a`）。工作台里点「个人中心」如果跳商城的 /me，
 * 看到的是**商城那个账号**——用户明明在商家端登录了 A，个人中心却显示买家 B，
 * 这不是"串号"，是两个站点各自登录了不同账号，但界面上没有任何提示。
 *
 * <p>所以工作台有自己的账号页：展示当前**工作台登录身份**（角色、账号、店铺身份），
 * 退出也在这里。商城的个人中心仍可从「返回商城」进入，两者互不冒充。
 */
import React, { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';

const MALL_URL = (import.meta.env.VITE_MALL_URL as string | undefined) ?? 'http://localhost:8088';
import { useAuth } from '@duoduo/shared/store/auth';
import { formatBjDateTime } from '@duoduo/shared/lib/time';
import { userApi } from '@duoduo/api-sdk/endpoints';
import { useAsync } from '@duoduo/shared/components/hooks';
import { TwoFactorSetupPanel } from '@duoduo/shared/components/TwoFactorSetup';
import { IpWhitelistPanel } from './IpWhitelistPanel';
import type { MerchantApplicationView } from '@duoduo/api-sdk/types';

const ROLE_TEXT: Record<string, string> = {
  SELLER: '商家（店铺主体）',
  OPERATOR: '平台运营',
  ADMIN: '平台管理员',
  USER: '普通用户',
};

/**
 * 店铺改名面板（第八十一轮）：仅营业中（APPROVED）店铺可改；
 * 全局唯一，重名会收到后端「店铺名已被使用」的冲突提示；每次改名平台留审计。
 * 改名即时生效 —— 购物车分组、商品卡片、结算页显示的都是实时查的店铺名。
 */
const StoreRenamePanel: React.FC = () => {
  const { refreshMe } = useAuth();
  const fetcher = useCallback(() => userApi.getMerchantApplication(), []);
  const { data } = useAsync<MerchantApplicationView>(fetcher, true, []);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  if (!data) return null;
  const canRename = data.status === 'APPROVED';

  const submit = async () => {
    setSaving(true);
    setMsg(null);
    try {
      await userApi.renameStore(draft.trim());
      await refreshMe();
      setEditing(false);
      setMsg({ kind: 'ok', text: '店铺名已更新，买家端将立即显示新店名' });
    } catch (err) {
      setMsg({ kind: 'err', text: err instanceof Error ? err.message : '改名失败，请稍后重试' });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ marginTop: 14 }}>
      <div className="row-between">
        <span className="small muted">店铺名称</span>
        {canRename && !editing ? (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setDraft(data.storeName); setEditing(true); }}>
            修改店名
          </button>
        ) : null}
      </div>
      <div style={{ fontWeight: 600 }}>{data.storeName}</div>
      {!canRename && data.status !== 'DRAFT' ? (
        <div className="small muted" style={{ marginTop: 4 }}>
          店铺当前状态（{data.status}）不支持改名；仅营业中的店铺支持修改店名。
        </div>
      ) : null}
      {editing ? (
        <div className="row" style={{ gap: 8, marginTop: 8 }}>
          <input
            className="input"
            value={draft}
            maxLength={30}
            placeholder="2–30 字，全局唯一"
            onChange={(e) => setDraft(e.target.value)}
          />
          <button type="button" className="btn btn-ghost" disabled={saving} onClick={() => setEditing(false)}>
            取消
          </button>
          <button type="button" className="btn btn-primary" disabled={saving || draft.trim().length < 2} onClick={() => void submit()}>
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      ) : null}
      {msg ? (
        <div className="small" style={{ marginTop: 6, color: msg.kind === 'ok' ? 'var(--success, green)' : 'var(--danger)' }}>
          {msg.text}
        </div>
      ) : null}
    </div>
  );
};


export const SellerAccountPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  if (!user) return null;

  return (
    <div>
      <h2 className="section-title">账号中心</h2>
      <p className="small muted" style={{ marginBottom: 16 }}>
        这里是商家工作台的登录账号；商城个人中心是另一套账号，两者互不影响。
      </p>

      <div className="card card-pad" style={{ maxWidth: 640 }}>
        <div className="row" style={{ gap: 14, alignItems: 'center', marginBottom: 16 }}>
          {user.avatar ? (
            <img
              src={user.avatar}
              alt=""
              style={{ width: 56, height: 56, borderRadius: '50%', objectFit: 'cover' }}
            />
          ) : (
            <span
              aria-hidden
              style={{
                width: 56,
                height: 56,
                borderRadius: '50%',
                background: 'var(--surface-2)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 24,
              }}
            >
              👤
            </span>
          )}
          <div>
            <div style={{ fontSize: 18, fontWeight: 600 }}>{user.nickname || '未设置昵称'}</div>
            <div className="small muted">{ROLE_TEXT[user.role?.toUpperCase()] || user.role}</div>
          </div>
        </div>

        <dl style={{ display: 'grid', gridTemplateColumns: '110px 1fr', rowGap: 10, margin: 0 }}>
          <dt className="small muted">手机号</dt>
          <dd style={{ margin: 0 }}>{user.phoneMasked}</dd>
          <dt className="small muted">账号 ID</dt>
          <dd style={{ margin: 0, fontFamily: 'monospace' }}>{user.id}</dd>
          <dt className="small muted">注册时间</dt>
          <dd style={{ margin: 0 }}>{formatBjDateTime(user.createdAt) || '—'}</dd>
          <dt className="small muted">实名认证</dt>
          <dd style={{ margin: 0 }}>{user.realNameVerified ? '已认证' : '未认证'}</dd>
          {user.merchant && (
            <>
              <dt className="small muted">所属店铺</dt>
              <dd style={{ margin: 0 }}>
                {user.merchant.storeName}
                （{user.merchant.memberRole === 'OWNER' ? '店主' : '店员'}）
              </dd>
            </>
          )}
        </dl>

        {user.role?.toUpperCase() === 'SELLER' ? <StoreRenamePanel /> : null}

        <div className="row" style={{ gap: 8, marginTop: 20 }}>
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => void logout().then(() => navigate('/login', { replace: true }))}
          >
            退出登录
          </button>
        </div>

        {/* 双身份：同一账号在商城也有买家侧。
            带上 expect：商城据此判断"当前商城登录的是不是同一个人"，
            相同则直接进入，不同/未登录则提示登录已过期并跳登录页。 */}
        <div className="row" style={{ gap: 8, marginTop: 14 }}>
          <a className="btn btn-ghost" href={`${MALL_URL}/me?from=merchant&expect=${user.id}`}>
            去商城个人中心
          </a>
        </div>
        <p className="small muted" style={{ marginTop: 12, marginBottom: 0 }}>
          商家工作台与商城共用同一个账号（同一手机号）：商城侧的个人中心仍按商城登录态展示。
          若商城当前登录的不是本账号，进入时会提示登录已过期并要求重新登录。
        </p>
      </div>

      {/* F-05 · 两步验证：后端 /api/auth/2fa/** 本来就是账号自助能力，
          商家端此前缺的只是入口。开启后商家端登录会要求输入认证器验证码。 */}
      <div style={{ maxWidth: 720, marginTop: 18 }}>
        <h3 className="section-title" style={{ fontSize: 17 }}>账号安全 · 两步验证</h3>
        <p className="small muted" style={{ marginTop: 0, marginBottom: 10 }}>
          商家账号能改商品、发货、退款与回复客服。开启两步验证后，即使密码泄露，
          没有你手机上的验证码也进不了工作台；同时建议店主也提醒店员自行开启。
        </p>
        <TwoFactorSetupPanel audience="merchant" />
      </div>

      {/* E-06 · IP 白名单：店主专属。开启后网关会拦住名单外的 /api/seller/** 访问，
          面板本身必须把「当前 IP」摆出来并支持一键加入，否则一次配置就能把人锁死。 */}
      <IpWhitelistPanel />
    </div>
  );
};
