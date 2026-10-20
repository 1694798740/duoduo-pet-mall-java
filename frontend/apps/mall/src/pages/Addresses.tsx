/**
 * 收货地址簿页（A8）。
 *
 * 数据来源：/api/addresses 全套 CRUD
 *   GET    /api/addresses                列表（默认地址排最前）
 *   GET    /api/addresses/default        默认地址（新用户返回 data:null，不是报错）
 *   POST   /api/addresses                新增
 *   PUT    /api/addresses/{id}           整体更新（PUT 不是 PATCH）
 *   POST   /api/addresses/{id}/default   设为默认
 *   DELETE /api/addresses/{id}           删除
 *
 * 关键契约：
 *  · 省 / 市 / 区 / 明细分开提交 —— 后端按「省+市+区+明细」拼接，
 *    直辖市（北京市 / 上海市）会自动去重市名，因此**前端不要自己拼整段地址**，
 *    一律用接口返回的 fullAddress。自己拼就会出现「北京市北京市朝阳区…」。
 *  · isDefault 只表示「设为默认」。不传 / false 都表示「不改变默认归属」——
 *    后端不存在「取消默认」这个动作（默认只能转移或随地址删除而顺延），
 *    因此界面上没有「取消默认」按钮。
 *  · 首个地址由后端自动设为默认，前端只是把复选框默认勾上以符合直觉。
 *  · 手机号在这里是**完整展示**的（自己的数据，编辑时要能核对），
 *    后端不做脱敏 —— 与订单列表给第三方看的场景不同。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { addressApi, geoApi } from '@duoduo/api-sdk/endpoints';
import type { AddressUpsertRequest, AddressView, RegeoCandidate } from '@duoduo/api-sdk/types';
import { loadRegions } from '@duoduo/shared/data/regions';
import type { RegionData } from '@duoduo/shared/data/regions';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, Field, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';

/** 常用标签；自由填写也允许（后端只限制 20 字） */
const LABEL_PRESETS = ['家', '公司', '学校'] as const;

/** 名称归一：去掉空白与常见行政后缀，用于把地图返回的名称匹配到区划表里 */
function normRegionName(raw: string): string {
  return raw
    .replace(/\s/gu, '')
    .replace(/(特别行政区|维吾尔自治区|回族自治区|壮族自治区|自治区|自治州|地区|省|市|盟|区|县)$/u, '');
}

/**
 * 在候选列表里找匹配项：精确 → 归一后相等 → 归一后前缀包含。
 * 地图服务商返回的名称常与区划表有细微差异（如「北京市」对「北京」、
 * 「恩施土家族苗族自治州」对「恩施州」），直接用 === 会大量匹配失败。
 */
function matchRegion<T extends { n: string }>(list: T[], raw: string | null | undefined): T | undefined {
  if (!raw) return undefined;
  const exact = list.find((x) => x.n === raw);
  if (exact) return exact;
  const t = normRegionName(raw);
  if (!t) return undefined;
  return (
    list.find((x) => normRegionName(x.n) === t) ??
    list.find((x) => normRegionName(x.n).startsWith(t) || t.startsWith(normRegionName(x.n)))
  );
}

/** 下拉选项：把「当前值」补进去 —— 历史地址可能不在内置数据里（如港澳台），不能被清空 */
function withCurrent(options: { n: string }[], current: string): { n: string }[] {
  if (!current || options.some((o) => o.n === current)) return options;
  return [...options, { n: current }];
}

interface FormState {
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  label: string;
  isDefault: boolean;
}

const EMPTY_FORM: FormState = {
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  label: '',
  isDefault: false,
};

function toForm(addr: AddressView): FormState {
  return {
    receiverName: addr.receiverName,
    receiverPhone: addr.receiverPhone,
    province: addr.province,
    city: addr.city,
    district: addr.district,
    detail: addr.detail,
    label: addr.label ?? '',
    isDefault: addr.isDefault,
  };
}

/** 表单 → 请求体：空串转 null，避免后端把「」当成有效值 */
function toPayload(form: FormState): AddressUpsertRequest {
  return {
    receiverName: form.receiverName.trim(),
    receiverPhone: form.receiverPhone.trim(),
    province: form.province.trim(),
    city: form.city.trim(),
    district: form.district.trim(),
    detail: form.detail.trim(),
    label: form.label.trim() || null,
    isDefault: form.isDefault,
  };
}

