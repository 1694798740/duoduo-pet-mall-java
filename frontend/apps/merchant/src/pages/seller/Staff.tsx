/**
 * 店员管理页（E13，店主侧）：邀请 / 停用 / 启用。
 *
 * <p>准入 OWNER-only（后端 403 兜底）；店员打开本页会看到"店员账号不可管理成员"。
 */
import React, { useCallback, useState } from 'react';
import { merchantStaffApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { StaffView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { SellerDenied } from './shared';

const statusTagClass = (s: string) =>
  s === 'ACTIVE' ? 'tag' : s === 'DISABLED' ? 'tag tag-danger' : 'tag';

export const SellerStaffPage: React.FC = () => {
  const { user } = useAuth();
  const isOwner = user?.role?.toUpperCase() === 'SELLER';
  const { push } = useToast();

  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data, loading, error: loadError, reload } = useAsync(
    useCallback(() => merchantStaffApi.list(), []),
    isOwner,
    [],
  );

  if (!isOwner) return <SellerDenied reason="店员账号不可管理成员，请联系店主" />;

  const invite = async () => {
    setError(null);
    if (!phone.trim()) {
      setError('请输入被邀请人的手机号（需已完成注册）');
      return;
    }
    setBusy(true);
    try {
      await merchantStaffApi.invite(phone.trim());
      setPhone('');
      push('邀请成功，对方即可用自己账号进入本店工作台', 'success');
      reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (m: StaffView) => {
    setBusy(true);
    setError(null);
    try {
      await (m.status === 'ACTIVE'
        ? merchantStaffApi.disable(m.memberId)
        : merchantStaffApi.enable(m.memberId));
      push(m.status === 'ACTIVE' ? '已停用，立即生效' : '已启用', 'success');
      reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">店员管理</h2>

      <Alert kind="info">
        店员用<b>自己的账号</b>登录即可进入本店工作台，处理订单与商品；
        <b>结算与提现信息对店员不可见</b>。停用即时生效。一个账号只能属于一家店。
      </Alert>

      {error ? (
        <div style={{ margin: '12px 0' }}>
          <Alert kind="error">{error}</Alert>
        </div>
      ) : null}

      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row" style={{ gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <Field label="被邀请人手机号（需已注册）">
            <input
              className="input"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="11 位手机号"
              maxLength={11}
              style={{ width: 220 }}
            />
          </Field>
          <button className="btn btn-primary" onClick={() => void invite()} disabled={busy}>
            {busy ? '处理中…' : '邀请为店员'}
          </button>
        </div>
      </div>

      {loadError ? <Alert kind="error">{loadError}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载成员…" /> : null}
      {!loading && data && data.length === 0 ? (
        <Empty icon="👥" title="还没有成员" desc="先邀请一位店员吧" />
      ) : null}
      {!loading && data && data.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>成员</th>
                <th>手机号</th>
                <th>身份</th>
                <th>状态</th>
                <th>加入时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.map((m) => (
                <tr key={m.memberId}>
                  <td>{m.nickname}</td>
                  <td>{m.phoneMasked}</td>
                  <td>{m.role === 'OWNER' ? '店主' : '店员'}</td>
                  <td><span className={statusTagClass(m.status)}>{m.status === 'ACTIVE' ? '启用' : '已停用'}</span></td>
                  <td>{m.createdAt ? formatDate(m.createdAt) : '—'}</td>
                  <td>
                    {m.role === 'OWNER' ? (
                      <span className="small muted">—</span>
                    ) : m.status === 'ACTIVE' ? (
                      <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void toggle(m)}>
                        停用
                      </button>
                    ) : (
                      <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void toggle(m)}>
                        启用
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
};
