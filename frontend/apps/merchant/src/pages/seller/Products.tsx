/**
 * 商家端 · 商品管理（M9 商品侧 + E14 上架审核 + A-09 回收站 + A-13 批量上下架）。
 *
 * 数据来源：GET /api/seller/products + POST /{spuId}/status + POST /{spuId}/resubmit
 *          + POST /api/seller/products（发布）+ GET /{spuId}（编辑回填）
 *          + POST /{spuId}/delete（软删）+ POST /{spuId}/restore（恢复）
 *          + POST /api/seller/products/batch-status（批量，逐条结果）
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

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { sellerApi, catalogApi } from '@duoduo/api-sdk/endpoints';
import { ApiError } from '@duoduo/api-sdk/client';
import type {
  BatchResultView,
  CategoryAttrView,
  PageResult,
  SellerProductBatchResultView,
  SellerProductDetailView,
  SellerProductImportResultView,
  SellerProductUpdateRequest,
  SellerProductView,
  StatusCount,
} from '@duoduo/api-sdk/types';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { Badge, UnreadDot, toCountMap } from '@duoduo/shared/components/badge';
import { applyBatchDismissLocally, applyOptimisticDismiss } from '@duoduo/shared/lib/badge-rules';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { readImportFile } from '@duoduo/shared/lib/import-file';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { RichTextView } from '@duoduo/shared/components/RichText';
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

/**
 * 规格结构编辑的一行（A-14）。
 * `id` 为空 = 本次新增；`key` 只用于 React 列表，与后端 id 无关。
 */
interface SpecDraftRow {
  key: string;
  id: string | null;
  specName: string;
  /** 元，字符串（提交前 ×100 转分） */
  price: string;
  stock: string;
}

let specKeySeq = 0;
const nextSpecKey = () => `spec-${++specKeySeq}`;

/** 时间展示：回收站要让人看清「什么时候删的、什么时候会被清掉」 */
function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 触发浏览器下载（A-17）。
 *
 * <p>后端把 CSV 文本装在 JSON 信封里返回（统一 401 续期与错误文案），
 * 下载这一步只能在前端做：Blob 的 MIME 必须带 charset=utf-8，
 * 否则 Windows 上双击打开会用 GBK 解码，中文标题全是乱码。
 */
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

