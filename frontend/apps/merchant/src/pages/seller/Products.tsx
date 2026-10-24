/**
 * 商家端 · 商品管理（M9 商品侧 + E14 上架审核）。
 *
 * 数据来源：GET /api/seller/products + POST /{spuId}/status + POST /{spuId}/resubmit
 *          + POST /api/seller/products（发布）+ GET /{spuId}（编辑回填）
 *
 * 三个口径：
 *  · 这里看到的是<b>归属自己店铺的商品</b>（t_spu.seller_id = 店铺主体）——
 *    店主与店员看到的是同一批，店员以店铺主体行动，前端不重复做归属过滤；
 *  · <b>交易维度（在售 / 下架）与审核维度（待审 / 通过 / 驳回）是两件事</b>，
 *    所以列表里是两列、筛选是两组标签：一件商品完全可以「已过审但被自己下架」，
 *    也会出现「驳回件等卖家改内容」；
 *  · <b>上架要过审，内容变更要再审</b>：这两条会让卖家困惑（「我点了上架怎么没反应」
 *    「我就改了个标题怎么商品不见了」），所以提示语必须说清下一步，而不是只说「不行」。
 */

import React, { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { sellerApi } from '@duoduo/api-sdk/endpoints';
import type { PageResult, SellerProductDetailView, SellerProductView } from '@duoduo/api-sdk/types';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import {
  Alert,
  Empty,
  Field,
  LoadingBlock,
  Modal,
  Pagination,
  SearchBar,
  formatPrice,
  useToast,
} from '@duoduo/shared/components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { ImageUploader } from '@duoduo/shared/components/ImageUploader';
import { KeyValueEditor, RichTextEditor, type KvItem } from '@duoduo/shared/components/RichText';
import { ProductPublishModal } from './ProductPublishModal';
import { useAuth } from '@duoduo/shared/store/auth';

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/** 审核维度筛选标签（与交易维度分开的第二组标签） */
const AUDIT_TABS: { key: string; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'PENDING', label: '待审核' },
  { key: 'APPROVED', label: '已通过' },
  { key: 'REJECTED', label: '已驳回' },
];

interface EditDraft {
  title: string;
  subtitle: string;
  mainImage: string;
  images: string[];
  detail: string;
  /** 商品介绍（纯文本，2026-10-24） */
  intro: string;
  /** 富文本详情 HTML */
  detailHtml: string;
  specParams: KvItem[];
  packaging: KvItem[];
  /** SKU 价格（元，字符串）与库存（字符串）—— 独立于内容保存，不触发重审 */
  skuPrices: Record<string, { price: string; stock: string }>;
  /** SKU id → 规格名（表格首列展示） */
  skuLabels: Record<string, string>;
}

