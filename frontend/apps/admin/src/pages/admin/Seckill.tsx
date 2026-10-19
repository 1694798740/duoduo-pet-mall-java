/**
 * 平台管理后台 · 秒杀活动管理（A14 遗留补齐）。
 *
 * 后端端点齐备（AdminSeckillController：列表 / 新建 / 上下架 / **编辑 / 删除**），
 * 此前后台**没有页面** —— 运营只能靠 curl 或直连数据库建活动，等于「功能存在但不可用」。本页把它们接到界面上。
 *
 * 两条容易踩的口径，页面里必须显式表达出来：
 *  1. 「进行中 / 未开始 / 已结束」（phase）与「上架 / 下架」（enabled）是<b>两个正交维度</b>：
 *     phase 由当前时间与 start_at/end_at 比较得出，enabled 是运营开关。
 *     一个「上架 + 已结束」的活动是完全正常的（活动过期不必手动置状态）。
 *  2. 服务端存的是 **UTC 挂钟**（容器时区全 UTC），所以时间输入框里的值**按 UTC 解读**，
 *     不是北京时间。页面上明确标注，避免「填了 20:00 却 12:00 就开抢」。
 *
 * 另：列表**会显示已下架的活动**（后端 /api/admin/seckill/activities 全量下发）——
 * 否则「下架」就成了不可逆操作，活动消失后没有任何入口能把它放回来。
 *
 * R74 补的两件事（此前只有「新建 / 上下架」，历史自测活动永远清不掉）：
 *  · **编辑**：只开标题与时间；商品 / 价格 / 配额不可改（余量已扣、订单已存价格快照）；
 *  · **删除**：只删「没有未关闭订单」的活动（订单全部已关闭的活动也可删，避免自测活动只增不减）；
 *    还有待付款 / 已付款 / 已发货 / 已完成订单的活动服务端 422，页面把原因原样展示并引导去「下架」。
 */

import React, { useCallback, useState } from 'react';
import { adminApi, productApi } from '@duoduo/api-sdk/endpoints';
import type {
  ProductCard,
  SeckillActivityCreateRequest,
  SeckillActivityEditRequest,
  SeckillActivityView,
  SkuView,
} from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 阶段 → 中文 + 配色类（与全局 badge 样式对齐） */
const PHASE_LABELS: Record<string, { text: string; cls: string }> = {
  ONGOING: { text: '进行中', cls: 'tag tag-success' },
  UPCOMING: { text: '未开始', cls: 'tag tag-second' },
  ENDED: { text: '已结束', cls: 'tag' },
};

type SkuRow = SeckillActivityCreateRequest['skus'][number];

/** 行状态 = 请求体字段 + 「选商品回填」带回来的现场信息（用于配额校验与提示） */
type RowState = SkuRow & { /** 该 SKU 当前可售库存（选商品回填时带出；手填 SKU 时为 null） */ skuStock: number | null };

const emptyRow = (): RowState => ({
  skuId: '',
  seckillPrice: 0,
  originalPrice: null,
  totalStock: 1,
  perLimit: 1,
  skuStock: null,
});

// ---------------- 时间口径：页面一律用「北京时间」，提交前换算成服务端 UTC ----------------
// 服务端存 UTC 挂钟（容器时区全 UTC）。此前页面让运营直接填 UTC（填 20:00 实际 12:00 开抢），
// 且默认值是「UTC 现在 +5 分钟」—— 看起来就是一堆随机时间。现在输入框与列表都显示北京时间，
// 默认值为北京时间当天 00:00–23:59，提交时自动 -8h 换算成 UTC。

const BJ_OFFSET_MS = 8 * 60 * 60 * 1000;

/** 服务端 UTC 挂钟串 → 北京时间 datetime-local 值（'YYYY-MM-DDTHH:mm'） */
const utcToBj = (iso: string | null | undefined): string => {
  if (!iso) return '';
  const t = Date.parse(`${iso.slice(0, 19)}Z`);
  if (Number.isNaN(t)) return '';
  return new Date(t + BJ_OFFSET_MS).toISOString().slice(0, 16);
};

