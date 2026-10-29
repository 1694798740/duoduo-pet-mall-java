/**
 * 运营后台 · 主数据导入弹窗（类目 / 品牌共用；CSV / xlsx 同口径）。
 *
 * 流程固定为三步：下载模板 → 预演（dry-run，不落库） → 正式导入。
 * 为什么强制先预演：类目 / 品牌是商品的公共主数据，一次写错会波及大量商品，
 * 而商品导入错了只需要删几件商品。预演让运营在落库前看到「哪些会新建、哪些会改、哪些行有错」。
 *
 * 文件读取与上传都在前端完成：CSV 读成文本、xlsx 读成 Base64，服务端只收 `{ content, fileName }`，
 * 与商品 / 价格库存导入同一套信封。
 */

import React, { useState } from 'react';
import type { BatchResultView, CatalogCsvView, CatalogImportPlanView } from '@duoduo/api-sdk/types';
import { Alert, Modal, useToast } from '@duoduo/shared/components/ui';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { readImportFile } from '@duoduo/shared/lib/import-file';

/** Blob 的 MIME 必须带 charset：Windows 上双击 CSV 会用 GBK 解码，中文全乱码 */
function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const ACTION_LABEL: Record<string, string> = {
  CREATE: '将新建',
  UPDATE: '将更新',
  SKIP: '跳过',
  ERROR: '有错误',
};

const ACTION_COLOR: Record<string, string> = {
  CREATE: '#1e8e3e',
  UPDATE: '#b26a00',
  SKIP: '#6b6b6b',
  ERROR: '#c0392b',
};

interface Props {
  open: boolean;
  title: string;
  /** 模板列与匹配口径说明 */
  hint: React.ReactNode;
  template: () => Promise<CatalogCsvView>;
  /** xlsx 模板下载地址（二进制附件，直接导航下载，不在前端读成文本） */
  templateXlsxUrl: string;
  dryRun: (body: { content: string; fileName?: string }) => Promise<CatalogImportPlanView>;
  submit: (body: { content: string; fileName?: string }) => Promise<BatchResultView>;
  /** 正式导入后刷新列表（只要有成功行就调用） */
  onImported: () => void;
  onClose: () => void;
}

