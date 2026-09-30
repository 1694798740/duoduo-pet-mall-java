/**
 * 商家端 · 商品管理（M9 商品侧）。
 *
 * 数据来源：GET /api/seller/products?keyword=&page=&pageSize= + POST /{spuId}/status
 *
 * 两个口径：
 *  · 这里看到的是<b>归属自己的商品</b>（t_spu.seller_id），运营 / 管理员可见全部 ——
 *    与订单侧同一套准入模型，前端不重复做归属过滤；
 *  · 上下架是<b>幂等开关</b>：重复点击、并发点击都安全，失败时以服务端返回为准再刷新。
 *
 * 本期刻意不做「新建商品」：那涉及类目 / 品牌 / SKU / 价格 / 库存的完整表单与审核，
 * 属于商家入驻运营流程（见 docs/PLAN.md 的排期说明）。
 */

import React, { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { sellerApi } from '../../api/endpoints';
import type { PageResult, SellerProductView } from '../../api/types';
import { useAsync } from '../../components/hooks';
import {
  Alert,
  Empty,
  Field,
  LoadingBlock,
  Modal,
  Pagination,
  formatPrice,
  useToast,
} from '../../components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '../../store/auth';

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

export const SellerProductsPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const [params, setParams] = useSearchParams();

  const keyword = params.get('keyword') ?? '';
  const page = num(params.get('page')) ?? 1;
  const allowed = canAccessSellerConsole(user?.role);

  const [busyId, setBusyId] = useState<string | null>(null);
  const [keywordDraft, setKeywordDraft] = useState(keyword);
  const [editing, setEditing] = useState<SellerProductView | null>(null);
  const [titleDraft, setTitleDraft] = useState('');
  const [imageDraft, setImageDraft] = useState('');

  const fetcher = useCallback(
    () => sellerApi.products({ keyword: keyword || undefined, page, pageSize: 20 }),
    [keyword, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<SellerProductView>>(
    fetcher,
    allowed,
    [allowed, keyword, page],
  );

  const setQuery = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next);
  };

  const toggle = async (item: SellerProductView) => {
    const action = item.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE';
    setBusyId(item.id);
    try {
      await sellerApi.changeProductStatus(item.id, action);
      push(
        action === 'ON_SALE'
          ? `「${item.title}」已上架，买家端立即可见`
          : `「${item.title}」已下架，买家端不再展示`,
        'success',
      );
      reload();
    } catch (err) {
      push(err instanceof Error ? err.message : '操作失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const closeEdit = () => setEditing(null);

  const openEdit = (item: SellerProductView) => {
    setEditing(item);
    setTitleDraft(item.title);
    setImageDraft(item.mainImage ?? '');
  };

  const submitEdit = async () => {
    if (!editing) return;
    const title = titleDraft.trim();
    if (!title) {
      push('商品标题不能为空', 'error');
      return;
    }
    // 「用户没填」= 不改：该字段根本不出现在请求体里（服务端按三态语义处理）
    const body: { title?: string; mainImage?: string } = {};
    if (title !== editing.title) body.title = title;
    const image = imageDraft.trim();
    if (image !== (editing.mainImage ?? '')) body.mainImage = image;
    if (!body.title && body.mainImage === undefined) {
      push('没有需要保存的修改', 'info');
      return;
    }
    setBusyId(editing.id);
    try {
      await sellerApi.updateProduct(editing.id, body);
      push('商品已更新，买家端列表随之刷新', 'success');
      closeEdit();
      reload();
    } catch (err) {
      push(err instanceof Error ? err.message : '保存失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  if (!allowed) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">商家端 · 商品管理</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">商品管理</h2>
        <span className="section-sub">{data ? `共 ${total} 件商品` : ''}</span>
      </div>

      <div className="row" style={{ marginBottom: 16, gap: 8 }}>
        <input
          className="input"
          style={{ maxWidth: 320 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') setQuery({ keyword: keywordDraft.trim() || undefined, page: undefined });
          }}
          placeholder="按商品标题搜索"
          maxLength={60}
        />
        <button
          className="btn btn-primary btn-sm"
          onClick={() => setQuery({ keyword: keywordDraft.trim() || undefined, page: undefined })}
        >
          搜索
        </button>
        {keyword ? (
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              setKeywordDraft('');
              setQuery({ keyword: undefined, page: undefined });
            }}
          >
            清除
          </button>
        ) : null}
      </div>

      {error ? (
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
      ) : null}

      {loading && !data ? (
        <LoadingBlock text="正在加载商品…" />
      ) : items.length ? (
        <>
          <div className="card card-pad" style={{ padding: 0, overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr>
                  <th>商品</th>
                  <th style={{ textAlign: 'right' }}>价格区间</th>
                  <th style={{ textAlign: 'right' }}>SKU</th>
                  <th style={{ textAlign: 'right' }}>库存</th>
                  <th style={{ textAlign: 'right' }}>销量</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                        {item.mainImage ? (
                          <img
                            src={item.mainImage}
                            alt={item.title}
                            style={{ width: 40, height: 40, borderRadius: 8, objectFit: 'cover' }}
                          />
                        ) : (
                          <span style={{ fontSize: 22 }}>🐾</span>
                        )}
                        <span style={{ maxWidth: 320, wordBreak: 'break-all' }}>{item.title}</span>
                      </div>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {formatPrice(item.minPrice)}
                      {item.maxPrice > item.minPrice ? ` ~ ${formatPrice(item.maxPrice)}` : ''}
                    </td>
                    <td style={{ textAlign: 'right' }}>{item.skuCount}</td>
                    <td style={{ textAlign: 'right' }}>{item.totalStock}</td>
                    <td style={{ textAlign: 'right' }}>{item.salesCount}</td>
                    <td>
                      <span className="tag">{item.statusLabel}</span>
                    </td>
                    <td>
                      <div className="row" style={{ gap: 8 }}>
                        <button
                          className="btn btn-ghost btn-sm"
                          disabled={busyId === item.id}
                          onClick={() => openEdit(item)}
                        >
                          编辑
                        </button>
                        <button
                          className={`btn btn-sm ${item.status === 'ON_SALE' ? 'btn-ghost' : 'btn-primary'}`}
                          disabled={busyId === item.id}
                          onClick={() => toggle(item)}
                        >
                          {busyId === item.id ? '处理中…' : item.status === 'ON_SALE' ? '下架' : '上架'}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <Pagination
            page={data ? data.page : page}
            pageCount={pageCount}
            total={total}
            onChange={(value) => setQuery({ page: String(value) })}
          />
        </>
      ) : (
        <Empty
          icon="📦"
          title={keyword ? '没有匹配的商品' : '名下还没有商品'}
          desc={
            keyword
              ? '换个关键词试试，或清除筛选条件。'
              : '第三方商家商品由入驻运营流程创建；平台自营商品对运营账号可见。'
          }
        />
      )}
      <Modal
        open={editing !== null}
        title="编辑商品"
        onClose={closeEdit}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeEdit}>
              取消
            </button>
            <button className="btn btn-primary" disabled={busyId !== null} onClick={submitEdit}>
              {busyId !== null ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        {editing ? (
          <div>
            <Field label="商品标题">
              <input
                className="input"
                value={titleDraft}
                maxLength={60}
                onChange={(e) => setTitleDraft(e.target.value)}
                placeholder="最多 60 个字"
              />
            </Field>
            <Field label="主图地址">
              <input
                className="input"
                value={imageDraft}
                maxLength={500}
                onChange={(e) => setImageDraft(e.target.value)}
                placeholder="留空表示不显示图片"
              />
            </Field>
            <p className="small muted" style={{ marginTop: 8 }}>
              只开放标题与主图：价格 / 库存牵动 SKU 与在途订单金额，不做成随手可改的字段。
              标题改完后由检索同步任务收敛到搜索结果，可能有短暂延迟。
            </p>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
