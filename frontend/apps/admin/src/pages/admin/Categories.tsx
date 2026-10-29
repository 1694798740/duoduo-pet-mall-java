/**
 * 平台管理后台 · 类目管理（M10）。
 *
 * 数据源：GET /api/admin/categories（扁平列表）+ 增改删 + 启停用。
 * 类目是两级树，后台用扁平列表展示，编辑通过弹窗完成。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type {
  CategoryAttrSaveRequest,
  CategoryAttrView,
  CategoryNode,
  CategorySaveRequest,
} from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';
import { CatalogImportModal } from './CatalogImportModal';

/**
 * 顶级类目的哨兵 parentId。
 *
 * ⚠️ 这个 `0` 是**真正的数值语义**（"没有父类目"），不是雪花 id ——
 * 它和后端的 `Long parentId` 约定一致。因此这里用字符串 `'0'` 表示，
 * 与真实的类目 id（18 位数字字符串）在类型上一致（都是 string）、
 * 在语义上可区分（`'0'` 恒为顶级）。**不要**把它和 `Number()` 混用。
 */
const TOP_PARENT = '0';

/** 本地分页每页条数（数据量小，够用即可） */
const PAGE_SIZE = 10;

const emptyForm: CategorySaveRequest = { name: '', parentId: TOP_PARENT, sortNo: 0, icon: '' };

const emptyAttrForm: CategoryAttrSaveRequest = {
  name: '', inputType: 'TEXT', required: false, options: [], sortNo: 0,
};

const ATTR_TYPE_LABEL: Record<string, string> = {
  TEXT: '文本',
  NUMBER: '数字',
  SELECT: '单选',
};