export const CatalogImportModal: React.FC<Props> = ({
  open, title, hint, template, templateXlsxUrl, dryRun, submit, onImported, onClose,
}) => {
  const { push } = useToast();
  const [fileName, setFileName] = useState('');
  const [content, setContent] = useState('');
  const [plan, setPlan] = useState<CatalogImportPlanView | null>(null);
  const [result, setResult] = useState<BatchResultView | null>(null);
  const [busy, setBusy] = useState<'check' | 'import' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setFileName('');
    setContent('');
    setPlan(null);
    setResult(null);
    setBusy(null);
    setError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const pickFile = async (file: File | null) => {
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      setError('文件过大（上限 2MB），请拆分后导入');
      return;
    }
    try {
      const payload = await readImportFile(file);
      setFileName(payload.fileName);
      setContent(payload.content);
      // 换文件后旧的预演结论不再适用，必须重新预演才能正式导入
      setPlan(null);
      setResult(null);
      setError(null);
    } catch {
      setError('读取文件失败，请确认文件未损坏');
    }
  };

  const downloadTemplate = async () => {
    try {
      const file = await template();
      downloadCsv(file.filename, file.content);
    } catch (err) {
      push(err instanceof Error ? err.message : '模板下载失败，请重试', 'error');
    }
  };

  const runCheck = async () => {
    if (!content) {
      setError('请先选择 CSV 或 xlsx 文件');
      return;
    }
    setBusy('check');
    setError(null);
    setResult(null);
    try {
      const next = await dryRun({ content, fileName: fileName || undefined });
      setPlan(next);
      push(
        `预演完成：新建 ${next.createCount} 行，更新 ${next.updateCount} 行，跳过 ${next.skipCount} 行`
        + (next.failedCount ? `，${next.failedCount} 行有错误` : ''),
        next.failedCount ? 'error' : 'success',
      );
    } catch (err) {
      setPlan(null);
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(null);
    }
  };

  const runImport = async () => {
    if (!content || !plan) return;
    setBusy('import');
    setError(null);
    try {
      const next = await submit({ content, fileName: fileName || undefined });
      setResult(next);
      setPlan(null);
      if (next.successCount > 0) {
        push(
          `导入完成：成功 ${next.successCount} 行${next.failedCount ? `，失败 ${next.failedCount} 行` : ''}`,
          next.failedCount ? 'error' : 'success',
        );
        onImported();
      } else {
        push('没有一行导入成功，请按下方原因修正后重试', 'error');
      }
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      wide
      onClose={close}
      footer={
        <>
          <button className="btn btn-ghost" onClick={close}>关闭</button>
          <button className="btn btn-ghost" disabled={!content || busy !== null} onClick={runCheck}>
            {busy === 'check' ? '预演中…' : '预演（不落库）'}
          </button>
          <button className="btn btn-primary" disabled={!plan || busy !== null} onClick={runImport}>
            {busy === 'import' ? '导入中…' : '确认导入'}
          </button>
        </>
      }
    >
      <Alert kind="info">{hint}</Alert>

      <div className="row" style={{ gap: 10, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
        <input
          type="file"
          accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          aria-label="选择 CSV 或 xlsx 文件"
          onChange={(e) => {
            void pickFile(e.target.files?.[0] ?? null);
            e.target.value = '';
          }}
        />
        <button className="btn btn-ghost btn-sm" onClick={downloadTemplate}>下载 CSV 模板</button>
        <a className="btn btn-ghost btn-sm" href={templateXlsxUrl}>xlsx 模板</a>
        <span className="small muted">
          {fileName ? `已选择：${fileName}` : '支持 UTF-8 CSV / xlsx，最大 2MB，500 行'}
        </span>
      </div>

      {error ? <div style={{ marginTop: 10 }}><Alert kind="error">{error}</Alert></div> : null}

      {plan ? (
        <div style={{ marginTop: 14 }}>
          <Alert kind={plan.failedCount === 0 ? 'info' : 'warn'}>
            共 {plan.total} 行：将新建 {plan.createCount} 行、更新 {plan.updateCount} 行、跳过 {plan.skipCount} 行
            {plan.failedCount ? `、${plan.failedCount} 行有错误（正式导入时这些行会失败，其余行照常）` : ''}。
            预演没有修改任何数据，确认无误后再点「确认导入」。
          </Alert>
          <table className="table" style={{ marginTop: 10 }}>
            <thead>
              <tr>
                <th style={{ width: 70 }}>行号</th>
                <th style={{ width: 90 }}>动作</th>
                <th style={{ width: 180 }}>名称</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {plan.items.map((entry) => (
                <tr key={`plan-${entry.lineNo}`}>
                  <td>{entry.lineNo}</td>
                  <td>
                    <span className="tag" style={{ color: ACTION_COLOR[entry.action] ?? undefined }}>
                      {ACTION_LABEL[entry.action] ?? entry.action}
                    </span>
                  </td>
                  <td>{entry.name || '—'}</td>
                  <td style={{ wordBreak: 'break-all' }}>
                    {entry.message}
                    {entry.targetId ? <span className="small muted">（ID {entry.targetId}）</span> : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {result ? (
        <div style={{ marginTop: 14 }}>
          <Alert kind={result.failedCount === 0 ? 'success' : result.successCount === 0 ? 'error' : 'warn'}>
            共 {result.total} 行：成功 {result.successCount} 行，失败 {result.failedCount} 行。
            成功行已落库，失败行未写入任何数据。
          </Alert>
          <table className="table" style={{ marginTop: 10 }}>
            <thead>
              <tr>
                <th style={{ width: 70 }}>行号</th>
                <th style={{ width: 120 }}>结果</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((entry) => (
                <tr key={`result-${entry.id}`}>
                  <td>{entry.id}</td>
                  <td>
                    {entry.success ? (
                      <span className="tag">成功</span>
                    ) : (
                      <span className="tag" style={{ color: '#c0392b' }}>{entry.errorCode ?? '失败'}</span>
                    )}
                  </td>
                  <td style={{ wordBreak: 'break-all' }}>{entry.message ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </Modal>
  );
};