export const SellerProductsPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const [params, setParams] = useSearchParams();

  const keyword = params.get('keyword') ?? '';
  const auditStatus = params.get('auditStatus') ?? '';
  const page = num(params.get('page')) ?? 1;
  const allowed = canAccessSellerConsole(user?.role);

  const [busyId, setBusyId] = useState<string | null>(null);
  const [keywordDraft, setKeywordDraft] = useState(keyword);
  /** 请求用防抖值；URL 里的 keyword 只在首次进入时作为初值（deep link） */
  const keywordQuery = useDebounced(keywordDraft.trim(), 350);
  const [publishing, setPublishing] = useState(false);
  const [editing, setEditing] = useState<SellerProductView | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  const [draftLoading, setDraftLoading] = useState(false);
  /** 图片上传/转存进行中 —— 保存按钮必须一起禁用，否则会存下「图还没进列表」的版本。
   *  两个上传器按字段名分开记：单个布尔量会被「一个传完、另一个还在传」覆盖掉。 */
  const [uploadBusy, setUploadBusy] = useState<Record<string, boolean>>({});
  const uploading = Object.values(uploadBusy).some(Boolean);
  /** 改价/改库存提交中（与内容保存互斥，避免两个请求互相覆盖） */
  const [pricingBusy, setPricingBusy] = useState(false);
  /** 打开编辑时的 SKU 基准值：用来判断「哪几行真的改了」，只提交差异 */
  const [skuBase, setSkuBase] = useState<Record<string, { price: number; stock: number }>>({});

  const fetcher = useCallback(
    () =>
      sellerApi.products({
        keyword: keywordQuery || undefined,
        auditStatus: auditStatus || undefined,
        page,
        pageSize: 20,
      }),
    [keywordQuery, auditStatus, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<SellerProductView>>(
    fetcher,
    allowed,
    [allowed, keywordQuery, auditStatus, page],
  );

  const setQuery = (patch: Record<string, string | undefined>, replace = false) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next, { replace });
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

  const resubmit = async (item: SellerProductView) => {
    setBusyId(item.id);
    try {
      await sellerApi.resubmitProduct(item.id);
      push(`「${item.title}」已重新提交，请等待平台审核`, 'success');
      reload();
    } catch (err) {
      push(err instanceof Error ? err.message : '提交失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const closeEdit = () => {
    setEditing(null);
    setDraft(null);
  };

  /**
   * 打开编辑弹窗：**必须拉一次详情**才知道副标题 / 详情图 / 详情正文
   * （列表项只带摘要字段）。同时这一步也是「卖家能读回自己被驳回的商品」的入口。
   */
  const openEdit = async (item: SellerProductView) => {
    setEditing(item);
    setDraft(null);
    setDraftLoading(true);
    try {
      const detail: SellerProductDetailView = await sellerApi.productDetail(item.id);
      const skuPrices: Record<string, { price: string; stock: string }> = {};
      const base: Record<string, { price: number; stock: number }> = {};
      const labels: Record<string, string> = {};
      for (const sku of detail.skus ?? []) {
        skuPrices[sku.id] = { price: (sku.price / 100).toFixed(2), stock: String(sku.stock) };
        base[sku.id] = { price: sku.price, stock: sku.stock };
        labels[sku.id] = sku.specName || '默认规格';
      }
      setSkuBase(base);
      setDraft({
        title: detail.title ?? '',
        subtitle: detail.subtitle ?? '',
        mainImage: detail.mainImage ?? '',
        images: detail.images ?? [],
        detail: detail.detail ?? '',
        intro: detail.intro ?? '',
        detailHtml: detail.detailHtml ?? '',
        specParams: detail.specParams ?? [],
        packaging: detail.packaging ?? [],
        skuPrices,
        skuLabels: labels,
      });
    } catch (err) {
      push(err instanceof Error ? err.message : '读取商品详情失败', 'error');
      setEditing(null);
    } finally {
      setDraftLoading(false);
    }
  };

  const submitEdit = async () => {
    if (!editing || !draft) return;
    const title = draft.title.trim();
    if (!title) {
      push('商品标题不能为空', 'error');
      return;
    }
    const mainImage = draft.mainImage.trim();
    if (!mainImage) {
      push('商品主图不能为空', 'error');
      return;
    }

    // 「用户没填」= 不改：该字段根本不出现在请求体里（服务端按三态语义处理）。
    // 直接传空串会把卖家的原有内容清掉 —— 那是线上事故，不是容错。
    const body: Record<string, unknown> = {};
    if (title !== editing.title) body.title = title;
    if (mainImage !== (editing.mainImage ?? '')) body.mainImage = mainImage;

    const detailItem: SellerProductDetailView = await sellerApi.productDetail(editing.id).catch(() => null as never);
    const originals = detailItem
      ? {
          subtitle: detailItem.subtitle ?? '',
          detail: detailItem.detail ?? '',
          images: detailItem.images ?? [],
          intro: detailItem.intro ?? '',
          detailHtml: detailItem.detailHtml ?? '',
          specParams: detailItem.specParams ?? [],
          packaging: detailItem.packaging ?? [],
        }
      : null;
    if (originals) {
      if (draft.subtitle.trim() !== originals.subtitle) body.subtitle = draft.subtitle.trim();
      if (draft.detail.trim() !== originals.detail) body.detail = draft.detail.trim();
      if (draft.images.join('\n') !== originals.images.join('\n')) body.images = draft.images;
      // 2026-10-24 新增内容字段：与原文比对后才提交（避免"点保存就重审"）
      if (draft.intro.trim() !== originals.intro) body.intro = draft.intro.trim();
      if (draft.detailHtml.trim() !== originals.detailHtml) body.detailHtml = draft.detailHtml.trim();
      const kvKey = (list: KvItem[]) => list.filter((x) => x.name.trim() || x.value.trim())
        .map((x) => `${x.name.trim()}=${x.value.trim()}`).join('\u0001');
      if (kvKey(draft.specParams) !== kvKey(originals.specParams)) body.specParams = draft.specParams;
      if (kvKey(draft.packaging) !== kvKey(originals.packaging)) body.packaging = draft.packaging;
    }

    if (Object.keys(body).length === 0) {
      push('没有需要保存的修改', 'info');
      return;
    }

    setBusyId(editing.id);
    try {
      await sellerApi.updateProduct(editing.id, body);
      push('商品已更新。内容变更已重新提交审核，审核通过后自动恢复展示', 'success');
      closeEdit();
      reload();
    } catch (err) {
      push(err instanceof Error ? err.message : '保存失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  /**
   * 保存价格与库存（2026-10-24）。
   *
   * 与「保存内容」分开：内容变更要重审，价格/库存不重审（行业通行口径）。
   * 只提交真正变化的行，避免无意义的写库与「点一下就改更新时间」。
   */
  const submitPrices = async () => {
    if (!editing || !draft) return;
    const changed: { id: string; price?: number; stock?: number }[] = [];
    for (const [id, value] of Object.entries(draft.skuPrices)) {
      const base = skuBase[id];
      if (!base) continue;
      const yuan = Number(value.price);
      const stock = Number(value.stock);
      if (!Number.isFinite(yuan) || yuan <= 0) {
        push('每个规格都要填写大于 0 的价格', 'error');
        return;
      }
      if (!Number.isInteger(stock) || stock < 0) {
        push('库存必须是不小于 0 的整数', 'error');
        return;
      }
      const cents = Math.round(yuan * 100);
      if (cents > 9_999_900) {
        push('价格超出上限（单个规格最高 99999 元）', 'error');
        return;
      }
      if (cents !== base.price || stock !== base.stock) {
        changed.push({ id, price: cents, stock });
      }
    }
    if (changed.length === 0) {
      push('价格与库存没有变化', 'info');
      return;
    }
    setPricingBusy(true);
    try {
      const detail = await sellerApi.updateSkuPriceStock(editing.id, { skus: changed });
      // 用服务端返回的结果刷新基准与表单，避免「保存成功但界面还是旧价」
      const nextBase: Record<string, { price: number; stock: number }> = {};
      const nextForm: Record<string, { price: string; stock: string }> = {};
      for (const sku of detail.skus ?? []) {
        nextBase[sku.id] = { price: sku.price, stock: sku.stock };
        nextForm[sku.id] = { price: (sku.price / 100).toFixed(2), stock: String(sku.stock) };
      }
      setSkuBase(nextBase);
      setDraft((d) => (d ? { ...d, skuPrices: nextForm } : d));
      push('价格与库存已更新（不会触发重新审核）', 'success');
      reload();
    } catch (err) {
      push(err instanceof Error ? err.message : '保存失败，请重试', 'error');
    } finally {
      setPricingBusy(false);
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
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <span className="section-sub">{data ? `共 ${total} 件商品` : ''}</span>
          <button className="btn btn-primary btn-sm" onClick={() => setPublishing(true)}>
            + 发布商品
          </button>
        </div>
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordDraft}
          onChange={(v) => {
            setKeywordDraft(v);
            // 每敲一个字只替换当前历史项，不 push —— 否则「后退」只能退掉一个字符
            setQuery({ keyword: v.trim() || undefined, page: undefined }, true);
          }}
          busy={loading && Boolean(keywordDraft.trim())}
          placeholder="搜商品标题"
          width={300}
        />
        {keywordDraft.trim() ? (
          <span className="small muted">匹配「{keywordDraft.trim()}」的商品 {total} 件</span>
        ) : null}
      </div>

      {/* 审核维度筛选：与交易维度是两个维度，不能合并成一组标签 */}
      <div className="row" style={{ marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
        {AUDIT_TABS.map((tab) => (
          <button
            key={tab.key || 'all'}
            className={`btn btn-sm ${auditStatus === tab.key ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setQuery({ auditStatus: tab.key || undefined, page: undefined })}
          >
            {tab.label}
          </button>
        ))}
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
                  <th>交易状态</th>
                  <th>审核</th>
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
                            loading="lazy"
                            decoding="async"
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
                      <span
                        className="tag"
                        style={
                          item.auditStatus === 'REJECTED'
                            ? { color: '#c0392b', borderColor: '#c0392b' }
                            : undefined
                        }
                      >
                        {item.auditLabel}
                      </span>
                      {item.auditStatus === 'REJECTED' && item.auditReason ? (
                        <div className="small muted" style={{ marginTop: 4, maxWidth: 220 }}>
                          驳回原因：{item.auditReason}
                        </div>
                      ) : null}
                    </td>
                    <td>
                      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                        <button
                          className="btn btn-ghost btn-sm"
                          disabled={busyId === item.id}
                          onClick={() => openEdit(item)}
                        >
                          编辑
                        </button>
                        {item.auditStatus === 'REJECTED' ? (
                          <button
                            className="btn btn-sm btn-primary"
                            disabled={busyId === item.id}
                            onClick={() => resubmit(item)}
                          >
                            重新提交
                          </button>
                        ) : null}
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
          title={keyword || auditStatus ? '没有匹配的商品' : '名下还没有商品'}
          desc={
            keyword || auditStatus
              ? '换个关键词或切换审核状态试试。'
              : '点右上角「发布商品」提交第一件商品，平台审核通过后自动上架。'
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
            <button
              className="btn btn-ghost"
              disabled={busyId !== null || pricingBusy || !draft || uploading}
              onClick={submitPrices}
            >
              {pricingBusy ? '保存价格中…' : '保存价格/库存'}
            </button>
            <button
              className="btn btn-primary"
              disabled={busyId !== null || pricingBusy || !draft || uploading}
              onClick={submitEdit}
            >
              {busyId !== null ? '保存中…' : uploading ? '图片上传中…' : '保存内容'}
            </button>
          </>
        }
      >
        {draftLoading || !draft ? (
          <LoadingBlock text="正在读取商品…" />
        ) : (
          <div>
            <Field label="商品标题">
              <input
                className="input"
                value={draft.title}
                maxLength={60}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                placeholder="最多 60 个字"
              />
            </Field>
            <Field label="副标题">
              <input
                className="input"
                value={draft.subtitle}
                maxLength={60}
                onChange={(e) => setDraft({ ...draft, subtitle: e.target.value })}
                placeholder="选填"
              />
            </Field>
            <Field label="主图" hint="支持本地上传，也可粘贴图片地址；主图是列表页唯一的视觉锚点，不能为空">
              <ImageUploader
                value={draft.mainImage ? [draft.mainImage] : []}
                onChange={(next) => setDraft({ ...draft, mainImage: next[0] ?? '' })}
                max={1}
                size={88}
                onBusyChange={(busy) => setUploadBusy((m) => ({ ...m, main: busy }))}
                addLabel="上传主图"
                urlPlaceholder="或粘贴主图地址（如 /img/products/xxx.svg）"
                hint=""
              />
            </Field>
            <Field label="详情图（可选，最多 9 张）" hint="支持本地多选上传；也可粘贴地址后点「添加地址」">
              <ImageUploader
                value={draft.images}
                onChange={(next) => setDraft({ ...draft, images: next })}
                max={9}
                size={72}
                onBusyChange={(busy) => setUploadBusy((m) => ({ ...m, detail: busy }))}
                addLabel="上传详情图"
                hint=""
              />
            </Field>
            <Field label="商品介绍" hint="详情页「商品介绍」区展示的短文本（选填）">
              <textarea
                className="input"
                rows={3}
                maxLength={2000}
                value={draft.intro}
                onChange={(e) => setDraft({ ...draft, intro: e.target.value })}
                placeholder="用一段话介绍这件商品的特点"
              />
            </Field>
            <Field label="商品详情（富文本）" hint="支持标题、加粗、列表与插图；保存后前台按富文本渲染">
              <RichTextEditor
                value={draft.detailHtml}
                onChange={(html) => setDraft({ ...draft, detailHtml: html })}
              />
            </Field>
            <Field label="价格与库存" hint="改价、改库存不会触发重新审核；保存后立即对买家生效">
              <div className="stack" style={{ gap: 8 }}>
                {Object.keys(draft.skuPrices).length === 0 ? (
                  <span className="small muted">该商品没有可修改的规格</span>
                ) : (
                  <table className="table" style={{ width: '100%' }}>
                    <thead>
                      <tr>
                        <th>规格</th>
                        <th style={{ width: 140 }}>价格（元）</th>
                        <th style={{ width: 120 }}>库存</th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(draft.skuPrices).map(([id, value]) => (
                        <tr key={id}>
                          <td>{draft.skuLabels[id] ?? '默认规格'}</td>
                          <td>
                            <input
                              className="input"
                              value={value.price}
                              inputMode="decimal"
                              onChange={(e) =>
                                setDraft({
                                  ...draft,
                                  skuPrices: { ...draft.skuPrices, [id]: { ...value, price: e.target.value } },
                                })
                              }
                            />
                          </td>
                          <td>
                            <input
                              className="input"
                              value={value.stock}
                              inputMode="numeric"
                              onChange={(e) =>
                                setDraft({
                                  ...draft,
                                  skuPrices: { ...draft.skuPrices, [id]: { ...value, stock: e.target.value } },
                                })
                              }
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </Field>
            <Field label="规格参数" hint="如 品牌 / 净含量 / 适用阶段；最多 20 条">
              <KeyValueEditor
                value={draft.specParams}
                onChange={(next) => setDraft({ ...draft, specParams: next })}
                namePlaceholder="参数名（如 净含量）"
                valuePlaceholder="参数值（如 2kg）"
              />
            </Field>
            <Field label="包装规格" hint="如 包装形式 / 单包重量 / 箱规；最多 20 条">
              <KeyValueEditor
                value={draft.packaging}
                onChange={(next) => setDraft({ ...draft, packaging: next })}
                namePlaceholder="规格名（如 包装形式）"
                valuePlaceholder="规格值（如 袋装）"
              />
            </Field>
            <p className="small muted" style={{ marginTop: 8 }}>
              保存后商品会<b>重新进入审核</b>（审核结论是针对修改前那段内容作出的），
              审核期间前台不可见、审核通过后自动恢复展示。价格与库存牵动 SKU 与在途订单金额，
              不在编辑范围内。
            </p>
          </div>
        )}
      </Modal>

      <ProductPublishModal
        open={publishing}
        onClose={() => setPublishing(false)}
        onSuccess={() => {
          setQuery({ auditStatus: 'PENDING', page: undefined });
          reload();
        }}
      />
    </div>
  );
};
