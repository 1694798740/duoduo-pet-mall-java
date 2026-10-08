/**
 * 平台管理后台 · 运营账号管理（A7）。
 *
 * 数据源：GET /api/admin/users?role=&keyword=（分页，脱敏）
 *        + POST /api/admin/users/{id}/role（改角色，仅 ADMIN）。
 *
 * 关键口径（与后端一致）：
 *  - 列表不展示实名姓名 / 身份证 / 完整手机号 —— 管的是「账号与角色」，不是身份资料；
 *  - 改角色成功后展示后端下发的 notice（含「目标用户需重新登录后生效」）；
 *  - 改角色入口只对 ADMIN 开放（OPERATOR 可看列表，点不动）—— 后端 403 是硬边界，
 *    前端隐藏只是少让人撞一次墙。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { AdminUserView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isAdminRole, isOperator } from './shared';

const PAGE_SIZE = 10;
const ROLE_OPTIONS = ['USER', 'SELLER', 'OPERATOR', 'ADMIN'];

/** 角色中文标签：表格里大写枚举是给程序看的，运营要看人话 */
const ROLE_LABELS: Record<string, string> = {
  USER: '普通用户',
  SELLER: '卖家',
  OPERATOR: '运营',
  ADMIN: '管理员',
};

export const AdminOperatorsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  // 改他人角色只有管理员能做；判定收敛到 shared（前后端同口径，忽略大小写）—— R74-3
  const isAdmin = isAdminRole(user?.role);
  const { push } = useToast();

  const [kwInput, setKwInput] = useState('');
  const [roleInput, setRoleInput] = useState('');
  const [query, setQuery] = useState<{ role?: string; keyword?: string; page: number }>({ page: 1 });

  const [target, setTarget] = useState<AdminUserView | null>(null);
  const [newRole, setNewRole] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.adminUsers({ ...query, pageSize: PAGE_SIZE }), [query]),
    operator,
    [operator, query],
  );

  if (!operator) return <AdminDenied />;
  if (loading) return <LoadingBlock text="正在加载用户…" />;

  const items = data?.items ?? [];

  const search = () => {
    setQuery({ role: roleInput || undefined, keyword: kwInput.trim() || undefined, page: 1 });
    setNotice(null);
  };

  const openChange = (item: AdminUserView) => {
    setTarget(item);
    setNewRole(item.role);
    setActionError(null);
  };
  const closeModal = () => {
    setTarget(null);
    setNewRole('');
  };

  const save = async () => {
    if (!target) return;
    setActionError(null);
    setBusy(true);
    try {
      const result = await adminApi.adminChangeRole(target.id, { role: newRole });
      // 后端下发的 notice 含「需重新登录后生效」，原样展示给运营（验收②）
      setNotice(result.notice);
      push('角色已更新', 'success');
      closeModal();
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 运营账号</h2>
      <AdminTabs />

      {!isAdmin ? (
        <Alert kind="info">
          查看用户列表需要运营 / 管理员身份；<b>变更角色仅限超级管理员（ADMIN）</b>。
        </Alert>
      ) : null}

      <div className="row row-wrap" style={{ marginBottom: 14, gap: 8 }}>
        <select
          className="input"
          style={{ width: 140 }}
          value={roleInput}
          onChange={(e) => setRoleInput(e.target.value)}
        >
          <option value="">全部角色</option>
          {ROLE_OPTIONS.map((r) => (
            <option key={r} value={r}>{ROLE_LABELS[r]}（{r}）</option>
          ))}
        </select>
        <input
          className="input"
          style={{ width: 220 }}
          placeholder="手机号 / 昵称"
          value={kwInput}
          onChange={(e) => setKwInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') search(); }}
          maxLength={30}
        />
        <button className="btn btn-primary btn-sm" onClick={search}>查询</button>
        <button
          className="btn btn-ghost btn-sm"
          onClick={() => { setKwInput(''); setRoleInput(''); setQuery({ page: 1 }); }}
        >
          重置
        </button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      {notice ? <Alert kind="success">{notice}</Alert> : null}

      {items.length === 0 ? (
        <Empty title="没有匹配的用户" />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>ID</th><th>手机号</th><th>昵称</th><th>角色</th><th>状态</th>
                <th>实名</th><th>信用分</th><th>注册时间</th><th style={{ width: 120 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.id}</td>
                  <td className="mono">{item.phoneMasked}</td>
                  <td>{item.nickname}</td>
                  <td>{ROLE_LABELS[item.role] ?? item.role}</td>
                  <td>{item.status === 'BANNED' ? <span className="muted">已封禁</span> : '正常'}</td>
                  <td>{item.realNameVerified ? '已实名' : '—'}</td>
                  <td>{item.creditScore ?? '—'}</td>
                  <td className="small muted">{item.createdAt?.slice(0, 10) ?? '—'}</td>
                  <td>
                    {isAdmin ? (
                      <button className="btn btn-ghost btn-sm" onClick={() => openChange(item)}>改角色</button>
                    ) : (
                      <span className="small muted">仅 ADMIN</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="row-between" style={{ marginTop: 10 }}>
            <span className="small muted">
              共 {data?.total ?? 0} 人 · 第 {data?.page ?? 1} / {data?.pageCount ?? 1} 页
            </span>
            <div className="row" style={{ gap: 6 }}>
              <button
                className="btn btn-ghost btn-sm"
                disabled={(data?.page ?? 1) <= 1}
                onClick={() => setQuery({ ...query, page: (data?.page ?? 1) - 1 })}
              >
                上一页
              </button>
              <button
                className="btn btn-ghost btn-sm"
                disabled={(data?.page ?? 1) >= (data?.pageCount ?? 1)}
                onClick={() => setQuery({ ...query, page: (data?.page ?? 1) + 1 })}
              >
                下一页
              </button>
            </div>
          </div>
        </div>
      )}

      <Modal
        open={target !== null}
        title="变更用户角色"
        onClose={closeModal}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeModal}>取消</button>
            <button
              className="btn btn-primary"
              disabled={busy || !newRole || (target != null && newRole === target.role)}
              onClick={save}
            >
              {busy ? '提交中…' : '确认变更'}
            </button>
          </>
        }
      >
        {target ? (
          <>
            <p className="small muted" style={{ marginTop: 0 }}>
              目标用户：{target.nickname}（{target.phoneMasked}）· 当前角色：
              {ROLE_LABELS[target.role] ?? target.role}
            </p>
            <Field label="目标角色" required>
              <select className="input" value={newRole} onChange={(e) => setNewRole(e.target.value)}>
                {ROLE_OPTIONS.map((r) => (
                  <option key={r} value={r}>{ROLE_LABELS[r]}（{r}）</option>
                ))}
              </select>
            </Field>
            <Alert kind="info">
              角色变更记录审计日志；被改用户<b>重新登录后</b>新角色才生效。
              平台至少保留一名管理员，最后一个 ADMIN 不可降级。
            </Alert>
          </>
        ) : null}
      </Modal>
    </div>
  );
};
