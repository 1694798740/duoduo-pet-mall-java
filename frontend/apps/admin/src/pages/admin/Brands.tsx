/**
 * 平台管理后台 · 品牌管理（M10）。
 *
 * 数据源：GET /api/admin/brands（含停用）+ 增改删 + 启停用。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { BrandSaveRequest, BrandView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, useToast,
} from '@duoduo/shared/components/ui';
import { ImageUploader } from '@duoduo/shared/components/ImageUploader';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 本地分页每页条数（数据量小，够用即可） */
const PAGE_SIZE = 10;

const emptyForm: BrandSaveRequest = { name: '', logo: '', country: '', story: '' };

export const AdminBrandsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [editing, setEditing] = useState<BrandView | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<BrandSaveRequest>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.brands(), []),
    operator,
    [operator],
  );

  if (!operator) return <AdminDenied />;
  if (loading) return <LoadingBlock text="正在加载品牌…" />;

  const items = data ?? [];
  // 接口返回全量（品牌 / 类目通常只有几十条），搜索与分页在本地做；
  // 与服务端分页的页面用同一套 SearchBar / Pagination，交互保持一致。
  const filtered = keyword
    ? items.filter((it) => ([it.name, it.country, it.story ?? ''].join(' ')).toLowerCase().includes(keyword.toLowerCase()))
    : items;
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const current = Math.min(page, pageCount);
  const visible = filtered.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE);

  const openCreate = () => {
    setEditing(null);
    setCreating(true);
    setForm(emptyForm);
    setActionError(null);
  };
  const openEdit = (item: BrandView) => {
    setEditing(item);
    setCreating(false);
    setForm({ name: item.name, logo: item.logo ?? '', country: item.country ?? '', story: item.story ?? '' });
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
        await adminApi.updateBrand(editing.id, form);
        push('品牌已更新', 'success');
      } else {
        await adminApi.createBrand(form);
        push('品牌已创建', 'success');
      }
      closeModal();
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (item: BrandView, active: boolean) => {
    try {
      await adminApi.setBrandStatus(item.id, active);
      push(active ? '已启用' : '已停用', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    }
  };

  const remove = async (item: BrandView) => {
    setActionError(null);
    try {
      await adminApi.deleteBrand(item.id);
      push('品牌已删除', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 品牌管理</h2>
      <AdminTabs />

      <div className="row-between row-wrap" style={{ marginBottom: 8 }}>
        <span className="small muted">共 {items.length} 个品牌</span>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建类目</button>
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          placeholder="搜品牌名称"
          width={260}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的结果 {filtered.length} 个</span>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {filtered.length === 0 ? (
        <Empty title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的结果` : "暂无品牌"} />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>ID</th><th>名称</th><th>国家 / 地区</th><th>品牌故事</th><th>状态</th><th style={{ width: 200 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.id}</td>
                  <td>
                    <div className="row" style={{ gap: 8 }}>
                      {item.logo ? <img src={item.logo} alt={item.name} style={{ width: 28, height: 28, objectFit: 'contain' }} /> : null}
                      <span>{item.name}</span>
                    </div>
                  </td>
                  <td className="small muted">{item.country ?? '—'}</td>
                  <td className="small muted" style={{ maxWidth: 240 }}>
                    {item.story ? (item.story.length > 40 ? item.story.slice(0, 40) + '…' : item.story) : '—'}
                  </td>
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
          <Pagination page={current} pageCount={pageCount} total={filtered.length} onChange={setPage} />
        </div>
      )}

      <Modal
        open={creating || editing !== null}
        title={editing ? '编辑品牌' : '新建品牌'}
        onClose={closeModal}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeModal}>取消</button>
            <button className="btn btn-primary" disabled={busy || uploading || !form.name.trim()} onClick={save}>
              {busy ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        <Field label="名称" required>
          <input className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} maxLength={50} />
        </Field>
        <Field label="Logo（可空）" hint="支持本地上传，也可粘贴图片地址；不填则只显示品牌名">
          <ImageUploader
            value={form.logo ? [form.logo] : []}
            onChange={(next) => setForm({ ...form, logo: next[0] ?? '' })}
            max={1}
            size={64}
            onBusyChange={setUploading}
            addLabel="上传 Logo"
            urlPlaceholder="或粘贴 Logo 图片地址"
            hint=""
          />
        </Field>
        <Field label="国家 / 地区（可空）">
          <input className="input" value={form.country ?? ''} onChange={(e) => setForm({ ...form, country: e.target.value })} maxLength={50} />
        </Field>
        <Field label="品牌故事（可空）">
          <textarea className="input" rows={3} value={form.story ?? ''} onChange={(e) => setForm({ ...form, story: e.target.value })} maxLength={500} />
        </Field>
      </Modal>
    </div>
  );
};
