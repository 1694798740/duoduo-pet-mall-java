/**
 * 平台侧商家成员总览（E13）：跨租户列出全部成员，支持平台停用 / 启用（风控处置）。
 *
 * <p>入口刻意不进顶栏（宽度上限），走路由直达；后续若有运营动线需求再并入概览待办卡。
 */
import React, { useCallback, useState } from 'react';
import { adminMerchantStaffApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { StaffAdminView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, LoadingBlock, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, isOperator } from './shared';

export const AdminMerchantStaffPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();
  const [page, setPage] = useState(1);
  const [busy, setBusy] = useState(false);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const { data, loading, error: loadError, reload } = useAsync(
    useCallback(
      () => adminMerchantStaffApi.page({ keyword: keyword || undefined, page, pageSize: 10 }),
      [keyword, page],
    ),
    operator,
    [keyword, page],
  );

  if (!operator) return <AdminDenied />;

  const toggle = async (m: StaffAdminView) => {
    setBusy(true);
    try {
      await (m.status === 'ACTIVE'
        ? adminMerchantStaffApi.disable(m.memberId)
        : adminMerchantStaffApi.enable(m.memberId));
      push(m.status === 'ACTIVE' ? '已停用（即时生效）' : '已启用', 'success');
      reload();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 商家成员总览</h2>

      <Alert kind="info">
        跨租户列出全部店铺成员（店员 + 店主）。平台停用<b>即时生效</b>且写入审计
        （ADMIN_DISABLE_STAFF，与店主自己停用可区分）。处置店主账号请走封禁流程，
        本页不允许操作 OWNER。
      </Alert>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜店铺名或手机号"
          width={300}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的成员 {data ? data.total : 0} 人</span>
        ) : null}
        <a
          className="btn btn-sm btn-ghost"
          style={{ marginLeft: 'auto' }}
          href={adminMerchantStaffApi.exportUrl({ keyword: keyword || undefined })}
        >
          导出 CSV
        </a>
      </div>

      {loadError ? <Alert kind="error">{loadError}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载成员…" /> : null}
      {!loading && data && data.items.length === 0 ? (
        <Empty
          icon="👥"
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的成员` : '暂无成员'}
          desc="有商家邀请店员后会出现在这里"
        />
      ) : null}
      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>所属店铺</th>
                <th>成员</th>
                <th>手机号</th>
                <th>身份</th>
                <th>状态</th>
                <th>加入时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((m) => (
                <tr key={m.memberId}>
                  <td>{m.storeName}</td>
                  <td>{m.userId}</td>
                  <td>{m.phoneMasked}</td>
                  <td>{m.role === 'OWNER' ? '店主' : '店员'}</td>
                  <td>
                    <span className={`tag ${m.status === 'DISABLED' ? 'tag-danger' : ''}`}>
                      {m.status === 'ACTIVE' ? '启用' : '已停用'}
                    </span>
                  </td>
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
          <Pagination
            page={page}
            pageCount={Math.max(1, data.pageCount)}
            total={data.total}
            onChange={setPage}
          />
        </div>
      ) : null}
    </div>
  );
};