export const AdminCategoriesPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();
  const confirm = useConfirm();

  // ---- A-10 · 类目属性模板（在类目行内打开，作为类目的从属主数据） ----
  const [attrCategory, setAttrCategory] = useState<CategoryNode | null>(null);
  const [attrs, setAttrs] = useState<CategoryAttrView[]>([]);
  const [attrsLoading, setAttrsLoading] = useState(false);
  const [attrError, setAttrError] = useState<string | null>(null);
  const [attrForm, setAttrForm] = useState<CategoryAttrSaveRequest>(emptyAttrForm);
  const [attrOptionsText, setAttrOptionsText] = useState('');
  const [editingAttr, setEditingAttr] = useState<CategoryAttrView | null>(null);
  const [attrFormOpen, setAttrFormOpen] = useState(false);
  const [attrBusy, setAttrBusy] = useState(false);

  const [editing, setEditing] = useState<CategoryNode | null>(null);
  const [creating, setCreating] = useState(false);
  /** 第三批 · 第 8 项：批量导入（模板 → dry-run → 正式导入） */
  const [importOpen, setImportOpen] = useState(false);
  const [form, setForm] = useState<CategorySaveRequest>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.categories(), []),
    operator,
    [operator],
  );

  if (!operator) return <AdminDenied />;
  if (loading) return <LoadingBlock text="正在加载类目…" />;

  const items = data ?? [];
  // 接口返回全量（品牌 / 类目通常只有几十条），搜索与分页在本地做；
  // 与服务端分页的页面用同一套 SearchBar / Pagination，交互保持一致。
  // nameOf 必须定义在 filtered 之前：搜索函数会在 filter 回调里同步调用它，
  // 放在后面会命中 const 的暂时性死区（输入关键词瞬间整页崩掉）。
  /** 列出父类目名；parentId 为 null 或哨兵 '0' 都表示顶级 */
  const nameOf = (parentId: string | null) =>
    parentId && parentId !== TOP_PARENT
      ? (items.find((c) => c.id === parentId)?.name ?? `#${parentId}`)
      : '（顶级）';
  const filtered = keyword
    ? items.filter((it) => ([it.name, nameOf(it.parentId)].join(' ')).toLowerCase().includes(keyword.toLowerCase()))
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
  const openEdit = (item: CategoryNode) => {
    setEditing(item);
    setCreating(false);
    setForm({ name: item.name, parentId: item.parentId ?? TOP_PARENT, sortNo: 0, icon: item.icon ?? '' });
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

  // ---------------- 类目属性模板 ----------------

  const loadAttrs = async (categoryId: string) => {
    setAttrsLoading(true);
    setAttrError(null);
    try {
      setAttrs(await adminApi.categoryAttrs(categoryId));
    } catch (err) {
      setAttrError(toFriendlyMessage(err));
    } finally {
      setAttrsLoading(false);
    }
  };

  const openAttrs = (category: CategoryNode) => {
    setAttrCategory(category);
    setAttrs([]);
    setEditingAttr(null);
    setAttrFormOpen(false);
    setAttrError(null);
    void loadAttrs(category.id);
  };

  const closeAttrs = () => {
    setAttrCategory(null);
    setAttrs([]);
    setEditingAttr(null);
    setAttrFormOpen(false);
    setAttrError(null);
  };

  const openAttrCreate = () => {
    setEditingAttr(null);
    setAttrForm({ ...emptyAttrForm });
    setAttrOptionsText('');
    setAttrFormOpen(true);
    setAttrError(null);
  };

  const openAttrEdit = (attr: CategoryAttrView) => {
    setEditingAttr(attr);
    setAttrForm({
      name: attr.name,
      inputType: attr.inputType as CategoryAttrSaveRequest['inputType'],
      required: attr.required,
      options: attr.options ?? [],
      sortNo: attr.sortNo,
    });
    setAttrOptionsText((attr.options ?? []).join('，'));
    setAttrFormOpen(true);
    setAttrError(null);
  };

  const saveAttr = async () => {
    if (!attrCategory) return;
    const options = attrForm.inputType === 'SELECT'
      ? attrOptionsText.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean)
      : [];
    const body: CategoryAttrSaveRequest = { ...attrForm, name: attrForm.name.trim(), options };
    if (!body.name) {
      setAttrError('属性名不能为空');
      return;
    }
    setAttrBusy(true);
    setAttrError(null);
    try {
      if (editingAttr) {
        await adminApi.updateCategoryAttr(editingAttr.id, body);
        push('属性已更新', 'success');
      } else {
        await adminApi.createCategoryAttr(attrCategory.id, body);
        push('属性已新增', 'success');
      }
      setAttrFormOpen(false);
      setEditingAttr(null);
      await loadAttrs(attrCategory.id);
    } catch (err) {
      setAttrError(toFriendlyMessage(err));
    } finally {
      setAttrBusy(false);
    }
  };

  const toggleAttr = async (attr: CategoryAttrView, active: boolean) => {
    if (!attrCategory) return;
    setAttrError(null);
    try {
      await adminApi.setCategoryAttrStatus(attr.id, active);
      push(active ? '属性已启用' : '属性已停用', 'success');
      await loadAttrs(attrCategory.id);
    } catch (err) {
      setAttrError(toFriendlyMessage(err));
    }
  };

  const removeAttr = async (attr: CategoryAttrView) => {
    if (!attrCategory) return;
    if (!(await confirm({
      title: '删除类目属性',
      message: `删除「${attr.name}」后，已发布商品里的同名参数会退化为自定义属性；下次改规格时不再校验它。`,
      danger: true,
      confirmText: '删除',
    }))) return;
    setAttrError(null);
    try {
      await adminApi.deleteCategoryAttr(attr.id);
      push('属性已删除', 'success');
      await loadAttrs(attrCategory.id);
    } catch (err) {
      setAttrError(toFriendlyMessage(err));
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 类目管理</h2>
      <AdminTabs />

      <div className="row-between row-wrap" style={{ marginBottom: 8 }}>
        <span className="small muted">共 {items.length} 个类目（两级树，扁平展示）</span>
        <div className="row" style={{ gap: 8 }}>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              window.location.href = adminApi.categoryExportUrl();
            }}
          >
            导出 CSV
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => setImportOpen(true)}>批量导入</button>
          <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建类目</button>
        </div>
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          placeholder="搜类目名称"
          width={260}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的结果 {filtered.length} 个</span>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {filtered.length === 0 ? (
        <Empty title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的结果` : "暂无类目"} />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>ID</th><th>名称</th><th>父类目</th><th>层级</th><th>状态</th><th style={{ width: 200 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.id}</td>
                  <td>{item.name}</td>
                  <td className="small muted">{nameOf(item.parentId)}</td>
                  <td>{item.depth}</td>
                  <td>{item.status === 'INACTIVE' ? <span className="muted">已停用</span> : '启用中'}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <button className="btn btn-ghost btn-sm" onClick={() => openEdit(item)}>编辑</button>
                      <button className="btn btn-ghost btn-sm" onClick={() => openAttrs(item)}>属性模板</button>
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
          {/*
            value 全程用**字符串**：真实类目 id 是 18 位雪花值，
            经 number 中转会丢精度（T-39）。仅哨兵 TOP_PARENT='0' 是普通短值。
            这里刻意不做 Number() —— select 的 value 本来就是字符串，直接用即可。
          */}
          <select
            className="input"
            value={form.parentId ?? TOP_PARENT}
            onChange={(e) => setForm({ ...form, parentId: e.target.value })}
          >
            <option value={TOP_PARENT}>（顶级）</option>
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

      <Modal
        open={attrCategory !== null}
        title={`类目属性模板 · ${attrCategory?.name ?? ''}`}
        onClose={closeAttrs}
        wide
        footer={<button className="btn btn-ghost" onClick={closeAttrs}>关闭</button>}
      >
        <div className="row-between row-wrap" style={{ marginBottom: 8 }}>
          <span className="small muted">
            模板只做发布 / 改规格时的校验（必填、数字格式、单选取值）；属性值仍保存在商品的规格参数里。
          </span>
          <button className="btn btn-primary btn-sm" onClick={openAttrCreate}>+ 新增属性</button>
        </div>

        {attrError ? <Alert kind="error">{attrError}</Alert> : null}

        {attrsLoading ? (
          <LoadingBlock text="正在加载属性模板…" />
        ) : attrs.length === 0 ? (
          <Empty
            title="该类目还没有属性模板"
            desc="新增属性后，商家发布该分类商品时会按它校验；不填必填项将无法发布"
          />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>属性名</th><th>类型</th><th>必填</th><th>选项</th><th>排序</th><th>状态</th>
                <th style={{ width: 200 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {attrs.map((attr) => (
                <tr key={attr.id}>
                  <td>{attr.name}</td>
                  <td className="small muted">{ATTR_TYPE_LABEL[attr.inputType] ?? attr.inputType}</td>
                  <td>{attr.required ? '必填' : '选填'}</td>
                  <td className="small muted">{(attr.options ?? []).join(' / ') || '—'}</td>
                  <td>{attr.sortNo}</td>
                  <td>{attr.status === 'INACTIVE' ? <span className="muted">已停用</span> : '启用中'}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <button className="btn btn-ghost btn-sm" onClick={() => openAttrEdit(attr)}>编辑</button>
                      {attr.status === 'INACTIVE' ? (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggleAttr(attr, true)}>启用</button>
                      ) : (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggleAttr(attr, false)}>停用</button>
                      )}
                      <button className="btn btn-ghost btn-sm" onClick={() => removeAttr(attr)}>删除</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {attrFormOpen ? (
          <div className="card card-pad" style={{ marginTop: 12 }}>
            <h4 style={{ marginTop: 0 }}>
              {editingAttr ? `编辑属性 · ${editingAttr.name}` : '新增属性'}
            </h4>
            <Field label="属性名" required hint="商家规格参数里同名的条目会按此模板校验；最多 30 字">
              <input
                className="input"
                value={attrForm.name}
                maxLength={30}
                onChange={(e) => setAttrForm({ ...attrForm, name: e.target.value })}
              />
            </Field>
            <Field label="类型">
              <select
                className="input"
                value={attrForm.inputType}
                onChange={(e) => setAttrForm({
                  ...attrForm,
                  inputType: e.target.value as CategoryAttrSaveRequest['inputType'],
                })}
              >
                <option value="TEXT">文本</option>
                <option value="NUMBER">数字</option>
                <option value="SELECT">单选（从选项里选）</option>
              </select>
            </Field>
            {attrForm.inputType === 'SELECT' ? (
              <Field label="选项" required hint="用逗号分隔，例如：幼犬，成犬，老年犬">
                <input
                  className="input"
                  value={attrOptionsText}
                  onChange={(e) => setAttrOptionsText(e.target.value)}
                  placeholder="幼犬，成犬，老年犬"
                />
              </Field>
            ) : null}
            <Field label="排序号" hint="越小越靠前">
              <input
                className="input"
                type="number"
                value={attrForm.sortNo ?? 0}
                onChange={(e) => setAttrForm({ ...attrForm, sortNo: Number(e.target.value) })}
              />
            </Field>
            <label className="row" style={{ gap: 6, marginBottom: 12 }}>
              <input
                type="checkbox"
                checked={attrForm.required ?? false}
                onChange={(e) => setAttrForm({ ...attrForm, required: e.target.checked })}
              />
              <span>必填（商家发布该分类商品时必须填写）</span>
            </label>
            <div className="row" style={{ gap: 8 }}>
              <button
                className="btn btn-primary"
                disabled={attrBusy || !attrForm.name.trim()}
                onClick={saveAttr}
              >
                {attrBusy ? '保存中…' : '保存属性'}
              </button>
              <button
                className="btn btn-ghost"
                onClick={() => {
                  setAttrFormOpen(false);
                  setEditingAttr(null);
                }}
              >
                取消
              </button>
            </div>
          </div>
        ) : null}
      </Modal>

      {/* 第三批 · 第 8 项：主数据导入必须走 dry-run，先看清逐行计划再落库 */}
      <CatalogImportModal
        open={importOpen}
        title="批量导入类目（CSV / xlsx）"
        hint={
          <>
            列顺序：名称、父类目、排序号、图标。父类目填 <b>ID</b> 或<b>顶级类目名称</b>，
            留空或 0 表示顶级；只支持两级，本文件新建的父类目行请写在子类目之前。
            同名且同父类目视为已存在，只更新排序号 / 图标（留空列 = 不改）。
          </>
        }
        template={adminApi.categoryImportTemplate}
        templateXlsxUrl={adminApi.categoryImportTemplateXlsxUrl()}
        dryRun={adminApi.checkCategoryImport}
        submit={adminApi.importCategories}
        onImported={reload}
        onClose={() => setImportOpen(false)}
      />
    </div>
  );
};