/**
 * 北京时间 datetime-local 值 → **带 +08:00 偏移的 ISO 串**（如 `2026-10-19T20:00:00+08:00`）。
 *
 * <p>★ 2026-10-19 修：这里以前返回的是**去掉时区的 UTC 挂钟串**（`2026-10-19T12:00:00`），
 * 但服务端自 `f0e835c` 起把「无时区输入」按**北京时间**解析再转 UTC ——
 * 于是同一次提交被减了两次 8 小时：运营填 20:00，库里存的是 12:00（北京），
 * 活动提前 8 小时结束。带上 `+08:00` 后走服务端的「有时区按其时区」分支，只换算一次。</p>
 *
 * <p>代价为零：`parseActivityTime` 本来就同时接受两种写法，这里只是把**语义写进报文**，
 * 不再依赖「谁去猜这个裸串是哪个时区」。</p>
 */
const bjToServerIso = (v: string): string => {
  if (!v) return '';
  // datetime-local 给的是 'YYYY-MM-DDTHH:mm'（可能带秒），补秒后直接挂 +08:00
  const hhmmss = v.length === 16 ? `${v}:00` : v.slice(0, 19);
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(hhmmss) ? `${hhmmss}+08:00` : '';
};

/** 北京时间的今天，拼上指定时分（默认值用：当天 00:00 / 23:59） */
const bjToday = (hhmm: string): string =>
  `${new Date(Date.now() + BJ_OFFSET_MS).toISOString().slice(0, 10)}T${hhmm}`;