/** 手机号校验与后端 PHONE_REGEX 一致，提交前先拦，省一次必然 400 的往返 */
const PHONE_RE = /^1[3-9]\d{9}$/;

export const AddressesPage: React.FC = () => {
  const { push } = useToast();

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AddressView | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** 待确认删除的地址；非空时弹确认框 */
  const [confirmDelete, setConfirmDelete] = useState<AddressView | null>(null);

  /**
   * 省市区数据按需加载：动态 import 单独切块，只有打开地址弹窗才拉。
   * 失败不阻断填写 —— 下拉会退化成「只有当前值」的状态，用户仍可提交。
   */
  const [regions, setRegions] = useState<RegionData | null>(null);
  const [regionsError, setRegionsError] = useState<string | null>(null);
  const [locating, setLocating] = useState(false);
  const [locateHint, setLocateHint] = useState<string | null>(null);
  const [locateError, setLocateError] = useState<string | null>(null);
  /** 周边候选位置：单一回填可能落到隔壁路，让用户在附近挑 */
  const [candidates, setCandidates] = useState<RegeoCandidate[]>([]);
  /** 浏览器定位精度（米），用来解释「为什么门牌可能不准」 */
  const [accuracy, setAccuracy] = useState<number | null>(null);

  useEffect(() => {
    if (!modalOpen || regions) return undefined;
    let alive = true;
    loadRegions()
      .then((d) => {
        if (alive) setRegions(d);
      })
      .catch(() => {
        if (alive) setRegionsError('省市区数据加载失败，请刷新页面重试');
      });
    return () => {
      alive = false;
    };
  }, [modalOpen, regions]);

  const provinceOpts = useMemo(
    () => (regions ? withCurrent(regions.list, form.province) : []),
    [regions, form.province],
  );
  const provinceNode = regions?.list.find((p) => p.n === form.province);
  const cityOpts = useMemo(
    () => (provinceNode ? withCurrent(provinceNode.ch, form.city) : []),
    [provinceNode, form.city],
  );
  const cityNode = provinceNode?.ch.find((c) => c.n === form.city);
  const districtOpts = useMemo(
    () => (cityNode ? withCurrent(cityNode.ch, form.district) : []),
    [cityNode, form.district],
  );

  /**
   * 定位并回填（A1）。
   *
   * 三条失败路径都必须给出**可继续操作**的出口，不能只弹一句错误：
   *  · 浏览器不支持 / 拒绝授权 → 提示去手动选（下拉本来就是可用的）；
   *  · 服务端未配置地图 Key → 明确说「尚未配置」，而不是让用户以为定位坏了；
   *  · 接口失败 → 同样回到手选。
   */
  const locate = async () => {
    setLocateError(null);
    setLocateHint(null);
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setLocateError('当前浏览器不支持定位，请手动选择省市区');
      return;
    }
    setLocating(true);
    try {
      const pos = await new Promise<GeolocationPosition>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 8000,
          maximumAge: 60_000,
        });
      });
      // ★ 必须声明 wgs84：浏览器给的是 GPS 原始坐标，高德只认 GCJ-02。
      //   不转换的后果是地址落到 500 米开外（实测深圳花园坪 → 龙城公园），
      //   而不是报错 —— 这正是「偏差超过 50 米」的根因。
      const geo = await geoApi.regeo(pos.coords.longitude, pos.coords.latitude, 'wgs84');
      if (!geo.configured) {
        setLocateHint('地图服务尚未配置（服务端缺 Key），请手动选择省市区');
        return;
      }
      if (!regions) {
        setLocateHint('省市区数据还没加载好，请稍后再试一次');
        return;
      }
      const p = matchRegion(regions.list, geo.province);
      const c = p ? matchRegion(p.ch, geo.city ?? geo.province) : undefined;
      const d = c ? matchRegion(c.ch, geo.district) : undefined;
      if (!p) {
        setLocateHint('已定位，但没有匹配到可选的省市区，请手动选择');
        return;
      }
      setForm((f) => ({
        ...f,
        province: p.n,
        city: c?.n ?? '',
        district: d?.n ?? '',
        detail: geo.detail?.trim() ? geo.detail.trim() : f.detail,
      }));
      setCandidates(geo.candidates ?? []);
      const acc = Math.round(pos.coords.accuracy);
      setAccuracy(Number.isFinite(acc) && acc > 0 ? acc : null);
      setLocateHint(
        `已按定位填入「${[p.n, c?.n, d?.n].filter(Boolean).join(' / ')}」`
        + (Number.isFinite(acc) && acc > 0 ? `（定位精度约 ${acc} 米）` : '')
        + (geo.candidates?.length ? '，可点下方附近位置改选' : '，请核对门牌号'),
      );
    } catch (err) {
      const geoErr = err as GeolocationPositionError | undefined;
      if (geoErr && geoErr.code === 1) {
        setLocateError('定位权限被拒绝，请在浏览器地址栏允许定位，或手动选择');
      } else if (geoErr && typeof geoErr.code === 'number' && [2, 3].includes(geoErr.code)) {
        setLocateError('定位超时或不可用，请手动选择省市区');
      } else {
        setLocateError('定位服务调用失败，请手动选择省市区');
      }
    } finally {
      setLocating(false);
    }
  };

  const fetcher = useCallback(() => addressApi.list(), []);
  const { data, loading, error, reload } = useAsync<AddressView[]>(fetcher);
  const [addresses, setAddresses] = useState<AddressView[]>([]);

  useEffect(() => {
    if (data) setAddresses(data);
  }, [data]);

  const openCreate = () => {
    setEditing(null);
    // 第一个地址默认勾上「设为默认」：与后端「首条自动默认」的行为对齐，
    // 避免用户建完发现不是默认还要再点一次
    setForm({ ...EMPTY_FORM, isDefault: addresses.length === 0 });
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (addr: AddressView) => {
    setEditing(addr);
    setForm(toForm(addr));
    setFormError(null);
    setModalOpen(true);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!form.receiverName.trim()) {
      setFormError('请填写收货人姓名');
      return;
    }
    if (!PHONE_RE.test(form.receiverPhone.trim())) {
      setFormError('请输入合法的 11 位手机号（1 开头，第二位 3-9）');
      return;
    }
    if (!form.province.trim() || !form.city.trim() || !form.district.trim()) {
      setFormError('请填写完整的省 / 市 / 区');
      return;
    }
    if (!form.detail.trim()) {
      setFormError('请填写详细地址（街道门牌）');
      return;
    }

    setBusy(true);
    try {
      const payload = toPayload(form);
      if (editing) {
        await addressApi.update(editing.id, payload);
        push('地址已更新', 'success');
      } else {
        await addressApi.create(payload);
        push('地址已添加', 'success');
      }
      setModalOpen(false);
      await reload();
    } catch (err) {
      setFormError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const setDefault = async (addr: AddressView) => {
    try {
      await addressApi.setDefault(addr.id);
      push(`已把「${addr.receiverName}」的地址设为默认`, 'success');
      await reload();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const doDelete = async () => {
    if (!confirmDelete) return;
    const target = confirmDelete;
    setConfirmDelete(null);
    try {
      await addressApi.remove(target.id);
      // 后端在删除默认地址时会把剩下最早的一条顺延为默认，这里如实告知，
      // 免得用户以为「默认地址没了」
      push(
        target.isDefault
          ? '地址已删除；默认地址已自动顺延到其余地址中的第一条'
          : '地址已删除',
        'success',
      );
      await reload();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">收货地址</h2>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>
          + 新增地址
        </button>
      </div>

      <Alert kind="info">
        结算时可直接选择这里的地址，不用每次手填。默认地址会在结算页自动选中。
      </Alert>

      <div style={{ marginTop: 18 }}>
        {error ? (
          <Alert kind="error">
            {error}
            <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
              重试
            </button>
          </Alert>
        ) : loading && !data ? (
          <LoadingBlock text="正在加载收货地址…" />
        ) : addresses.length === 0 ? (
          <Empty
            icon="📍"
            title="还没有收货地址"
            desc="添加后下单时可直接选用，避免每次手填"
            action={
              <button className="btn btn-primary" onClick={openCreate}>
                立即添加
              </button>
            }
          />
        ) : (
          <div className="grid grid-2">
            {addresses.map((addr) => (
              <div className="card card-pad" key={addr.id}>
                <div className="row-between">
                  <strong style={{ fontSize: 16 }}>
                    {addr.receiverName}
                    <span className="small" style={{ marginLeft: 8, fontWeight: 400 }}>
                      {addr.receiverPhone}
                    </span>
                  </strong>
                  <span className="row" style={{ gap: 6 }}>
                    {addr.label ? <span className="tag">{addr.label}</span> : null}
                    {addr.isDefault ? <span className="tag tag-success">默认</span> : null}
                  </span>
                </div>

                {/* 用服务端拼好的 fullAddress，不自己拼 —— 直辖市去重逻辑只在服务端一处 */}
                <div className="small" style={{ marginTop: 8, lineHeight: 1.6 }}>
                  {addr.fullAddress}
                </div>

                <div className="row row-wrap" style={{ marginTop: 12 }}>
                  <button className="btn btn-ghost btn-sm" onClick={() => openEdit(addr)}>
                    编辑
                  </button>
                  {!addr.isDefault ? (
                    <button className="btn btn-ghost btn-sm" onClick={() => void setDefault(addr)}>
                      设为默认
                    </button>
                  ) : null}
                  <button
                    className="btn btn-danger btn-sm"
                    onClick={() => setConfirmDelete(addr)}
                  >
                    删除
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <Modal
        open={modalOpen}
        title={editing ? '编辑收货地址' : '新增收货地址'}
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setModalOpen(false)}>
              取消
            </button>
            <button className="btn btn-primary" type="submit" form="address-form" disabled={busy}>
              {busy ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        {formError ? (
          <div style={{ marginBottom: 14 }}>
            <Alert kind="error">{formError}</Alert>
          </div>
        ) : null}

        <form id="address-form" onSubmit={submit}>
          <div className="grid grid-2">
            <Field label="收货人" required>
              <input
                className="input"
                value={form.receiverName}
                onChange={(e) => setForm({ ...form, receiverName: e.target.value })}
                placeholder="例如 张小三"
                maxLength={50}
              />
            </Field>

            <Field label="手机号" required hint="快递员联系用">
              <input
                className="input"
                value={form.receiverPhone}
                onChange={(e) => setForm({ ...form, receiverPhone: e.target.value })}
                placeholder="11 位手机号"
                maxLength={11}
                inputMode="numeric"
              />
            </Field>
          </div>

          {/* 省 / 市 / 区：三级联动下拉（A1）。省变了清空市区、市变了清空区，
              避免出现「浙江省 / 深圳市 / 南山区」这种跨省组合。 */}
          <div className="row-between" style={{ marginBottom: 6 }}>
            <span className="small" style={{ color: 'var(--text-3)' }}>
              {regions ? `共 ${regions.list.length} 个省级行政区，选省后自动列出该省城市` : '省市区数据加载中…'}
            </span>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => void locate()}
              disabled={locating}
            >
              {locating ? '定位中…' : '📍 定位当前地址'}
            </button>
          </div>
          {regionsError ? (
            <div style={{ marginBottom: 10 }}>
              <Alert kind="warn">{regionsError}</Alert>
            </div>
          ) : null}
          {locateError ? (
            <div style={{ marginBottom: 10 }}>
              <Alert kind="error">{locateError}</Alert>
            </div>
          ) : null}
          {locateHint ? (
            <div style={{ marginBottom: 10 }}>
              <Alert kind="info">{locateHint}</Alert>
            </div>
          ) : null}
          {/* 附近候选：解决「只回填一个位置、错了只能自己重打」的问题。
              数据来自逆地理 extensions=all 带回的周边 POI，不额外消耗配额。 */}
          {candidates.length > 0 ? (
            <div style={{ marginBottom: 12 }}>
              <div className="small" style={{ marginBottom: 6, color: 'var(--text-3)' }}>
                附近位置（点一条填入详细地址{accuracy != null ? `，定位精度约 ${accuracy} 米` : ''}）
              </div>
              <div className="row row-wrap" style={{ gap: 8 }}>
                {candidates.map((cd) => (
                  <button
                    key={`${cd.name}-${cd.lng}-${cd.lat}`}
                    type="button"
                    className={`btn btn-sm ${form.detail === cd.name ? 'btn-primary' : 'btn-ghost'}`}
                    title={cd.address ?? cd.name}
                    onClick={() => {
                      setForm((f) => ({ ...f, detail: cd.name }));
                      setLocateHint(
                        `已选「${cd.name}」`
                        + (cd.distance != null ? `（距定位点 ${cd.distance} 米）` : '')
                        + '，可再补充门牌号',
                      );
                    }}
                  >
                    {cd.name}
                    {cd.distance != null ? ` · ${cd.distance}米` : ''}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          <div className="grid grid-3">
            <Field label="省 / 直辖市" required>
              <select
                className="input"
                aria-label="省 / 直辖市"
                value={form.province}
                onChange={(e) => setForm({ ...form, province: e.target.value, city: '', district: '' })}
              >
                <option value="">请选择省份</option>
                {provinceOpts.map((p) => (
                  <option key={p.n} value={p.n}>{p.n}</option>
                ))}
              </select>
            </Field>
            <Field label="市" required>
              <select
                className="input"
                aria-label="市"
                value={form.city}
                disabled={!provinceNode}
                onChange={(e) => setForm({ ...form, city: e.target.value, district: '' })}
              >
                <option value="">{provinceNode ? '请选择城市' : '请先选择省份'}</option>
                {cityOpts.map((c) => (
                  <option key={c.n} value={c.n}>{c.n}</option>
                ))}
              </select>
            </Field>
            <Field label="区 / 县" required>
              <select
                className="input"
                aria-label="区 / 县"
                value={form.district}
                disabled={!cityNode}
                onChange={(e) => setForm({ ...form, district: e.target.value })}
              >
                <option value="">{cityNode ? '请选择区县' : '请先选择城市'}</option>
                {districtOpts.map((d) => (
                  <option key={d.n} value={d.n}>{d.n}</option>
                ))}
              </select>
            </Field>
          </div>

          <Field label="详细地址" required hint="街道、门牌、楼栋房号">
            <input
              className="input"
              value={form.detail}
              onChange={(e) => setForm({ ...form, detail: e.target.value })}
              placeholder="例如 文三路 99 号 3 幢 501 室"
              maxLength={200}
            />
          </Field>

          <Field label="标签" hint="仅用于区分，例如 家 / 公司">
            <div className="row row-wrap" style={{ gap: 8 }}>
              {LABEL_PRESETS.map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={`btn btn-sm ${form.label === preset ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => setForm({ ...form, label: form.label === preset ? '' : preset })}
                >
                  {preset}
                </button>
              ))}
              <input
                className="input"
                style={{ maxWidth: 160 }}
                value={form.label}
                onChange={(e) => setForm({ ...form, label: e.target.value })}
                placeholder="自定义"
                maxLength={20}
              />
            </div>
          </Field>

          <label className="row small" style={{ gap: 6 }}>
            <input
              type="checkbox"
              checked={form.isDefault}
              // 已是默认地址时不允许取消勾选：后端没有「取消默认」这个动作，
              // 让它可点只会产出一个「点了没反应」的假交互
              disabled={editing?.isDefault === true}
              onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
            />
            设为默认地址
            {editing?.isDefault ? <span className="small">（当前已是默认，改由其他地址的「设为默认」切换）</span> : null}
          </label>
        </form>
      </Modal>

      <Modal
        open={confirmDelete !== null}
        title="确认删除地址"
        onClose={() => setConfirmDelete(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>
              取消
            </button>
            <button className="btn btn-danger" onClick={() => void doDelete()}>
              确认删除
            </button>
          </>
        }
      >
        {confirmDelete ? (
          <div>
            <p>
              将删除「{confirmDelete.receiverName} · {confirmDelete.receiverPhone}」的地址：
            </p>
            <p className="small" style={{ marginTop: 8 }}>
              {confirmDelete.fullAddress}
            </p>
            <Alert kind="warn">
              已下单的历史订单不受影响 —— 订单里保存的是**下单当时的地址快照**，
              不随地址簿变化。
            </Alert>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
