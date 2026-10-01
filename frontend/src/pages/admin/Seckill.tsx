/**
 * 平台管理后台 · 秒杀活动管理（A14 遗留补齐）。
 *
 * 后端三个端点早已齐备（AdminSeckillController），但此前后台**没有页面** ——
 * 运营只能靠 curl 或直连数据库建活动，等于「功能存在但不可用」。本页把它们接到界面上。
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
 */

import React, { useCallback, useState } from 'react';
import { adminApi, productApi } from '../../api/endpoints';
import type {
  ProductCard,
  SeckillActivityCreateRequest,
  SeckillActivityView,
  SkuView,
} from '../../api/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, formatPrice, useToast,
} from '../../components/ui';
import { useAsync } from '../../components/hooks';
import { toFriendlyMessage } from '../../api/client';
import { useAuth } from '../../store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 阶段 → 中文 + 配色类（与全局 badge 样式对齐） */
const PHASE_LABELS: Record<string, { text: string; cls: string }> = {
  ONGOING: { text: '进行中', cls: 'tag tag-success' },
  UPCOMING: { text: '未开始', cls: 'tag tag-second' },
  ENDED: { text: '已结束', cls: 'tag' },
};

type SkuRow = SeckillActivityCreateRequest['skus'][number];

const emptyRow = (): SkuRow => ({
  skuId: '',
  seckillPrice: 0,
  originalPrice: null,
  totalStock: 1,
  perLimit: 1,
});

/** ISO 本地串（无时区后缀）→ <input type="datetime-local"> 需要的 'YYYY-MM-DDTHH:mm' */
const toLocalInput = (iso: string | null | undefined): string => (iso ? iso.slice(0, 16) : '');

/** 反向：输入框值 → 服务端 LocalDateTime 需要的 'YYYY-MM-DDTHH:mm:00' */
const fromLocalInput = (v: string): string => (v.length === 16 ? `${v}:00` : v);

/** 当前 UTC 挂钟（容器时区全 UTC，不能用 toLocaleString 那套本地时间） */
const utcInputNow = (plusMinutes: number): string =>
  new Date(Date.now() + plusMinutes * 60_000).toISOString().slice(0, 16);

export const AdminSeckillPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<{ title: string; startAt: string; endAt: string }>({
    title: '',
    startAt: utcInputNow(5),
    endAt: utcInputNow(125),
  });
  const [rows, setRows] = useState<SkuRow[]>([emptyRow()]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

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
    setForm({ title: '', startAt: utcInputNow(5), endAt: utcInputNow(125) });
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

  const updateRow = (index: number, patch: Partial<SkuRow>) =>
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

  /** 选中 SKU：回填 skuId，并把商品现价当作原价默认值（运营仍可改） */
  const applySku = (sku: SkuView) => {
    updateRow(pickRowIndex, {
      skuId: sku.id,
      originalPrice: sku.price,
      seckillPrice: Math.max(1, Math.round(sku.price * 0.8)),
    });
    setPicking(false);
    push('已回填 SKU', 'success');
  };

  const save = async () => {
    setActionError(null);
    const skus = rows
      .filter((r) => r.skuId.trim() !== '')
      .map((r) => ({
        skuId: r.skuId.trim(),
        // 金额单位：页面按元录入，服务端按分
        seckillPrice: Math.round(r.seckillPrice * 100),
        originalPrice: r.originalPrice == null || r.originalPrice === 0
          ? null
          : Math.round(r.originalPrice * 100),
        totalStock: r.totalStock,
        perLimit: r.perLimit ?? 1,
      }));
    if (!form.title.trim()) {
      setActionError('请填写活动标题');
      return;
    }
    if (skus.length === 0) {
      setActionError('至少配置一个秒杀商品');
      return;
    }
    if (!form.startAt || !form.endAt) {
      setActionError('请填写开始与结束时间');
      return;
    }
    if (fromLocalInput(form.endAt) <= fromLocalInput(form.startAt)) {
      setActionError('结束时间必须晚于开始时间');
      return;
    }
    const body: SeckillActivityCreateRequest = {
      title: form.title.trim(),
      startAt: fromLocalInput(form.startAt),
      endAt: fromLocalInput(form.endAt),
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

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 秒杀活动</h2>
      <AdminTabs />

      <div className="row-between row-wrap" style={{ marginBottom: 14 }}>
        <span className="small muted">
          共 {items.length} 场活动（含已下架）　·　时间均为 <b>UTC</b>，不是北京时间
        </span>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>+ 新建活动</button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {items.length === 0 ? (
        <Empty title="还没有秒杀活动" desc="点右上角「新建活动」创建第一场" />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>活动</th>
                <th>时间（UTC）</th>
                <th>阶段</th>
                <th>开关</th>
                <th>秒杀商品与余量</th>
                <th style={{ width: 120 }}>操作</th>
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
                      <div>{toLocalInput(item.startAt)}</div>
                      <div className="muted">至 {toLocalInput(item.endAt)}</div>
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
                      {item.enabled ? (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, false)}>下架</button>
                      ) : (
                        <button className="btn btn-ghost btn-sm" onClick={() => toggle(item, true)}>上架</button>
                      )}
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
            <Field label="开始时间（UTC）" required>
              <input
                className="input"
                type="datetime-local"
                value={form.startAt}
                onChange={(e) => setForm({ ...form, startAt: e.target.value })}
              />
            </Field>
          </div>
          <div style={{ flex: 1, minWidth: 200 }}>
            <Field label="结束时间（UTC）" required>
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
          服务端与容器时区均为 UTC，上面填的是什么时刻就是 UTC 的什么时刻。
          想让北京时间的 20:00 开抢，这里要填 <b>12:00</b>。
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
              <div style={{ flex: 1, minWidth: 110 }}>
                <Field label="秒杀价（元）" required>
                  <input
                    className="input"
                    type="number"
                    min={0.01}
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
                <Field label="活动配额" required>
                  <input
                    className="input"
                    type="number"
                    min={1}
                    step={1}
                    value={row.totalStock}
                    onChange={(e) => updateRow(index, { totalStock: Number(e.target.value) })}
                  />
                </Field>
              </div>
              <div style={{ flex: 1, minWidth: 90 }}>
                <Field label="每人限购">
                  <input
                    className="input"
                    type="number"
                    min={1}
                    max={10}
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
                      　<span className="mono muted">{sku.id}</span>
                    </span>
                    <button className="btn btn-ghost btn-sm" onClick={() => applySku(sku)}>用这个</button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