export const SellerProductsPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const confirm = useConfirm();
  const [params, setParams] = useSearchParams();

  const keyword = params.get('keyword') ?? '';
  const auditStatus = params.get('auditStatus') ?? '';
  /** A-09：回收站是独立视图，不与「我的商品」混排 */
  const inRecycle = params.get('deleted') === '1';
  const page = num(params.get('page')) ?? 1;
  const allowed = canAccessSellerConsole(user?.role);

  const [busyId, setBusyId] = useState<string | null>(null);
  const [keywordDraft, setKeywordDraft] = useState(keyword);
  /** 请求用防抖值；URL 里的 keyword 只在首次进入时作为初值（deep link） */
  const keywordQuery = useDebounced(keywordDraft.trim(), 350);
  const [publishing, setPublishing] = useState(false);
  const [editing, setEditing] = useState<SellerProductView | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  /** 打开编辑时读到的详情快照：既是「原始值」比对基准，也是提交时的乐观锁版本来源。
   *  保存时不再二次拉详情 —— 否则版本号会变成"此刻最新"，A-07 的并发保护就被绕过了。 */
  const [editDetail, setEditDetail] = useState<SellerProductDetailView | null>(null);
  const [draftLoading, setDraftLoading] = useState(false);
  /** 图片上传/转存进行中 —— 保存按钮必须一起禁用，否则会存下「图还没进列表」的版本。
   *  两个上传器按字段名分开记：单个布尔量会被「一个传完、另一个还在传」覆盖掉。 */
  const [uploadBusy, setUploadBusy] = useState<Record<string, boolean>>({});
  const uploading = Object.values(uploadBusy).some(Boolean);
  /** 改价/改库存提交中（与内容保存互斥，避免两个请求互相覆盖） */
  const [pricingBusy, setPricingBusy] = useState(false);
  /** 打开编辑时的 SKU 基准值：用来判断「哪几行真的改了」，只提交差异 */
  const [skuBase, setSkuBase] = useState<Record<string, { price: number; stock: number }>>({});
  /** A-10 · 该商品所属类目的属性模板（编辑期若换了类目是换不了的，模板固定） */
  const [editTemplateAttrs, setEditTemplateAttrs] = useState<CategoryAttrView[]>([]);
  /** A-13 · 批量选择只作用于**当前页**：切页、筛选、搜索都会清空，避免误操作看不见的商品 */
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchResult, setBatchResult] = useState<SellerProductBatchResultView | null>(null);
  /** 结果弹窗里要显示商品标题，但那时列表可能已经 reload —— 先按 id 快照住 */
  const [batchTitles, setBatchTitles] = useState<Record<string, string>>({});
  /** A-14 · 规格结构弹窗（与内容编辑分开：结构变更要重审，价格/库存不重审） */
  const [specEditing, setSpecEditing] = useState<SellerProductView | null>(null);
  const [specRows, setSpecRows] = useState<SpecDraftRow[]>([]);
  /** 打开弹窗时读到的版本与原始规格：版本用于乐观锁，原始规格用于判断「结构是否真的变了」 */
  const [specVersion, setSpecVersion] = useState(0);
  const [specOriginal, setSpecOriginal] = useState<Record<string, { specName: string }>>({});
  const [specMerchant, setSpecMerchant] = useState(false);
  const [specLoading, setSpecLoading] = useState(false);
  const [specBusy, setSpecBusy] = useState(false);
  /** SKU id → 订单数；只含被订单引用过的规格（服务端口径） */
  const [specUsage, setSpecUsage] = useState<Record<string, number>>({});
  /** 占用查询失败时不能假装「都可以删」 */
  const [specUsageUnknown, setSpecUsageUnknown] = useState(false);
  /** A-17 · CSV 导入 / 导出 */
  const [csvBusy, setCsvBusy] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importFileName, setImportFileName] = useState('');
  const [importContent, setImportContent] = useState('');
  const [importResult, setImportResult] = useState<SellerProductImportResultView | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  /** 第三批 · 价格/库存导入：与商品导入分开一个弹窗，结果结构也不同（逐行 BatchResult） */
  const [psOpen, setPsOpen] = useState(false);
  const [psFileName, setPsFileName] = useState('');
  const [psContent, setPsContent] = useState('');
  const [psResult, setPsResult] = useState<BatchResultView | null>(null);
  const [psBusy, setPsBusy] = useState(false);

  const fetcher = useCallback(
    () =>
      sellerApi.products({
        keyword: keywordQuery || undefined,
        auditStatus: auditStatus || undefined,
        deleted: inRecycle || undefined,
        page,
        pageSize: 20,
      }),
    [keywordQuery, auditStatus, inRecycle, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<SellerProductView>>(
    fetcher,
    allowed,
    [allowed, keywordQuery, auditStatus, inRecycle, page],
  );

  /**
   * 审核页签角标（2026-10-27）：服务端 GROUP BY audit_status，覆盖全量而非当前页。
   *
   * <p>回收站视图不显示这组页签，所以也不拉数 —— 回收站里的商品不参与日常管理，
   * 把它们算进「待审核」会让卖家永远清不掉那个红点。
   */
  const auditCountsState = useAsync<StatusCount[]>(
    useCallback(() => sellerApi.productAuditCounts(), []),
    allowed,
  );
  /** 角标本地态：允许乐观减，之后一律以服务端分桶覆盖 */
  const [auditCounts, setAuditCounts] = useState<Record<string, number>>({});
  useEffect(() => {
    setAuditCounts(toCountMap(auditCountsState.data));
  }, [auditCountsState.data]);
  const reloadAuditCounts = auditCountsState.reloadSilently;

  /**
   * 逐条已读的本地覆盖（2026-10-27 · 橙色未读印记）。
   *
   * <p>列表数据来自 `useAsync`（服务端快照）；点开预览只把这一条加入集合，
   * 不整页重拉 —— 重拉会闪一下，也会把用户正在看的滚动位置打回顶部。
   * 切筛选/翻页时清空，由服务端回传的 `read` 字段重新决定橙点。
   */
  const [readIds, setReadIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    setReadIds(new Set());
  }, [page, keywordQuery, auditStatus, inRecycle]);
  const isRead = (item: SellerProductView) => item.read === true || readIds.has(item.id);

  /** 预览卡片（2026-10-27）：点卡片先看轻量预览，不直接进编辑弹窗 */
  const [preview, setPreview] = useState<SellerProductView | null>(null);
  /** 预览时才拉全量详情（列表 DTO 保持轻量）：副标题/类目品牌/全部图片/SKU/正文 */
  const [previewDetail, setPreviewDetail] = useState<SellerProductDetailView | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  /** 请求序号：快速切换商品时丢弃过期的详情响应 */
  const previewSeq = useRef(0);

  const closePreview = () => {
    previewSeq.current += 1;
    setPreview(null);
    setPreviewDetail(null);
    setPreviewLoading(false);
  };

  /**
   * 打开商品 = 看过：上报已读，「已通过」角标随之消掉（阅后即消）。
   *
   * <p>规则：橙点对所有审核态都消失（看过了）；数字角标只有 APPROVED 可消 ——
   * PENDING（等审核）与 REJECTED（要改）读过也必须留着。
   * 失败不本地硬减，回滚到服务端值。
   */
  const dismissAuditBadge = useCallback(
    async (item: SellerProductView) => {
      if (isRead(item)) return;
      // 橙点：任何审核态都算「看过」（打开预览即已读）
      setReadIds((prev) => new Set(prev).add(item.id));
      // 数字角标：只有「已通过」可消（待审核 / 已驳回是还没落定的事，读过也必须留着）
      const dismissible = item.auditStatus === 'APPROVED';
      if (dismissible) {
        setAuditCounts((prev) => applyOptimisticDismiss(prev, item.auditStatus));
      }
      try {
        const rows = await sellerApi.productMarkRead(item.id);
        if (dismissible) setAuditCounts(toCountMap(rows));
        notifyBadgeChanged();
      } catch {
        // 上报失败：撤掉橙点覆盖，回滚到服务端值（不制造「看过但其实没上报」的假状态）
        setReadIds((prev) => {
          const next = new Set(prev);
          next.delete(item.id);
          return next;
        });
        if (dismissible) void reloadAuditCounts();
      }
    },
    [reloadAuditCounts, readIds],
  );

  /**
   * 打开预览卡片 = 已读（原子操作）。
   *
   * <p>「渲染预览」与「上报已读」必须在同一个入口里完成 —— 放进两个按钮的
   * onClick 里迟早漏一处，就会出现「看了但角标不动」或「角标动了但没看」。
   */
  const openPreview = (item: SellerProductView) => {
    setPreview(item);
    setPreviewDetail(null);
    setPreviewLoading(true);
    void dismissAuditBadge(item);
    // 列表只带摘要，预览要展示的类目/品牌/全部图片/SKU/正文在详情接口里；
    // 延迟加载避免为了预览把所有字段塞进列表响应体（列表是最高频路径）。
    const seq = previewSeq.current + 1;
    previewSeq.current = seq;
    sellerApi
      .productDetail(item.id)
      .then((detail) => {
        if (seq === previewSeq.current) setPreviewDetail(detail);
      })
      .catch(() => {
        // 详情失败仍可用列表摘要展示，不把预览整个挡住
      })
      .finally(() => {
        if (seq === previewSeq.current) setPreviewLoading(false);
      });
  };

  /**
   * 列表 + 审核角标一起刷。
   *
   * <p>商品页的写操作有 10 处（提交 / 上架 / 下架 / 删除 / 恢复 / 批量 / 导入…），
   * 每一处都单独记得「顺手刷一下角标」是不现实的 —— 漏一处就是「列表已变、角标没变」。
   * 统一收口到这一个函数，写操作只调它。
   */
  const reloadAll = useCallback(() => {
    // 注意：这里必须是 reload（列表），不能写成 reloadAll —— 那就是无限递归
    reload();
    reloadAuditCounts();
    // 上架/下架/删除/恢复都会改变审核分桶，父级菜单即时跟上
    notifyBadgeChanged();
  }, [reload, reloadAuditCounts]);

  const setQuery = (patch: Record<string, string | undefined>, replace = false) => {
    // 切页/筛选/搜索后，上一页的勾选不能再生效 —— 批量操作只认「眼前这一页」
    setSelectedIds([]);
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next, { replace });
  };

  // 列表刷新后剔除已不在当前页的勾选（例如刚被删除的商品），防止批量请求带上看不见的 id
  useEffect(() => {
    const pageIds = new Set((data?.items ?? []).map((item) => item.id));
    setSelectedIds((prev) => {
      const next = prev.filter((id) => pageIds.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [data]);

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  };

  const toggleSelectAll = () => {
    const pageIds = (data?.items ?? []).map((item) => item.id);
    const allSelected = pageIds.length > 0 && pageIds.every((id) => selectedIds.includes(id));
    setSelectedIds(allSelected ? [] : pageIds);
  };

  /**
   * 批量上下架（A-13）：服务端逐条执行、逐条返回结果，**不整体回滚**。
   *
   * 所有条目都走单品同一套规则（归属、审核、店铺资质），所以「部分失败」是常态：
   * 选中的商品里可能混着待审核、被驳回、已删除或不属于本店的条目。
   * 结果必须逐条列出来 —— 只报「成功 8 件」而不说哪 2 件失败，卖家只能挨个重点。
   */
  const runBatchStatus = async (action: 'ON_SALE' | 'OFF_SHELF') => {
    if (!selectedIds.length || batchBusy) return;
    const label = action === 'ON_SALE' ? '上架' : '下架';
    const ok = await confirm({
      title: action === 'ON_SALE' ? '批量上架' : '批量下架',
      message: (
        <>
          将尝试{label} <b>{selectedIds.length}</b> 件商品（仅当前页）。
          <br />
          {action === 'ON_SALE'
            ? '只有审核通过、店铺资质有效的商品会上架成功；未过审或无权操作的商品会逐条失败。'
            : '下架后买家端立即不可见；已下架的商品会视为成功。'}
          <br />
          部分失败不会回滚已成功的商品，结果会逐条列出。
        </>
      ),
      confirmText: `批量${label}`,
      danger: action === 'OFF_SHELF',
    });
    if (!ok) return;

    const titles: Record<string, string> = {};
    for (const item of data?.items ?? []) {
      if (selectedIds.includes(item.id)) titles[item.id] = item.title;
    }
    setBatchTitles(titles);
    setBatchBusy(true);
    try {
      const result = await sellerApi.batchChangeProductStatus(selectedIds, action);
      setBatchResult(result);

      /**
       * 批量操作即已读（2026-10-27）：
       * ① 只对**成功**条目清除橙点（失败条目没处理成，提醒必须保留）；
       * ② 角标优先用接口返回的最新分桶覆盖；旧后端未返回时，按成功条目的状态本地兜底扣减
       *    （同样只扣「可消」状态，且不会减到负数）；
       * ③ 勾选清空 → 批量操作栏自动收起、全选框取消。
       */
      const successIds = result.items.filter((entry) => entry.success).map((entry) => entry.id);
      if (successIds.length > 0) {
        setReadIds((prev) => {
          const next = new Set(prev);
          successIds.forEach((id) => next.add(id));
          return next;
        });
      }
      if (result.auditCounts) {
        setAuditCounts(toCountMap(result.auditCounts)); // 服务端权威值
      } else {
        const statusById = new Map((data?.items ?? []).map((item) => [item.id, item.auditStatus]));
        setAuditCounts((prev) => applyBatchDismissLocally(
          prev,
          successIds.map((id) => statusById.get(id)),
          (status) => status === 'APPROVED',
        ));
      }
      setSelectedIds([]);

      push(
        result.failedCount === 0
          ? `批量${label}完成：${result.successCount} 件全部成功`
          : `批量${label}完成：成功 ${result.successCount} 件，失败 ${result.failedCount} 件`,
        result.failedCount === 0 ? 'success' : 'error',
      );
      // 列表照常刷新（状态已变、未读优先会重新排序）；角标已由上面覆盖，不再重复拉
      reload();
      notifyBadgeChanged();
    } catch (err) {
      push(err instanceof Error ? err.message : `批量${label}失败，请重试`, 'error');
    } finally {
      setBatchBusy(false);
    }
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
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '操作失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  /**
   * 删除商品（A-09）：软删进回收站。
   *
   * 必须说清三件事，否则卖家会把它当成「永久删除」而不敢点、或删完以为再也找不回：
   * ① 买家端立即不可见；② 30 天内可恢复；③ 恢复后是下架态，需要自己重新上架。
   */
  const removeProduct = async (item: SellerProductView) => {
    const ok = await confirm({
      title: '删除商品',
      message: (
        <>
          「{item.title}」将移入回收站，买家端立即不可见。
          <br />
          30 天内可在这里恢复；恢复后为下架状态，需要你核对内容后重新上架。
        </>
      ),
      confirmText: '移入回收站',
      danger: true,
    });
    if (!ok) return;
    setBusyId(item.id);
    try {
      await sellerApi.deleteProduct(item.id);
      push(`「${item.title}」已移入回收站，30 天内可恢复`, 'success');
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '删除失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  /** 从回收站恢复（A-09）：恢复后保持下架，避免卖家删过的商品悄悄回到前台 */
  const restoreProduct = async (item: SellerProductView) => {
    setBusyId(item.id);
    try {
      await sellerApi.restoreProduct(item.id);
      push(`「${item.title}」已恢复为下架状态，请核对内容后重新上架`, 'success');
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '恢复失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  // ---------------- A-17 · 复制 / CSV 导入导出 ----------------

  /** 复制商品：副本默认下架，卖家核对后自行上架（服务端不会替卖家决定上架） */
  const copyProduct = async (item: SellerProductView) => {
    const ok = await confirm({
      title: '复制商品',
      message: (
        <>
          将以「{item.title}」的内容与规格新建一件商品。
          <br />
          副本<b>默认下架</b>，标题会追加「（副本）」；请核对库存与价格后再上架。
        </>
      ),
      confirmText: '复制',
    });
    if (!ok) return;
    setBusyId(item.id);
    try {
      const copied = await sellerApi.copyProduct(item.id);
      push(`已复制为「${copied.title}」，当前为下架状态，请核对后上架`, 'success');
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '复制失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const exportCsv = async () => {
    if (csvBusy) return;
    setCsvBusy(true);
    try {
      const file = await sellerApi.exportProductsCsv();
      downloadCsv(file.filename, file.content);
      push(`已导出 ${file.productCount} 件商品（${file.rowCount} 行规格）`, 'success');
    } catch (err) {
      push(err instanceof Error ? err.message : '导出失败，请重试', 'error');
    } finally {
      setCsvBusy(false);
    }
  };

  const openImport = () => {
    setImportFileName('');
    setImportContent('');
    setImportResult(null);
    setImportOpen(true);
  };

  const downloadTemplate = async () => {
    try {
      const file = await sellerApi.importProductTemplate();
      downloadCsv(file.filename, file.content);
    } catch (err) {
      push(err instanceof Error ? err.message : '模板下载失败，请重试', 'error');
    }
  };

  const pickImportFile = async (file: File | null) => {
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      push('文件过大（上限 2MB），请拆分后导入', 'error');
      return;
    }
    try {
      const payload = await readImportFile(file);
      setImportFileName(payload.fileName);
      setImportContent(payload.content);
      setImportResult(null);
    } catch {
      push('读取文件失败，请确认文件未损坏', 'error');
    }
  };

  const submitImport = async () => {
    if (!importContent) {
      push('请先选择 CSV 或 xlsx 文件', 'error');
      return;
    }
    setImportBusy(true);
    try {
      const result = await sellerApi.importProductsCsv({
        content: importContent,
        fileName: importFileName || undefined,
      });
      setImportResult(result);
      if (result.successCount > 0) {
        push(
          `导入完成：成功 ${result.successCount} 个商品${result.failedCount ? `，失败 ${result.failedCount} 个` : ''}`,
          result.failedCount ? 'error' : 'success',
        );
        reloadAll();
      } else {
        push('导入未成功，请按下方原因修正后重试', 'error');
      }
    } catch (err) {
      push(err instanceof Error ? err.message : '导入失败，请重试', 'error');
    } finally {
      setImportBusy(false);
    }
  };

  const openPriceStock = () => {
    setPsFileName('');
    setPsContent('');
    setPsResult(null);
    setPsOpen(true);
  };

  const downloadPriceStockTemplate = async () => {
    try {
      const file = await sellerApi.priceStockTemplate();
      downloadCsv(file.filename, file.content);
    } catch (err) {
      push(err instanceof Error ? err.message : '模板下载失败，请重试', 'error');
    }
  };

  const pickPriceStockFile = async (file: File | null) => {
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      push('文件过大（上限 2MB），请拆分后导入', 'error');
      return;
    }
    try {
      const payload = await readImportFile(file);
      setPsFileName(payload.fileName);
      setPsContent(payload.content);
      setPsResult(null);
    } catch {
      push('读取文件失败，请确认文件未损坏', 'error');
    }
  };

  const submitPriceStock = async () => {
    if (!psContent) {
      push('请先选择 CSV 或 xlsx 文件', 'error');
      return;
    }
    setPsBusy(true);
    try {
      const result = await sellerApi.importPriceStock({
        content: psContent,
        fileName: psFileName || undefined,
      });
      setPsResult(result);
      if (result.successCount > 0) {
        push(
          `改价改库存完成：成功 ${result.successCount} 行${result.failedCount ? `，失败 ${result.failedCount} 行` : ''}`,
          result.failedCount ? 'error' : 'success',
        );
        reloadAll();
      } else {
        push('没有一行导入成功，请按下方原因修正后重试', 'error');
      }
    } catch (err) {
      push(err instanceof Error ? err.message : '导入失败，请重试', 'error');
    } finally {
      setPsBusy(false);
    }
  };

  const resubmit = async (item: SellerProductView) => {
    setBusyId(item.id);
    try {
      await sellerApi.resubmitProduct(item.id);
      push(`「${item.title}」已重新提交，请等待平台审核`, 'success');
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '提交失败，请重试', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const closeEdit = () => {
    setEditing(null);
    setDraft(null);
    setEditDetail(null);
    setEditTemplateAttrs([]);
  };

  /**
   * 打开编辑弹窗：**必须拉一次详情**才知道副标题 / 详情图 / 详情正文
   * （列表项只带摘要字段）。同时这一步也是「卖家能读回自己被驳回的商品」的入口。
   */
  const openEdit = async (item: SellerProductView) => {
    setEditing(item);
    // 打开编辑 = 看过这条商品：上报已读（只有「已通过」会因此消角标）
    void dismissAuditBadge(item);
    setDraft(null);
    setEditDetail(null);
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
      setEditDetail(detail);
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
      // 模板失败不阻塞编辑：它只决定渲染与「改规格时」的端上预校验，服务端仍会兜底
      try {
        setEditTemplateAttrs(detail.categoryId
          ? await catalogApi.categoryAttrs(detail.categoryId)
          : []);
      } catch {
        setEditTemplateAttrs([]);
      }
    } catch (err) {
      push(err instanceof Error ? err.message : '读取商品详情失败', 'error');
      setEditing(null);
    } finally {
      setDraftLoading(false);
    }
  };

  // 模板属性与自定义 KV 共用 specParams（口径同发布弹窗）：模板项按属性名匹配，
  // 自定义编辑器只显示其余项，提交时仍是同一份 KV。
  const editTemplateNames = editTemplateAttrs.map((attr) => attr.name);
  const editCustomSpecs = draft
    ? draft.specParams.filter((item) => !editTemplateNames.includes(item.name.trim()))
    : [];
  const editSpecValueOf = (name: string) =>
    draft?.specParams.find((item) => item.name.trim() === name)?.value ?? '';
  const setEditSpecValue = (name: string, value: string) => {
    if (!draft) return;
    const index = draft.specParams.findIndex((item) => item.name.trim() === name);
    const specParams = index < 0
      ? [...draft.specParams, { name, value }]
      : draft.specParams.map((item, i) => (i === index ? { ...item, value } : item));
    setDraft({ ...draft, specParams });
  };
  const setEditCustomSpecs = (next: KvItem[]) => {
    if (!draft) return;
    const templateRows = draft.specParams.filter((item) => editTemplateNames.includes(item.name.trim()));
    setDraft({ ...draft, specParams: [...templateRows, ...next] });
  };

  const submitEdit = async () => {
    if (!editing || !draft || !editDetail) return;    const title = draft.title.trim();
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
    const body: Omit<SellerProductUpdateRequest, 'version'> = {};
    if (title !== editing.title) body.title = title;
    if (mainImage !== (editing.mainImage ?? '')) body.mainImage = mainImage;

    // 比对基准用**打开弹窗时**读到的快照：保存前再拉一次详情会把 version 刷成最新，
    // 恰好让「期间被别人改过」这件事被抹掉，乐观锁形同虚设。
    const originals = {
      subtitle: editDetail.subtitle ?? '',
      detail: editDetail.detail ?? '',
      images: editDetail.images ?? [],
      intro: editDetail.intro ?? '',
      detailHtml: editDetail.detailHtml ?? '',
      specParams: editDetail.specParams ?? [],
      packaging: editDetail.packaging ?? [],
    };
    if (draft.subtitle.trim() !== originals.subtitle) body.subtitle = draft.subtitle.trim();
    if (draft.detail.trim() !== originals.detail) body.detail = draft.detail.trim();
    if (draft.images.join('\n') !== originals.images.join('\n')) body.images = draft.images;
    // 2026-10-24 新增内容字段：与原文比对后才提交（避免"点保存就重审"）
    if (draft.intro.trim() !== originals.intro) body.intro = draft.intro.trim();
    if (draft.detailHtml.trim() !== originals.detailHtml) body.detailHtml = draft.detailHtml.trim();
    const kvKey = (list: KvItem[]) => list.filter((x) => x.name.trim() || x.value.trim())
      .map((x) => `${x.name.trim()}=${x.value.trim()}`).join('\u0001');
    const specsChanged = kvKey(draft.specParams) !== kvKey(originals.specParams);
    // A-10：只有「改了规格参数」才按模板校验 —— 只改标题的老商品不能被后来新增的必填项卡住
    if (specsChanged) {
      for (const attr of editTemplateAttrs) {
        const value = editSpecValueOf(attr.name).trim();
        if (attr.required && !value) {
          push(`请填写必填属性「${attr.name}」`, 'error');
          return;
        }
        if (!value) continue;
        if (attr.inputType === 'NUMBER' && !/^-?\d+(\.\d+)?$/.test(value)) {
          push(`属性「${attr.name}」必须是数字`, 'error');
          return;
        }
        if (attr.inputType === 'SELECT' && (attr.options?.length ?? 0) > 0
          && !(attr.options ?? []).includes(value)) {
          push(`属性「${attr.name}」只能选：${(attr.options ?? []).join(' / ')}`, 'error');
          return;
        }
      }
    }
    if (specsChanged) body.specParams = draft.specParams;
    if (kvKey(draft.packaging) !== kvKey(originals.packaging)) body.packaging = draft.packaging;

    if (Object.keys(body).length === 0) {
      push('没有需要保存的修改', 'info');
      return;
    }

    setBusyId(editing.id);
    try {
      await sellerApi.updateProduct(editing.id, { ...body, version: editDetail.version });
      push('商品已更新。内容变更已重新提交审核，审核通过后自动恢复展示', 'success');
      closeEdit();
      reloadAll();
    } catch (err) {
      // 409 = 期间被审核 / 被其他会话改过。绝不能自动刷新版本重试：
      // 那等于拿旧内容覆盖刚发生的变更，正是 A-07 要堵的整行覆盖。
      if (err instanceof ApiError && err.status === 409) {
        push('商品内容已被其他人修改，本次保存未生效。请关闭后重新打开编辑，确认最新内容再改', 'error');
      } else {
        push(err instanceof Error ? err.message : '保存失败，请重试', 'error');
      }
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
      reloadAll();
    } catch (err) {
      push(err instanceof Error ? err.message : '保存失败，请重试', 'error');
    } finally {
      setPricingBusy(false);
    }
  };

  // ---------------- A-14 · 规格结构（增 / 删 / 改名 / 改价） ----------------

  const closeSpecs = () => {
    setSpecEditing(null);
    setSpecRows([]);
    setSpecOriginal({});
    setSpecUsage({});
    setSpecUsageUnknown(false);
  };

  /**
   * 打开规格弹窗：拉详情拿最新版本与全部规格，再拉「订单占用」提前标出不可删的行。
   *
   * 占用查询失败不阻塞编辑（价格库存照常改），但必须显式提示「无法确认」——
   * 把查不到当成没有订单，等于诱导卖家删掉一个卖过的规格。
   */
  const openSpecs = async (item: SellerProductView) => {
    setSpecEditing(item);
    setSpecRows([]);
    setSpecOriginal({});
    setSpecUsage({});
    setSpecUsageUnknown(false);
    setSpecLoading(true);
    try {
      const detail = await sellerApi.productDetail(item.id);
      const skus = detail.skus ?? [];
      setSpecVersion(detail.version);
      setSpecMerchant(detail.supplyType === 'merchant');
      setSpecRows(
        skus.map((sku) => ({
          key: nextSpecKey(),
          id: sku.id,
          specName: sku.specName ?? '',
          price: (sku.price / 100).toFixed(2),
          stock: String(sku.stock),
        })),
      );
      const original: Record<string, { specName: string }> = {};
      for (const sku of skus) {
        original[sku.id] = { specName: sku.specName || '默认' };
      }
      setSpecOriginal(original);
      try {
        const usage = await sellerApi.productSkuUsage(item.id);
        const next: Record<string, number> = {};
        for (const entry of usage) next[entry.skuId] = entry.orderCount;
        setSpecUsage(next);
      } catch {
        setSpecUsageUnknown(true);
      }
    } catch (err) {
      push(err instanceof Error ? err.message : '读取商品详情失败', 'error');
      setSpecEditing(null);
    } finally {
      setSpecLoading(false);
    }
  };

  const patchSpecRow = (key: string, patch: Partial<SpecDraftRow>) => {
    setSpecRows((rows) => rows.map((row) => (row.key === key ? { ...row, ...patch } : row)));
  };

  const addSpecRow = () => {
    if (specRows.length >= 20) {
      push('最多支持 20 个规格', 'error');
      return;
    }
    setSpecRows((rows) => [...rows, {
      key: nextSpecKey(), id: null, specName: '', price: '', stock: '0',
    }]);
  };

  const removeSpecRow = (row: SpecDraftRow) => {
    if (specRows.length <= 1) {
      push('商品至少需要一个规格', 'error');
      return;
    }
    if (row.id && specUsage[row.id]) {
      push(`「${row.specName || '默认'}」已有 ${specUsage[row.id]} 笔订单，不能删除；可以改名后保留`, 'error');
      return;
    }
    setSpecRows((rows) => rows.filter((x) => x.key !== row.key));
  };

  const submitSpecs = async () => {
    if (!specEditing) return;
    if (specRows.length === 0) {
      push('商品至少需要一个规格', 'error');
      return;
    }
    const inputs: { id: string | null; specName: string; price: number; stock: number }[] = [];
    for (const row of specRows) {
      const yuan = Number(row.price);
      const stock = Number(row.stock);
      const name = row.specName.trim();
      if (name.length > 30) {
        push('规格名最多 30 个字', 'error');
        return;
      }
      if (!Number.isFinite(yuan) || yuan <= 0) {
        push('每个规格都要填写大于 0 的价格', 'error');
        return;
      }
      const cents = Math.round(yuan * 100);
      if (cents > 9_999_900) {
        push('价格超出上限（单个规格最高 99999 元）', 'error');
        return;
      }
      if (!Number.isInteger(stock) || stock < 0 || stock > 999_999) {
        push('库存必须是 0 ~ 999999 的整数', 'error');
        return;
      }
      inputs.push({ id: row.id, specName: name, price: cents, stock });
    }

    const keptIds = inputs.filter((x) => x.id).map((x) => x.id as string);
    const addedCount = inputs.filter((x) => !x.id).length;
    const removedCount = Object.keys(specOriginal).length - keptIds.length;
    const renamedCount = inputs.filter((x) => {
      const originalName = x.id ? specOriginal[x.id]?.specName : undefined;
      return originalName !== undefined && (x.specName || '默认') !== originalName;
    }).length;
    const structureChanged = addedCount > 0 || removedCount > 0 || renamedCount > 0;

    // 只有第三方商品会因结构变更重审：自营免审，不必拦一道无意义的确认
    if (structureChanged && specMerchant) {
      const parts: string[] = [];
      if (addedCount) parts.push(`新增 ${addedCount} 个`);
      if (removedCount) parts.push(`删除 ${removedCount} 个`);
      if (renamedCount) parts.push(`改规格名 ${renamedCount} 个`);
      const ok = await confirm({
        title: '保存规格结构',
        message: (
          <>
            本次将{parts.join('、')}。保存后商品会<b>重新进入审核</b>，审核期间前台不可见，
            审核通过后自动恢复展示。
            <br />
            已有订单的规格不能删除 —— 若下方标记了「已有订单」，请改名保留而不是删掉。
          </>
        ),
        confirmText: '保存并重新提审',
        danger: removedCount > 0,
      });
      if (!ok) return;
    }

    setSpecBusy(true);
    try {
      const result = await sellerApi.updateProductSkus(specEditing.id, {
        version: specVersion,
        skus: inputs,
      });
      const summary = `新增 ${result.added} / 修改 ${result.updated} / 删除 ${result.removed}`;
      push(
        result.reaudited
          ? `规格已保存（${summary}）。商品已重新进入审核，审核通过后前台恢复展示`
          : `规格已保存（${summary}）。价格与库存变更不会触发重新审核`,
        'success',
      );
      closeSpecs();
      reloadAll();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        push('商品已被其他人修改，本次保存未生效。请关闭后重新打开规格确认最新内容', 'error');
      } else {
        push(err instanceof Error ? err.message : '保存规格失败，请重试', 'error');
      }
    } finally {
      setSpecBusy(false);
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

  /**
   * 未读优先（2026-10-27）：后端已按「未读在前、组内按更新时间」排序；
   * 这里再做一次**稳定排序**，让刚点开预览的那一条立刻落回已读组（不必等刷新）。
   * 稳定排序保证同组内仍是服务端给的顺序。
   */
  const items = [...(data?.items ?? [])].sort(
    (a, b) => Number(isRead(a)) - Number(isRead(b)),
  );
  const total = data?.total ?? 0;
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const allSelected = items.length > 0 && items.every((item) => selectedIds.includes(item.id));
  const selectedCount = items.filter((item) => selectedIds.includes(item.id)).length;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">商品管理</h2>
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <span className="section-sub">
            {data ? `共 ${total} 件${inRecycle ? '已删除商品' : '商品'}` : ''}
          </span>
          {!inRecycle ? (
            <>
              <button className="btn btn-ghost btn-sm" onClick={openImport}>
                导入 CSV
              </button>
              <button className="btn btn-ghost btn-sm" onClick={openPriceStock}>
                导入价格库存
              </button>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => {
                  window.location.href = sellerApi.priceStockExportUrl({
                    keyword: keywordQuery || undefined,
                    auditStatus: auditStatus || undefined,
                  });
                }}
              >
                导出价格库存
              </button>
              <button className="btn btn-ghost btn-sm" disabled={csvBusy} onClick={exportCsv}>
                {csvBusy ? '导出中…' : '导出 CSV'}
              </button>
              <button className="btn btn-primary btn-sm" onClick={() => setPublishing(true)}>
                + 发布商品
              </button>
            </>
          ) : null}
        </div>
      </div>

      {/* A-09：商品 / 回收站是两个视图，不混排 —— 否则卖家分不清「这件还在卖吗」 */}
      <div className="row" style={{ marginBottom: 14, gap: 8, flexWrap: 'wrap' }}>
        <button
          className={`btn btn-sm ${!inRecycle ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => {
            setKeywordDraft('');
            setQuery({ deleted: undefined, auditStatus: undefined, keyword: undefined, page: undefined });
          }}
        >
          我的商品
        </button>
        <button
          className={`btn btn-sm ${inRecycle ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => {
            setKeywordDraft('');
            setQuery({ deleted: '1', auditStatus: undefined, keyword: undefined, page: undefined });
          }}
        >
          回收站
        </button>
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
        {inRecycle ? (
          <span className="small muted">删除后保留 30 天；到期由系统自动清理，无法恢复</span>
        ) : null}
      </div>

      {/* 审核维度筛选：与交易维度是两个维度，不能合并成一组标签；回收站不按审核态筛 */}
      {!inRecycle ? (
        <div className="row" style={{ marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
          {AUDIT_TABS.map((tab) => (
            <button
              key={tab.key || 'all'}
              className={`btn btn-sm ${auditStatus === tab.key ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setQuery({ auditStatus: tab.key || undefined, page: undefined })}
            >
              {tab.label}
              <Badge count={auditCounts[tab.key]} />
            </button>
          ))}
        </div>
      ) : null}

      {!inRecycle && selectedCount > 0 ? (
        <div
          className="card card-pad"
          style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}
        >
          <span className="small">
            已选 <b>{selectedCount}</b> 件（仅当前页）
          </span>
          <button
            className="btn btn-primary btn-sm"
            disabled={batchBusy}
            onClick={() => runBatchStatus('ON_SALE')}
          >
            {batchBusy ? '处理中…' : '批量上架'}
          </button>
          <button
            className="btn btn-ghost btn-sm"
            disabled={batchBusy}
            onClick={() => runBatchStatus('OFF_SHELF')}
          >
            批量下架
          </button>
          <button
            className="btn btn-ghost btn-sm"
            disabled={batchBusy}
            onClick={() => setSelectedIds([])}
          >
            清空选择
          </button>
        </div>
      ) : null}

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
                  {!inRecycle ? (
                    <th style={{ width: 40 }}>
                      <input
                        type="checkbox"
                        aria-label="全选本页"
                        checked={allSelected}
                        disabled={batchBusy}
                        onChange={toggleSelectAll}
                      />
                    </th>
                  ) : null}
                  <th>商品</th>
                  <th style={{ textAlign: 'right' }}>价格区间</th>
                  {inRecycle ? (
                    <>
                      <th>删除时间</th>
                      <th>预计清理</th>
                      <th>删除前审核态</th>
                      <th>操作</th>
                    </>
                  ) : (
                    <>
                      <th style={{ textAlign: 'right' }}>SKU</th>
                      <th style={{ textAlign: 'right' }}>库存</th>
                      <th style={{ textAlign: 'right' }}>销量</th>
                      <th>交易状态</th>
                      <th>审核</th>
                      <th>操作</th>
                    </>
                  )}
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr
                    key={item.id}
                    className={isRead(item) ? undefined : 'unread-card'}
                    onClick={() => openPreview(item)}
                    style={{ cursor: 'pointer' }}
                  >
                    {!inRecycle ? (
                      <td onClick={(e) => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          aria-label={`选择「${item.title}」`}
                          checked={selectedIds.includes(item.id)}
                          disabled={batchBusy}
                          onChange={() => toggleSelect(item.id)}
                        />
                      </td>
                    ) : null}
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
                        {!isRead(item) ? <UnreadDot /> : null}
                        <span style={{ maxWidth: 320, wordBreak: 'break-all' }}>{item.title}</span>
                      </div>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {formatPrice(item.minPrice)}
                      {item.maxPrice > item.minPrice ? ` ~ ${formatPrice(item.maxPrice)}` : ''}
                    </td>
                    {inRecycle ? (
                      <>
                        <td>{fmtTime(item.deletedAt)}</td>
                        <td>{fmtTime(item.purgeAt)}</td>
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
                        </td>
                        <td onClick={(e) => e.stopPropagation()}>
                          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                            <button
                              className="btn btn-primary btn-sm"
                              disabled={busyId === item.id}
                              onClick={() => restoreProduct(item)}
                            >
                              {busyId === item.id ? '恢复中…' : '恢复'}
                            </button>
                          </div>
                        </td>
                      </>
                    ) : (
                      <>
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
                        <td onClick={(e) => e.stopPropagation()}>
                          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                            <button
                              className="btn btn-ghost btn-sm"
                              disabled={busyId === item.id}
                              onClick={() => openEdit(item)}
                            >
                              编辑
                            </button>
                            <button
                              className="btn btn-ghost btn-sm"
                              disabled={busyId === item.id}
                              onClick={() => openSpecs(item)}
                            >
                              规格
                            </button>
                            <button
                              className="btn btn-ghost btn-sm"
                              disabled={busyId === item.id}
                              onClick={() => copyProduct(item)}
                            >
                              复制
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
                            <button
                              className="btn btn-ghost btn-sm"
                              style={{ color: '#c0392b' }}
                              disabled={busyId === item.id}
                              onClick={() => removeProduct(item)}
                            >
                              删除
                            </button>
                          </div>
                        </td>
                      </>
                    )}
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
          icon={inRecycle ? '🗑️' : '📦'}
          title={
            inRecycle
              ? keyword
                ? '没有匹配的回收站商品'
                : '回收站是空的'
              : keyword || auditStatus
                ? '没有匹配的商品'
                : '名下还没有商品'
          }
          desc={
            inRecycle
              ? keyword
                ? '换个关键词试试。'
                : '删除的商品会在这里保留 30 天，期间随时可以恢复；到期后由系统自动清理。'
              : keyword || auditStatus
                ? '换个关键词或切换审核状态试试。'
                : '点右上角「发布商品」提交第一件商品，平台审核通过后自动上架。'
          }
        />
      )}

      {/* 预览卡片（2026-10-27）：点列表行先看轻量预览 —— 打开即已读，橙点消失、
          「已通过」角标 -1；编辑/规格/上下架等动作仍在预览里进入，不改变原有入口 */}
      <Modal
        open={preview !== null}
        title="商品预览"
        onClose={closePreview}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closePreview}>
              关闭
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                const target = preview;
                if (!target) return;
                closePreview();
                void openEdit(target);
              }}
            >
              编辑这件商品
            </button>
          </>
        }
      >
        {preview ? (
          <div className="stack" style={{ gap: 14 }}>
            {/* 头部摘要：列表里已有的轻量字段，先渲染，不等详情返回 */}
            <div className="row" style={{ gap: 14, alignItems: 'flex-start' }}>
              {preview.mainImage ? (
                <img
                  src={preview.mainImage}
                  alt={preview.title}
                  style={{ width: 120, height: 120, borderRadius: 10, objectFit: 'cover', flex: '0 0 auto' }}
                />
              ) : (
                <span
                  aria-hidden
                  style={{
                    width: 120, height: 120, borderRadius: 10, background: 'var(--surface-2)',
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 32,
                  }}
                >
                  🐾
                </span>
              )}
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 16, wordBreak: 'break-all' }}>{preview.title}</div>
                {previewDetail?.subtitle ? (
                  <div className="small muted" style={{ marginTop: 4 }}>{previewDetail.subtitle}</div>
                ) : null}
                <div className="row row-wrap" style={{ gap: 6, marginTop: 8 }}>
                  <span className="tag">{preview.statusLabel}</span>
                  <span className="tag">{preview.auditLabel}</span>
                  <span className="tag">
                    {previewDetail?.supplyType === 'self' ? '平台自营' : '第三方商家'}
                  </span>
                </div>
                <div style={{ marginTop: 8, fontSize: 20, fontWeight: 700, color: 'var(--danger)' }}>
                  {formatPrice(preview.minPrice)}
                  {preview.maxPrice > preview.minPrice ? ` ~ ${formatPrice(preview.maxPrice)}` : ''}
                </div>
                <div className="small muted" style={{ marginTop: 4 }}>
                  SKU {preview.skuCount} 个 · 库存 {preview.totalStock} 件 · 销量 {preview.salesCount}
                  {preview.rating ? ` · 评分 ${preview.rating.toFixed(1)}（${preview.ratingCount} 条）` : ''}
                </div>
              </div>
            </div>

            {previewLoading ? (
              <LoadingBlock text="正在加载商品详情…" />
            ) : previewDetail ? (
              <>
                {/* 基本信息：类目/品牌/审核/版本，补足列表摘要里没有的字段 */}
                <div className="card card-pad" style={{ padding: 12 }}>
                  <div className="small" style={{ fontWeight: 600, marginBottom: 8 }}>基本信息</div>
                  <div
                    className="small muted"
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
                      gap: 6,
                    }}
                  >
                    <span>
                      类目：{previewDetail.categoryName
                        ?? (previewDetail.categoryId ? `#${previewDetail.categoryId}` : '—')}
                    </span>
                    <span>品牌：{previewDetail.brandName ?? '无品牌'}</span>
                    <span>商品 ID：{previewDetail.id}</span>
                    <span>版本：v{previewDetail.version}</span>
                    <span>审核时间：{previewDetail.auditAt ? fmtTime(previewDetail.auditAt) : '—'}</span>
                    <span>供货：{previewDetail.supplyType === 'self' ? '平台自营' : '第三方商家'}</span>
                  </div>
                </div>

                {/* SKU 明细：卖家最需要核对的规格 / 价格 / 库存 */}
                {previewDetail.skus.length > 0 ? (
                  <div>
                    <div className="small" style={{ fontWeight: 600, marginBottom: 6 }}>
                      规格与库存（{previewDetail.skus.length}）
                    </div>
                    <table className="table">
                      <thead>
                        <tr>
                          <th>规格</th>
                          <th style={{ textAlign: 'right' }}>价格</th>
                          <th style={{ textAlign: 'right' }}>库存</th>
                          <th>状态</th>
                        </tr>
                      </thead>
                      <tbody>
                        {previewDetail.skus.map((sku) => (
                          <tr key={sku.id}>
                            <td>{sku.specName ?? '默认规格'}</td>
                            <td style={{ textAlign: 'right' }}>{formatPrice(sku.price)}</td>
                            <td style={{ textAlign: 'right' }}>{sku.stock}</td>
                            <td>{sku.status === 'ON_SALE' || sku.status === 'ACTIVE' ? '在售' : '停用'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : null}

                {/* 全部图片（主图 + 详情图） */}
                {previewDetail.images.length > 0 ? (
                  <div>
                    <div className="small" style={{ fontWeight: 600, marginBottom: 6 }}>
                      商品图片（{previewDetail.images.length}）
                    </div>
                    <div className="row row-wrap" style={{ gap: 6 }}>
                      {previewDetail.images.map((img, idx) => (
                        <a key={`${img}-${idx}`} href={img} target="_blank" rel="noreferrer">
                          <img
                            src={img}
                            alt={`商品图 ${idx + 1}`}
                            style={{
                              width: 64, height: 64, borderRadius: 6, objectFit: 'cover',
                              border: '1px solid var(--border, #e5e7eb)',
                            }}
                            loading="lazy"
                            decoding="async"
                          />
                        </a>
                      ))}
                    </div>
                  </div>
                ) : null}

                {/* 介绍 / 规格参数 / 包装清单 */}
                {previewDetail.intro ? (
                  <div>
                    <div className="small" style={{ fontWeight: 600, marginBottom: 4 }}>商品介绍</div>
                    <div className="small muted">{previewDetail.intro}</div>
                  </div>
                ) : null}
                {previewDetail.specParams?.length || previewDetail.packaging?.length ? (
                  <div className="small muted" style={{ display: 'grid', gap: 4 }}>
                    {previewDetail.specParams?.length ? (
                      <span>
                        规格参数：{previewDetail.specParams.map((kv) => `${kv.name}=${kv.value}`).join('；')}
                      </span>
                    ) : null}
                    {previewDetail.packaging?.length ? (
                      <span>
                        包装清单：{previewDetail.packaging.map((kv) => `${kv.name}×${kv.value}`).join('；')}
                      </span>
                    ) : null}
                  </div>
                ) : null}

                {/* 富文本详情：默认折叠，避免预览被长文撑爆 */}
                {previewDetail.detailHtml ? (
                  <details>
                    <summary className="small" style={{ cursor: 'pointer' }}>
                      富文本详情（点击展开）
                    </summary>
                    <div style={{ maxHeight: 260, overflow: 'auto', marginTop: 6 }}>
                      <RichTextView html={previewDetail.detailHtml} />
                    </div>
                  </details>
                ) : previewDetail.detail ? (
                  <details>
                    <summary className="small" style={{ cursor: 'pointer' }}>
                      详情正文（点击展开）
                    </summary>
                    <div className="small muted" style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>
                      {previewDetail.detail}
                    </div>
                  </details>
                ) : null}
              </>
            ) : (
              <div className="small muted">
                详情加载失败，以上为列表摘要；可点「编辑这件商品」查看完整内容。
              </div>
            )}

            {preview.auditStatus === 'REJECTED' && preview.auditReason ? (
              <div className="small" style={{ color: '#c0392b' }}>
                驳回原因：{preview.auditReason}
              </div>
            ) : null}

            <div className="small muted">
              打开预览即视为已读：橙点消失，若这件商品是「已通过」，角标同步 -1。
              待审核 / 已驳回是还没落定的事，读过也继续保留角标提醒。
            </div>
          </div>
        ) : null}
      </Modal>

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
            {editTemplateAttrs.length > 0 ? (
              <Field label="类目属性" hint="由类目模板决定：标 * 的必填，数字与单选项会按规则校验">
                <div className="card card-pad" style={{ padding: 10 }}>
                  {editTemplateAttrs.map((attr) => (
                    <div key={attr.id} className="row" style={{ gap: 10, alignItems: 'center', marginBottom: 8 }}>
                      <span className="small" style={{ width: 120 }}>
                        {attr.name}{attr.required ? ' *' : ''}
                      </span>
                      <div style={{ flex: 1 }} data-attr-name={attr.name}>
                        {attr.inputType === 'SELECT' ? (
                          <select
                            className="input"
                            value={editSpecValueOf(attr.name)}
                            onChange={(e) => setEditSpecValue(attr.name, e.target.value)}
                          >
                            <option value="">请选择</option>
                            {(attr.options ?? []).map((option) => (
                              <option key={option} value={option}>{option}</option>
                            ))}
                          </select>
                        ) : (
                          <input
                            className="input"
                            value={editSpecValueOf(attr.name)}
                            maxLength={200}
                            inputMode={attr.inputType === 'NUMBER' ? 'decimal' : undefined}
                            placeholder={attr.inputType === 'NUMBER' ? '请输入数字' : '选填'}
                            onChange={(e) => setEditSpecValue(attr.name, e.target.value)}
                          />
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </Field>
            ) : null}
            <Field label="其他规格参数（自定义）" hint="如 品牌 / 净含量 / 适用阶段；最多 20 条">
              <KeyValueEditor
                value={editCustomSpecs}
                onChange={setEditCustomSpecs}
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

      <Modal
        open={specEditing !== null}
        title={`管理规格${specEditing ? ` · ${specEditing.title}` : ''}`}
        onClose={closeSpecs}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeSpecs}>
              取消
            </button>
            <button
              className="btn btn-primary"
              disabled={specBusy || specLoading || specRows.length === 0}
              onClick={submitSpecs}
            >
              {specBusy ? '保存中…' : '保存规格'}
            </button>
          </>
        }
      >
        {specLoading ? (
          <LoadingBlock text="正在读取规格…" />
        ) : (
          <div>
            <Alert kind="info">
              这里的保存会整体替换该商品的规格：新增、删除、改规格名都会让
              {specMerchant ? '商品重新进入审核，审核期间前台不可见' : '规格立即生效（自营商品免审）'}。
              只改价格 / 库存不会触发重新审核。
            </Alert>
            {specUsageUnknown ? (
              <Alert kind="warn">
                暂时无法确认哪些规格已被订单引用。已有订单的规格不能删除，
                若保存时被拒绝，请按提示改名保留。
              </Alert>
            ) : null}
            <table className="table" style={{ marginTop: 12 }}>
              <thead>
                <tr>
                  <th>规格名</th>
                  <th style={{ width: 130 }}>价格（元）</th>
                  <th style={{ width: 110 }}>库存</th>
                  <th style={{ width: 170 }}>状态</th>
                  <th style={{ width: 80 }}></th>
                </tr>
              </thead>
              <tbody>
                {specRows.map((row) => {
                  const used = row.id ? (specUsage[row.id] ?? 0) : 0;
                  return (
                    <tr key={row.key}>
                      <td>
                        <input
                          className="input"
                          value={row.specName}
                          maxLength={30}
                          placeholder="如 2kg（留空为「默认」）"
                          onChange={(e) => patchSpecRow(row.key, { specName: e.target.value })}
                        />
                      </td>
                      <td>
                        <input
                          className="input"
                          inputMode="decimal"
                          value={row.price}
                          onChange={(e) => patchSpecRow(row.key, { price: e.target.value })}
                        />
                      </td>
                      <td>
                        <input
                          className="input"
                          inputMode="numeric"
                          value={row.stock}
                          onChange={(e) => patchSpecRow(row.key, { stock: e.target.value })}
                        />
                      </td>
                      <td>
                        {used ? (
                          <span className="small" style={{ color: '#c0392b' }}>
                            已有 {used} 笔订单，不可删除
                          </span>
                        ) : row.id ? (
                          <span className="tag">可删除</span>
                        ) : (
                          <span className="tag">新增</span>
                        )}
                      </td>
                      <td>
                        <button
                          className="btn btn-ghost btn-sm"
                          style={{ color: used ? undefined : '#c0392b' }}
                          disabled={specBusy || specRows.length <= 1 || used > 0}
                          onClick={() => removeSpecRow(row)}
                        >
                          删除
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="row" style={{ marginTop: 10, justifyContent: 'space-between' }}>
              <button
                className="btn btn-ghost btn-sm"
                disabled={specBusy || specRows.length >= 20}
                onClick={addSpecRow}
              >
                + 新增规格
              </button>
              <span className="small muted">最多 20 个规格 · 已有订单的规格不能删除</span>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={batchResult !== null}
        title="批量操作结果"
        onClose={() => setBatchResult(null)}
        footer={
          <button className="btn btn-primary" onClick={() => setBatchResult(null)}>
            知道了
          </button>
        }
      >
        {batchResult ? (
          <div>
            <Alert
              kind={
                batchResult.failedCount === 0
                  ? 'success'
                  : batchResult.successCount === 0
                    ? 'error'
                    : 'warn'
              }
            >
              共 {batchResult.total} 件：成功 {batchResult.successCount} 件，失败{' '}
              {batchResult.failedCount} 件。
              {batchResult.failedCount > 0
                ? '失败的商品保持原状态，本次操作不会回滚已成功的部分；可修正原因后重新提交。'
                : ''}
            </Alert>
            <table className="table" style={{ marginTop: 12 }}>
              <thead>
                <tr>
                  <th>商品</th>
                  <th style={{ width: 200 }}>结果</th>
                </tr>
              </thead>
              <tbody>
                {batchResult.items.map((entry) => (
                  <tr key={entry.id}>
                    <td style={{ wordBreak: 'break-all' }}>{batchTitles[entry.id] ?? entry.id}</td>
                    <td>
                      {entry.success ? (
                        <span className="tag">{entry.statusLabel ?? '成功'}</span>
                      ) : (
                        <span className="small" style={{ color: '#c0392b' }}>
                          {entry.message ?? '操作失败'}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Modal>

      {/* A-17 · 批量导入：一个分组键 = 一个商品，多行规格填同一个分组键；CSV 与 xlsx 同口径 */}
      <Modal
        open={importOpen}
        title="批量导入商品（CSV / xlsx）"
        onClose={() => setImportOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setImportOpen(false)}>
              关闭
            </button>
            <button
              className="btn btn-primary"
              disabled={importBusy || !importContent}
              onClick={submitImport}
            >
              {importBusy ? '导入中…' : '开始导入'}
            </button>
          </>
        }
      >
        <Alert kind="info">
          一个「分组键」= 一个商品；同一商品的多个规格填相同分组键（每个规格一行）。
          同一分组键下的标题 / 类目 / 图片等商品字段必须一致。导入的商品会按新建规则进入审核，
          第三方商品审核通过前前台不可见。
        </Alert>
        <div className="row" style={{ gap: 10, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
          <input
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            aria-label="选择商品 CSV 或 xlsx 文件"
            onChange={(e) => {
              void pickImportFile(e.target.files?.[0] ?? null);
              e.target.value = '';
            }}
          />
          <button className="btn btn-ghost btn-sm" onClick={downloadTemplate}>
            下载 CSV 模板
          </button>
          <a className="btn btn-ghost btn-sm" href={sellerApi.importProductTemplateXlsxUrl()}>
            xlsx 模板
          </a>
          <span className="small muted">
            {importFileName ? `已选择：${importFileName}` : '支持 UTF-8 CSV / xlsx，最大 2MB'}
          </span>
        </div>

        {importResult ? (
          <div style={{ marginTop: 14 }}>
            <Alert kind={importResult.failedCount === 0 ? 'success' : importResult.successCount === 0 ? 'error' : 'warn'}>
              共 {importResult.totalGroups} 个商品：成功 {importResult.successCount} 个，失败{' '}
              {importResult.failedCount} 个。失败的商品未创建，已成功的商品保留。
            </Alert>
            <table className="table" style={{ marginTop: 10 }}>
              <thead>
                <tr>
                  <th style={{ width: 70 }}>行号</th>
                  <th>商品</th>
                  <th style={{ width: 220 }}>结果</th>
                </tr>
              </thead>
              <tbody>
                {importResult.items.map((entry) => (
                  <tr key={`${entry.lineNo}-${entry.groupKey}`}>
                    <td>{entry.lineNo}</td>
                    <td style={{ wordBreak: 'break-all' }}>{entry.title || entry.groupKey || '—'}</td>
                    <td>
                      {entry.success ? (
                        <span className="tag">已创建</span>
                      ) : (
                        <span className="small" style={{ color: '#c0392b' }}>{entry.message ?? '导入失败'}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Modal>

      {/* 第三批 · 价格/库存导入：一行 = 一个规格的一次改动，逐行返回结果 */}
      <Modal
        open={psOpen}
        title="批量改价 / 改库存（CSV / xlsx）"
        onClose={() => setPsOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPsOpen(false)}>
              关闭
            </button>
            <button
              className="btn btn-primary"
              disabled={psBusy || !psContent}
              onClick={submitPriceStock}
            >
              {psBusy ? '导入中…' : '开始导入'}
            </button>
          </>
        }
      >
        <Alert kind="info">
          列顺序：商品ID、规格ID、价格(元)、库存。一行对应一个规格；只填其中一列时，另一列保持不变。
          价格填元、最多两位小数。改价改库存<b>不会触发重新审核</b>，也不会改变商品审核状态。
        </Alert>
        <div className="row" style={{ gap: 10, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
          <input
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            aria-label="选择价格库存 CSV 或 xlsx 文件"
            onChange={(e) => {
              void pickPriceStockFile(e.target.files?.[0] ?? null);
              e.target.value = '';
            }}
          />
          <button className="btn btn-ghost btn-sm" onClick={downloadPriceStockTemplate}>
            下载 CSV 模板
          </button>
          <a className="btn btn-ghost btn-sm" href={sellerApi.priceStockTemplateXlsxUrl()}>
            xlsx 模板
          </a>
          <span className="small muted">
            {psFileName ? `已选择：${psFileName}` : '支持 UTF-8 CSV / xlsx，最大 2MB'}
          </span>
        </div>

        {psResult ? (
          <div style={{ marginTop: 14 }}>
            <Alert kind={psResult.failedCount === 0 ? 'success' : psResult.successCount === 0 ? 'error' : 'warn'}>
              共 {psResult.total} 行：成功 {psResult.successCount} 行，失败 {psResult.failedCount} 行。
              成功行已立即生效，失败行未修改任何数据。
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
                {psResult.items.map((entry) => (
                  <tr key={`ps-${entry.id}`}>
                    <td>{entry.id}</td>
                    <td>
                      {entry.success ? (
                        <span className="tag">已更新</span>
                      ) : (
                        <span className="tag" style={{ color: '#c0392b' }}>{entry.errorCode ?? '失败'}</span>
                      )}
                    </td>
                    <td style={{ wordBreak: 'break-all' }}>
                      {entry.success ? '—' : entry.message ?? '导入失败'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Modal>

      <ProductPublishModal
        open={publishing}
        onClose={() => setPublishing(false)}
        onSuccess={() => {
          setQuery({ auditStatus: 'PENDING', page: undefined });
          reloadAll();
        }}
      />
    </div>
  );
};
