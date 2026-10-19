/**
 * 店员管理页（E13，店主侧）：邀请 / 停用 / 启用。
 *
 * <p>准入 OWNER-only（后端 403 兜底）；店员打开本页会看到"店员账号不可管理成员"。
 */
import React, { useCallback, useMemo, useState } from 'react';
import { merchantStaffApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { StaffView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { SellerDenied } from './shared';

/** 本店成员人数很少（个位数到几十），接口一次性返回全量 —— 因此搜索与分页在本地做。
 *  与服务端分页的页面写法一致（同样的 SearchBar / Pagination），只是数据源不同。 */
const PAGE_SIZE = 10;

const statusTagClass = (s: string) =>
  s === 'ACTIVE' ? 'tag' : s === 'DISABLED' ? 'tag tag-danger' : 'tag';

export const SellerStaffPage: React.FC = () => {
  const { user } = useAuth();
  const isOwner = user?.role?.toUpperCase() === 'SELLER';
  const { push } = useToast();

  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);

  const { data, loading, error: loadError, reload } = useAsync(
    useCallback(() => merchantStaffApi.list(), []),
    isOwner,
    [],
  );

  // ★ 派生计算必须写在提前 return **之前**：hooks 不能条件调用，
  // 否则 isOwner 从 false 变 true 时 hooks 数量变化，React 会直接抛错。
  const all = data ?? [];
  const filtered = useMemo(() => {
    if (!keyword) return all;
    const kw = keyword.toLowerCase();
    return all.filter(
      (m) =>
        (m.nickname ?? '').toLowerCase().includes(kw)
        || (m.phoneMasked ?? '').includes(kw)
        || (m.role === 'OWNER' ? '店主' : '店员').includes(kw),
    );
  }, [all, keyword]);
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const current = Math.min(page, pageCount);
  const rows = filtered.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE);

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

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜昵称、手机号或身份"
          width={280}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的成员 {filtered.length} 人</span>
        ) : null}
      </div>

      {loadError ? <Alert kind="error">{loadError}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载成员…" /> : null}
      {!loading && filtered.length === 0 ? (
        <Empty
          icon="👥"
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的成员` : '还没有成员'}
          desc={keywordInput.trim() ? '换个昵称或手机号试试' : '先邀请一位店员吧'}
        />
      ) : null}
      {!loading && filtered.length > 0 ? (
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
              {rows.map((m) => (
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
          <Pagination page={current} pageCount={pageCount} total={filtered.length} onChange={setPage} />
        </div>
      ) : null}
    </div>
  );
};