export const AdminSeckillPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<{ title: string; startAt: string; endAt: string }>({
    title: '',
    startAt: bjToday('00:00'),
    endAt: bjToday('23:59'),
  });
  const [rows, setRows] = useState<RowState[]>([emptyRow()]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // ---- 编辑（R74）：只改标题与时间；商品 / 价格 / 配额刻意不开（会让配置与已成交事实对不上） ----
  const [editing, setEditing] = useState<SeckillActivityView | null>(null);
  const [editForm, setEditForm] = useState<{ title: string; startAt: string; endAt: string }>({
    title: '',
    startAt: '',
    endAt: '',
  });
  const [editBusy, setEditBusy] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // ---- 删除（R74）：默认不动手（弹窗默认焦点在「取消」），且服务端还会拦「已有订单」的活动 ----
  const [deleting, setDeleting] = useState<SeckillActivityView | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // ---- 选商品助手：关键词 → SPU → SKU（运营不可能手背 19 位 skuId） ----
  const [pickKeyword, setPickKeyword] = useState('');
  const [pickResults, setPickResults] = useState<ProductCard[]>([]);
  const [pickSpus, setPickSpus] = useState<ProductCard[]>([]);
  const [pickSkus, setPickSkus] = useState<Array<SkuView & { spuTitle: string }>>([]);
  const [pickBusy, setPickBusy] = useState(false);
  const [pickError, setPickError] = useState<string | null>(null);
  const [pickRowIndex, setPickRowIndex] = useState(0);
  const [picking, setPicking] = useState(false);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.seckillActivities(), []),
    operator,
    [operator],
  );

  if (!operator) return <AdminDenied />;
  if (loading) return <LoadingBlock text="正在加载秒杀活动…" />;

  const items = data ?? [];

  const openCreate = () => {
    setCreating(true);
    setForm({ title: '', startAt: bjToday('00:00'), endAt: bjToday('23:59') });
    setRows([emptyRow()]);
    setActionError(null);
    setPickSkus([]);
    setPickSpus([]);
    setPickResults([]);
  };
  const closeModal = () => {
    setCreating(false);
    setActionError(null);
    setPicking(false);
  };

  const updateRow = (index: number, patch: Partial<RowState>) =>
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  const addRow = () => setRows((prev) => [...prev, emptyRow()]);
  const removeRow = (index: number) =>
    setRows((prev) => (prev.length === 1 ? prev : prev.filter((_, i) => i !== index)));

  const searchProducts = async () => {
    setPickError(null);
    setPickBusy(true);
    try {
      const page = await productApi.search({ keyword: pickKeyword.trim(), page: 1, pageSize: 8 });
      setPickResults(page.items ?? []);
      if ((page.items ?? []).length === 0) setPickError('没有搜到商品，换个关键词试试');
    } catch (err) {
      setPickError(toFriendlyMessage(err));
    } finally {
      setPickBusy(false);
    }
  };

  const loadSkus = async (card: ProductCard) => {
    setPickError(null);
    setPickBusy(true);
    try {
      const detail = await productApi.detail(card.id);
      setPickSpus([card]);
      setPickSkus((detail.skus ?? []).map((s) => ({ ...s, spuTitle: card.title })));
      if ((detail.skus ?? []).length === 0) setPickError('该商品没有可售规格');
    } catch (err) {
      setPickError(toFriendlyMessage(err));
    } finally {
      setPickBusy(false);
    }
  };

  /**
   * 选中 SKU：回填 skuId / 当前可售库存，并把商品现价当作原价默认值。
   * ★ 单位换算（2026-10-19 修复）：`sku.price` 是**分**（服务端金额字段一律数字分），
   *   而这两个输入框按**元**录入、提交时才 ×100 —— 之前直接把 15800（分）填进「元」框，
   *   运营一不留神就建出「原价 15800 元」的活动。
   */
  const applySku = (sku: SkuView) => {
    /**
     * 秒杀价默认值＝现价八折，并夹在「(0.01 元, 原价)」区间内（2026-10-19 新增规则）：
     * 秒杀价必须**严格低于**原价、且**大于 0.01 元**。默认值直接落在合法区间，
     * 运营不改也能存；原价极低（≤0.02 元）时无解，给 0.01 让保存时的校验把话说明白。
     */
    const originalCents = sku.price;
    const eighty = Math.round(originalCents * 0.8);
    const seckillCents = originalCents > 2 ? Math.min(Math.max(eighty, 2), originalCents - 1) : 1;
    updateRow(pickRowIndex, {
      skuId: sku.id,
      skuStock: sku.stock,
      originalPrice: sku.price / 100,
      seckillPrice: seckillCents / 100,
      // 配额默认值收敛到库存范围内（上限先按 99 封顶，保存前还有「不得超过库存」的强校验）
      totalStock: Math.max(1, Math.min(sku.stock, 99)),
    });
    setPicking(false);
    push(`已回填 SKU（当前可售库存 ${sku.stock}）`, 'success');
  };

  const save = async () => {
    setActionError(null);
    if (!form.title.trim()) {
      setActionError('请填写活动标题');
      return;
    }
    if (!form.startAt || !form.endAt) {
      setActionError('请填写开始与结束时间');
      return;
    }
    if (form.endAt <= form.startAt) {
      setActionError('结束时间必须晚于开始时间');
      return;
    }
    const filled = rows.filter((r) => r.skuId.trim() !== '');
    if (filled.length === 0) {
      setActionError('至少配置一个秒杀商品');
      return;
    }
    // 逐行校验（2026-10-19）：配额受库存约束、限购受配额约束 —— 之前两个数字随便填，
    // 「配额 5000、库存 193」这种活动能直接建出来，卖出去就是超卖。
    // 同日新增价格规则：秒杀价必须**严格低于原售价**、且**大于 0.01 元**（详见下方注释）。
    for (const [i, r] of rows.entries()) {
      if (r.skuId.trim() === '') continue;
      const who = `第 ${i + 1} 个商品（SKU ${r.skuId.trim()}）`;
      const priceCents = Math.round(r.seckillPrice * 100);
      /**
       * 价格规则（2026-10-19）：
       *   ① 秒杀价 > 0.01 元 —— 0.01 元本身就是「白送」，且 0 元会让分摊/退款出现 0 金额行；
       *   ② 秒杀价 < 原售价（**严格**低于）—— 等于原售价的「秒杀」没有任何优惠，
       *      还会让划线价与售价相同，页面上看起来像配置错误。
       * 比较一律换成分（整数）做，避免 0.1+0.2 这类浮点误差造成「看着相等、判不相等」。
       */
      if (priceCents <= 1) {
        setActionError(`${who}：秒杀价必须大于 0.01 元（当前 ${(priceCents / 100).toFixed(2)} 元）`);
        return;
      }
      if (r.originalPrice != null && r.originalPrice > 0) {
        const originalCents = Math.round(r.originalPrice * 100);
        if (priceCents >= originalCents) {
          setActionError(
            `${who}：秒杀价（${(priceCents / 100).toFixed(2)} 元）必须低于原售价（${(originalCents / 100).toFixed(2)} 元）`,
          );
          return;
        }
      }
      if (r.skuStock != null && r.totalStock > r.skuStock) {
        setActionError(
          `第 ${i + 1} 个商品：活动配额（${r.totalStock}）不能超过该规格当前可售库存（${r.skuStock}）`,
        );
        return;
      }
      const per = r.perLimit ?? 1;
      if (per < 1) {
        setActionError(`第 ${i + 1} 个商品：每人限购至少 1 件`);
        return;
      }
      if (per > r.totalStock) {
        setActionError(`第 ${i + 1} 个商品：每人限购（${per}）不能超过活动配额（${r.totalStock}）`);
        return;
      }
    }
    const skus = filled.map((r) => ({
      skuId: r.skuId.trim(),
      // 金额单位：页面按元录入，服务端按分
      seckillPrice: Math.round(r.seckillPrice * 100),
      originalPrice: r.originalPrice == null || r.originalPrice === 0
        ? null
        : Math.round(r.originalPrice * 100),
      totalStock: r.totalStock,
      perLimit: r.perLimit ?? 1,
    }));
    const body: SeckillActivityCreateRequest = {
      title: form.title.trim(),
      // 输入框是北京时间，提交前换算成服务端 UTC 存储
      startAt: bjToServerIso(form.startAt),
      endAt: bjToServerIso(form.endAt),
      skus,
    };
    setBusy(true);
    try {
      await adminApi.createSeckillActivity(body);
      push('秒杀活动已创建', 'success');
      closeModal();
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (item: SeckillActivityView, enabled: boolean) => {
    setActionError(null);
    try {
      await adminApi.setSeckillActivityStatus(item.id, enabled);
      push(enabled ? '已上架，C 端可见' : '已下架，C 端不再展示', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    }
  };

  const openEdit = (item: SeckillActivityView) => {
    setEditing(item);
    setEditError(null);
    setEditForm({
      title: item.title,
      startAt: utcToBj(item.startAt),
      endAt: utcToBj(item.endAt),
    });
  };

  const saveEdit = async () => {
    if (!editing) return;
    setEditError(null);
    if (!editForm.title.trim()) {
      setEditError('请填写活动标题');
      return;
    }
    if (!editForm.startAt || !editForm.endAt) {
      setEditError('请填写开始与结束时间');
      return;
    }
    if (editForm.endAt <= editForm.startAt) {
      setEditError('结束时间必须晚于开始时间');
      return;
    }
    const body: SeckillActivityEditRequest = {
      title: editForm.title.trim(),
      startAt: bjToServerIso(editForm.startAt),
      endAt: bjToServerIso(editForm.endAt),
    };
    setEditBusy(true);
    try {
      await adminApi.updateSeckillActivity(editing.id, body);
      push('活动已更新', 'success');
      setEditing(null);
      reload();
    } catch (err) {
      setEditError(toFriendlyMessage(err));
    } finally {
      setEditBusy(false);
    }
  };

  const doDelete = async () => {
    if (!deleting) return;
    setDeleteError(null);
    setDeleteBusy(true);
    try {
      await adminApi.deleteSeckillActivity(deleting.id);
      push('活动已删除', 'success');
      setDeleting(null);
      reload();
    } catch (err) {
      // 服务端会拒「已有秒杀订单」的活动（422），原因原样展示，不吞
      setDeleteError(toFriendlyMessage(err));
    } finally {
      setDeleteBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 秒杀活动</h2>
      <AdminTabs />

      <div className="row-between row-wrap" style={{ marginBottom: 14 }}>
        <span className="small muted">
          共 {items.length} 场活动（含已下架）　·　时间均为 <b>北京时间</b>（服务端按 UTC 存储，页面已自动换算）
        </span>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建活动</button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {/* 创建弹窗的校验错误渲染在弹窗内部（见下），这里不再重复一份 */}

      {items.length === 0 ? (
        <Empty title="还没有秒杀活动" desc="点右上角「新建活动」创建第一场" />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>活动</th>
                <th>时间（北京时间）</th>
                <th>阶段</th>
                <th>开关</th>
                <th>秒杀商品与余量</th>
                <th style={{ width: 200 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => {
                const phase = PHASE_LABELS[item.phase] ?? { text: item.phase, cls: 'badge badge-muted' };
                return (
                  <tr key={item.id}>
                    <td>
                      <div>{item.title}</div>
                      <div className="small muted mono">{item.id}</div>
                    </td>
                    <td className="small">
                      <div>{utcToBj(item.startAt)}</div>
                      <div className="muted">至 {utcToBj(item.endAt)}</div>
                    </td>
                    <td><span className={phase.cls}>{phase.text}</span></td>
                    <td>
                      {item.enabled
                        ? <span className="tag tag-success">已上架</span>
                        : <span className="tag">已下架</span>}
                    </td>
                    <td>
                      {(item.skus ?? []).length === 0 ? (
                        <span className="small muted">无</span>
                      ) : (
                        <ul style={{ margin: 0, paddingLeft: 16 }}>
                          {item.skus.map((s) => (
                            <li key={s.skuId} className="small">
                              <span>{s.spuTitle}</span>
                              {s.specText ? <span className="muted">　{s.specText}</span> : null}
                              <span className="muted">　{formatPrice(s.seckillPrice)}</span>
                              <span className="muted">
                                　余 {s.availableStock}/{s.totalStock}　限购 {s.perLimit}
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      <div className="row row-wrap" style={{ gap: 6 }}>
                        {item.enabled ? (
                          <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, false)}>下架</button>
                        ) : (
                          <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, true)}>上架</button>
                        )}
                        <button className="btn btn-ghost btn-sm" onClick={() => openEdit(item)}>编辑</button>
                        <button className="btn btn-ghost btn-sm" onClick={() => { setDeleteError(null); setDeleting(item); }}>
                          删除
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={creating}
        title="新建秒杀活动"
        onClose={closeModal}
        footer={
          <>
            <button className="btn btn-ghost" onClick={closeModal}>取消</button>
            <button className="btn btn-primary" disabled={busy || !form.title.trim()} onClick={save}>
              {busy ? '创建中…' : '创建'}
            </button>
          </>
        }
      >
        <Field label="活动标题" required>
          <input
            className="input"
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            maxLength={100}
            placeholder="如：周末猫粮狂欢"
          />
        </Field>
        <div className="row row-wrap" style={{ gap: 12 }}>
          <div style={{ flex: 1, minWidth: 200 }}>
            <Field label="开始时间（北京时间）" required>
              <input
                className="input"
                type="datetime-local"
                value={form.startAt}
                onChange={(e) => setForm({ ...form, startAt: e.target.value })}
              />
            </Field>
          </div>
          <div style={{ flex: 1, minWidth: 200 }}>
            <Field label="结束时间（北京时间）" required>
              <input
                className="input"
                type="datetime-local"
                value={form.endAt}
                onChange={(e) => setForm({ ...form, endAt: e.target.value })}
              />
            </Field>
          </div>
        </div>
        <p className="small muted">
          按北京时间填写，默认<b>当天 00:00–23:59</b>，可自行调整；提交时自动换算成服务端 UTC 存储。
          想让 20:00 开抢就直接填 20:00。
        </p>

        <div className="row-between row-wrap" style={{ marginTop: 8, marginBottom: 6 }}>
          <b>秒杀商品</b>
          <button className="btn btn-ghost btn-sm" onClick={addRow}>+ 加一个商品</button>
        </div>
        {rows.map((row, index) => (
          <div key={index} className="card card-pad" style={{ marginBottom: 8 }}>
            <div className="row row-wrap" style={{ gap: 8 }}>
              <div style={{ flex: 2, minWidth: 220 }}>
                <Field label="SKU ID" required>
                  <input
                    className="input mono"
                    value={row.skuId}
                    onChange={(e) => updateRow(index, { skuId: e.target.value })}
                    placeholder="19 位 ID，可用右侧「选商品」回填"
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 130 }}>
                <Field
                  label="秒杀价（元）"
                  required
                  hint={
                    row.originalPrice != null && row.originalPrice > 0
                      ? `必须低于原价 ¥${row.originalPrice.toFixed(2)}，且大于 ¥0.01`
                      : '必须低于原售价，且大于 ¥0.01'
                  }
                >
                  <input
                    className="input"
                    type="number"
                    min={0.02}
                    step={0.01}
                    value={row.seckillPrice || ''}
                    onChange={(e) => updateRow(index, { seckillPrice: Number(e.target.value) })}
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 110 }}>
                <Field label="原价（元，可空）">
                  <input
                    className="input"
                    type="number"
                    min={0}
                    step={0.01}
                    value={row.originalPrice ?? ''}
                    onChange={(e) =>
                      updateRow(index, { originalPrice: e.target.value === '' ? null : Number(e.target.value) })}
                    placeholder="留空取现价"
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 90 }}>
                <Field
                  label="活动配额"
                  required
                  hint={row.skuStock != null ? `该规格当前可售库存 ${row.skuStock}，不能超过它` : '先用「选商品回填」带出库存'}
                >
                  <input
                    className="input"
                    type="number"
                    min={1}
                    max={row.skuStock ?? undefined}
                    step={1}
                    value={row.totalStock}
                    onChange={(e) => updateRow(index, { totalStock: Number(e.target.value) })}
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 90 }}>
                <Field
                  label="每人限购"
                  hint={`1 ~ ${Math.max(1, Math.min(10, row.totalStock || 10))} 件（不超过活动配额，全场最多 10）`}
                >
                  <input
                    className="input"
                    type="number"
                    min={1}
                    max={Math.max(1, Math.min(10, row.totalStock || 10))}
                    step={1}
                    value={row.perLimit ?? 1}
                    onChange={(e) => updateRow(index, { perLimit: Number(e.target.value) })}
                  />
                </Field>
              </div>
            </div>
            <div className="row row-wrap" style={{ gap: 6 }}>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => { setPickRowIndex(index); setPicking(true); }}
              >
                选商品回填
              </button>
              <button
                className="btn btn-ghost btn-sm"
                disabled={rows.length === 1}
                onClick={() => removeRow(index)}
              >
                移除
              </button>
            </div>
          </div>
        ))}

        {picking ? (
          <div className="card card-pad" style={{ marginTop: 6 }}>
            <div className="row" style={{ gap: 6, marginBottom: 8 }}>
              <input
                className="input"
                value={pickKeyword}
                onChange={(e) => setPickKeyword(e.target.value)}
                placeholder="搜商品名"
              />
              <button className="btn btn-ghost btn-sm" disabled={pickBusy} onClick={searchProducts}>
                {pickBusy ? '搜索中…' : '搜索'}
              </button>
              <button className="btn btn-ghost btn-sm" onClick={() => setPicking(false)}>收起</button>
            </div>
            {pickError ? <Alert kind="error">{pickError}</Alert> : null}
            {pickSpus.length === 0 ? (
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {pickResults.map((card) => (
                  <li key={card.id} className="row-between row-wrap" style={{ gap: 8 }}>
                    <span className="small">{card.title}</span>
                    <button className="btn btn-ghost btn-sm" onClick={() => loadSkus(card)}>看规格</button>
                  </li>
                ))}
              </ul>
            ) : (
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {pickSkus.map((sku) => (
                  <li key={sku.id} className="row-between row-wrap" style={{ gap: 8 }}>
                    <span className="small">
                      {sku.spuTitle}　<span className="muted">{Object.values(sku.spec ?? {}).join(' / ')}</span>
                      　<span className="muted">{formatPrice(sku.price)}</span>
                      　<span className="muted">库存 {sku.stock}</span>
                      　<span className="mono muted">{sku.id}</span>
                    </span>
                    <button className="btn btn-ghost btn-sm" onClick={() => applySku(sku)}>用这个</button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : null}

        {/*
         * ★ 校验错误必须显示在**弹窗里**（2026-10-19 修）：
         * 原来 actionError 渲染在页面顶部，而它被模态遮罩挡着 —— 运营点「创建」只看到
         * 「什么都没发生」，会以为按钮坏了。错误提示放在创建按钮上方，点到就看到。
         */}
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      {/* 编辑：只改标题与时间 —— 商品/价格/配额不在这里，理由是「已扣的余量」与「已下单的价格快照」不能改 */}
      <Modal
        open={editing !== null}
        title="编辑秒杀活动"
        onClose={() => { setEditing(null); setEditError(null); }}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => { setEditing(null); setEditError(null); }}>取消</button>
            <button className="btn btn-primary" disabled={editBusy || !editForm.title.trim()} onClick={saveEdit}>
              {editBusy ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        {editing ? (
          <>
            <p className="small muted mono">活动 ID：{editing.id}</p>
            <Field label="活动标题" required>
              <input
                className="input"
                value={editForm.title}
                onChange={(e) => setEditForm({ ...editForm, title: e.target.value })}
                maxLength={100}
              />
            </Field>
            <div className="row row-wrap" style={{ gap: 12 }}>
              <div style={{ flex: 1, minWidth: 200 }}>
                <Field label="开始时间（北京时间）" required>
                  <input
                    className="input"
                    type="datetime-local"
                    value={editForm.startAt}
                    onChange={(e) => setEditForm({ ...editForm, startAt: e.target.value })}
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 200 }}>
                <Field label="结束时间（北京时间）" required>
                  <input
                    className="input"
                    type="datetime-local"
                    value={editForm.endAt}
                    onChange={(e) => setEditForm({ ...editForm, endAt: e.target.value })}
                  />
                </Field>
              </div>
            </div>
            <p className="small muted">
              按北京时间填写，提交时自动换算成服务端 UTC 存储。
            </p>
            <p className="small muted">
              商品、秒杀价与配额<b>不可改</b>：余量已经扣过、订单里也已存了价格快照，改了会让配置与既成事实对不上。
              要换商品请<b>新建</b>一场活动，再把这一场下架。
            </p>
            {editError ? <Alert kind="error">{editError}</Alert> : null}
          </>
        ) : null}
      </Modal>

      {/* 删除：用于清理「没有未关闭订单」的活动（订单全已关闭的也可删）；还有未关闭订单时服务端会拒并把原因带回来 */}
      <Modal
        open={deleting !== null}
        title="删除秒杀活动"
        onClose={() => { setDeleting(null); setDeleteError(null); }}
        footer={
          <>
            <button className="btn btn-primary" onClick={() => { setDeleting(null); setDeleteError(null); }}>取消</button>
            <button className="btn btn-ghost" disabled={deleteBusy} onClick={doDelete}>
              {deleteBusy ? '删除中…' : '确认删除'}
            </button>
          </>
        }
      >
        {deleting ? (
          <>
            <p>
              即将删除活动 <b>{deleting.title}</b>（<span className="mono small">{deleting.id}</span>）。
            </p>
            <p className="small muted">
              只能删除<b>没有未关闭订单</b>的活动（自测残留、建错的草稿；历史上被下过单但订单都已关闭的也算）。
              只要还有<b>待付款 / 已付款 / 已发货 / 已完成</b>的订单，服务端就会拒绝并提示——那时请改用「下架」：
              C 端不再展示，而订单与资金记录的来源链保持完整。
            </p>
            {deleteError ? <Alert kind="error">{deleteError}</Alert> : null}
          </>
        ) : null}
      </Modal>
    </div>
  );
};
