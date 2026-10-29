/**
 * 店员管理页（E13，店主侧）：邀请 / 停用 / 启用 / 权限配置（F-04）。
 *
 * <p>准入 OWNER-only（后端 403 兜底）；店员打开本页会看到"店员账号不可管理成员"。
 */
import React, { useCallback, useMemo, useRef, useState } from 'react';
import { merchantStaffApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { BatchResultView, StaffPermission, StaffView } from '@duoduo/api-sdk/types';
import { STAFF_PERMISSION_LABELS } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { useAuth } from '@duoduo/shared/store/auth';
import { SellerDenied } from './shared';

/**
 * 最小 CSV 解析（2026-10-28 · 员工批量导入）。
 *
 * <p>只处理导入模板需要的那部分 RFC 4180：引号包裹、双引号转义、CRLF 与 BOM。
 * 引入一个 CSV 库只为一个两列表格不值得；这里 30 行，且行为完全可预期。
 */
function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  const src = text.replace(/^\uFEFF/, '');
  for (let i = 0; i < src.length; i += 1) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else if (ch !== '\r') {
      cell += ch;
    }
  }
  if (cell.length || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows.filter((r) => r.some((c) => c.trim() !== ''));
}

/** 本店成员人数很少（个位数到几十），接口一次性返回全量 —— 因此搜索与分页在本地做。
 *  与服务端分页的页面写法一致（同样的 SearchBar / Pagination），只是数据源不同。 */
const PAGE_SIZE = 10;

