/**
 * 平台管理后台 · 类目管理（M10）。
 *
 * 数据源：GET /api/admin/categories（扁平列表）+ 增改删 + 启停用。
 * 类目是两级树，后台用扁平列表展示，编辑通过弹窗完成。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '../../api/endpoints';
import type { CategoryNode, CategorySaveRequest } from '../../api/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, useToast,
} from '../../components/ui';
import { useAsync } from '../../components/hooks';
import { toFriendlyMessage } from '../../api/client';
import { useAuth } from '../../store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const emptyForm: CategorySaveRequest = { name: '', parentId: 0, sortNo: 0, icon: '' };

export const AdminCategoriesPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [editing, setEditing] = useState<CategoryNode | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<CategorySaveRequest>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.categories(), []),
    operator,
    [operator],
  );

  if (!operator) return <AdminDenied />;
  if (loading) return <LoadingBlock text="正在加载类目…" />;

  const items = data ?? [];
  const nameOf = (parentId: number | null) =>
    parentId && parentId !== 0 ? (items.find((c) => c.id === parentId)?.name ?? `#${parentId}`) : '（顶级）';

  const openCreate = () => {
    setEditing(null);
    setCreating(true);
    setForm(emptyForm);
    setActionError(null);
  };
  const openEdit = (item: CategoryNode) => {
    setEditing(item);
    setCreating(false);
    setForm({ name: item.name, parentId: item.parentId ?? 0, sortNo: 0, icon: item.icon ?? '' });
    setActionError(null);
  };
  const closeModal = () => {
    setEditing(null);
    setCreating(false);
    setForm(emptyForm);
  };

  const save = async () => {
    setActionError(null);
    setBusy(true);
    try {
      if (editing) {
        await adminApi.updateCategory(editing.id, form);
        push('类目已更新', 'success');
      } else {
        await adminApi.createCategory(form);
        push('类目已创建', 'success');
      }
      closeModal();
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (item: CategoryNode, active: boolean) => {
    try {
      await adminApi.setCategoryStatus(item.id, active);
      push(active ? '已启用' : '已停用', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    }
  };

  const remove = async (item: CategoryNode) => {
    setActionError(null);
    try {
      await adminApi.deleteCategory(item.id);
      push('类目已删除', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 类目管理</h2>
      <AdminTabs />

      <div className="row-between row-wrap" style={{ marginBottom: 14 }}>
        <span className="small muted">共 {items.length} 个类目（两级树，扁平展示）</span>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建类目</button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {items.length === 0 ? (
        <Empty title="暂无类目" />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>ID</th><th>名称</th><th>父类目</th><th>层级</th><th>状态</th><th style={{ width: 200 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.id}</td>
                  <td>{item.name}</td>
                  <td className="small muted">{nameOf(item.parentId)}</td>
                  <td>{item.depth}</td>
                  <td>{item.status === 'INACTIVE' ? <span className="muted">已停用</span> : '启用中'}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <button className="btn btn-ghost btn-sm" onClick={() => openEdit(item)}>编辑</button>
                      {item.status === 'INACTIVE' ? (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, true)}>启用</button>
                      ) : (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, false)}>停用</button>
                      )}
                      <button className="btn btn-ghost btn-sm" onClick={() => remove(item)}>删除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={creating || editing !== null}
        title={editing ? '编辑类目' : '新建类目'}
        onClose={closeModal}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeModal}>取消</button>
            <button className="btn btn-primary" disabled={busy || !form.name.trim()} onClick={save}>
              {busy ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        <Field label="名称" required>
          <input className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} maxLength={50} />
        </Field>
        <Field label="父类目" hint="留 0 表示顶级类目；选某个类目则成为其子类目">
          <select
            className="input"
            value={form.parentId ?? 0}
            onChange={(e) => setForm({ ...form, parentId: Number(e.target.value) })}
          >
            <option value={0}>（顶级）</option>
            {items.filter((c) => c.depth === 1).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </Field>
        <Field label="排序号">
          <input className="input" type="number" value={form.sortNo ?? 0} onChange={(e) => setForm({ ...form, sortNo: Number(e.target.value) })} />
        </Field>
        <Field label="图标（emoji 或图标类名，可空）">
          <input className="input" value={form.icon ?? ''} onChange={(e) => setForm({ ...form, icon: e.target.value })} placeholder="如 🐱" maxLength={20} />
        </Field>
      </Modal>
    </div>
  );
};