const ALL_PERMISSIONS = Object.keys(STAFF_PERMISSION_LABELS) as StaffPermission[];

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

  // F-04 · 权限配置弹窗
  const [permTarget, setPermTarget] = useState<StaffView | null>(null);
  const [permDraft, setPermDraft] = useState<StaffPermission[]>([]);
  const [permBusy, setPermBusy] = useState(false);
  const [permError, setPermError] = useState<string | null>(null);

  /** 批量导入 / 导出 / 批量启停（2026-10-28 · 导入导出第一批） */
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

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
  // 店主本人不可停用（服务端也会拒），批量选择里直接不提供
  const selectableIds = rows.filter((m) => m.role !== 'OWNER').map((m) => m.memberId);
  const selection = useBatchSelection(selectableIds, `${keyword}|${current}`);

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

  /**
   * 导入员工 CSV：第一行是表头（手机号 / 权限），之后每行一位员工。
   *
   * <p>解析与提交都在前端完成、服务端逐行邀请并返回逐行结果 ——
   * 失败的手机号（未注册 / 已在别店）会带着**原表行号**列在结果弹窗里，
   * 店主改完那几行再导一次即可，不用把整张表重新对一遍。
   */
  const handleImportFile = async (file: File) => {
    setError(null);
    setImportBusy(true);
    try {
      const cells = parseCsv(await file.text());
      if (cells.length < 2) {
        setError('文件里没有数据行：第一行应为表头，从第二行开始填写员工手机号');
        return;
      }
      const payload = cells.slice(1).map((row, index) => ({
        rowNo: index + 2,
        phone: (row[0] ?? '').trim(),
        // 权限列用分号分隔；空 = 不提交（服务端按默认全量权限邀请）
        permissions: (row[1] ?? '')
          .split(';')
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean),
      })).filter((row) => row.phone !== '');
      if (payload.length === 0) {
        setError('没有读到手机号，请确认第一列是手机号');
        return;
      }
      const result = await merchantStaffApi.importRows(
        payload.map((row) => ({
          rowNo: row.rowNo,
          phone: row.phone,
          permissions: row.permissions.length ? row.permissions : undefined,
        })),
      );
      setBatchResult(result);
      reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setImportBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const submitBatchStatus = async (enabled: boolean) => {
    if (!selection.count || batchBusy) return;
    setError(null);
    setBatchBusy(true);
    try {
      const result = await merchantStaffApi.batchStatus(selection.selected, enabled);
      setBatchResult(result);
      selection.clear();
      reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBatchBusy(false);
    }
  };

  const openPermissions = (m: StaffView) => {
    setPermTarget(m);
    setPermDraft(m.permissions ?? []);
    setPermError(null);
  };

  const savePermissions = async () => {
    if (!permTarget) return;
    setPermBusy(true);
    setPermError(null);
    try {
      await merchantStaffApi.updatePermissions(permTarget.memberId, permDraft);
      push(permDraft.length === 0 ? '已设为「仅查看」' : '权限已更新，立即生效', 'success');
      setPermTarget(null);
      reload();
    } catch (err) {
      setPermError(toFriendlyMessage(err));
    } finally {
      setPermBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">店员管理</h2>

      <Alert kind="info">
        店员用<b>自己的账号</b>登录即可进入本店工作台，处理订单与商品；
        <b>结算与提现信息对店员不可见</b>。停用即时生效。一个账号只能属于一家店。
        <br />
        每名店员可以单独配置操作权限（例如「只能发货、不能改价」），修改后立即生效。
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
        <div className="row row-wrap" style={{ gap: 8, marginTop: 12, alignItems: 'center' }}>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void handleImportFile(file);
            }}
          />
          <button
            className="btn btn-ghost btn-sm"
            disabled={importBusy}
            onClick={() => fileRef.current?.click()}
          >
            {importBusy ? '导入中…' : '批量导入（CSV）'}
          </button>
          <a className="btn btn-ghost btn-sm" href={merchantStaffApi.importTemplateUrl()}>
            下载导入模板
          </a>
          <a className="btn btn-ghost btn-sm" href={merchantStaffApi.exportUrl()}>
            导出员工 CSV
          </a>
          <span className="small muted">
            模板两列：手机号、权限（分号分隔；留空 = 默认全部权限）
          </span>
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
        <label className="checkbox-row small" style={{ marginLeft: 'auto' }}>
          <input
            type="checkbox"
            checked={selection.allSelected}
            disabled={!selectableIds.length}
            onChange={selection.toggleAll}
          />
          全选当前页店员
        </label>
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
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-primary btn-sm"
              disabled={batchBusy}
              onClick={() => void submitBatchStatus(true)}
            >
              批量启用
            </button>
            <button
              className="btn btn-danger btn-sm"
              disabled={batchBusy}
              onClick={() => void submitBatchStatus(false)}
            >
              批量停用
            </button>
          </BatchBar>
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 36 }}>
                  <input
                    type="checkbox"
                    aria-label="全选当前页店员"
                    checked={selection.allSelected}
                    disabled={!selectableIds.length}
                    onChange={selection.toggleAll}
                  />
                </th>
                <th>成员</th>
                <th>手机号</th>
                <th>身份</th>
                <th>状态</th>
                <th>权限</th>
                <th>加入时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.memberId}>
                  <td>
                    {m.role === 'OWNER' ? (
                      <span className="small muted">—</span>
                    ) : (
                      <input
                        type="checkbox"
                        aria-label={`选择店员 ${m.nickname}`}
                        checked={selection.selectedSet.has(m.memberId)}
                        disabled={batchBusy}
                        onChange={() => selection.toggle(m.memberId)}
                      />
                    )}
                  </td>
                  <td>{m.nickname}</td>
                  <td>{m.phoneMasked}</td>
                  <td>{m.role === 'OWNER' ? '店主' : '店员'}</td>
                  <td><span className={statusTagClass(m.status)}>{m.status === 'ACTIVE' ? '启用' : '已停用'}</span></td>
                  <td>
                    {m.role === 'OWNER' ? (
                      <span className="small muted">全部权限</span>
                    ) : (m.permissions ?? []).length === 0 ? (
                      <span className="tag">仅查看</span>
                    ) : (
                      <span className="small">
                        {(m.permissions ?? []).map((p) => STAFF_PERMISSION_LABELS[p] ?? p).join('、')}
                      </span>
                    )}
                  </td>
                  <td>{m.createdAt ? formatDate(m.createdAt) : '—'}</td>
                  <td>
                    {m.role === 'OWNER' ? (
                      <span className="small muted">—</span>
                    ) : (
                      <div className="row" style={{ gap: 6 }}>
                        <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => openPermissions(m)}>
                          权限
                        </button>
                        {m.status === 'ACTIVE' ? (
                          <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void toggle(m)}>
                            停用
                          </button>
                        ) : (
                          <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void toggle(m)}>
                            启用
                          </button>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={current} pageCount={pageCount} total={filtered.length} onChange={setPage} />
        </div>
        </div>
      ) : null}

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="成员 / 行号" />

      <Modal
        open={Boolean(permTarget)}
        title={`配置权限 · ${permTarget?.nickname ?? ''}`}
        onClose={() => setPermTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPermTarget(null)} disabled={permBusy}>
              取消
            </button>
            <button className="btn btn-primary" onClick={() => void savePermissions()} disabled={permBusy}>
              {permBusy ? '保存中…' : '保存权限'}
            </button>
          </>
        }
      >
        {permError ? (
          <div style={{ marginBottom: 12 }}>
            <Alert kind="error">{permError}</Alert>
          </div>
        ) : null}
        <div className="small muted" style={{ marginBottom: 12 }}>
          勾选该店员可以执行的操作；<b>全部不勾 = 仅查看</b>（能进工作台看订单 / 商品 / 客服，但不能做任何写操作）。
          修改后立即生效，无需店员重新登录。
        </div>
        <div className="row" style={{ gap: 8, marginBottom: 12 }}>
          <button
            className="btn btn-sm btn-ghost"
            onClick={() => setPermDraft([...ALL_PERMISSIONS])}
            disabled={permBusy}
          >
            全选
          </button>
          <button className="btn btn-sm btn-ghost" onClick={() => setPermDraft([])} disabled={permBusy}>
            清空（仅查看）
          </button>
        </div>
        <div style={{ display: 'grid', gap: 10 }}>
          {ALL_PERMISSIONS.map((p) => (
            <label key={p} className="row" style={{ gap: 8, alignItems: 'center', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={permDraft.includes(p)}
                onChange={(e) => {
                  setPermDraft((prev) => (e.target.checked
                    ? [...prev, p]
                    : prev.filter((x) => x !== p)));
                }}
              />
              <span>{STAFF_PERMISSION_LABELS[p]}</span>
            </label>
          ))}
        </div>
      </Modal>
    </div>
  );
};
