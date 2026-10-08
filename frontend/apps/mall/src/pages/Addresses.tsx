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

import React, { useCallback, useEffect, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { addressApi } from '@duoduo/api-sdk/endpoints';
import type { AddressUpsertRequest, AddressView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, Field, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';

/** 常用标签；自由填写也允许（后端只限制 20 字） */
const LABEL_PRESETS = ['家', '公司', '学校'] as const;

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

          <div className="grid grid-3">
            <Field label="省 / 直辖市" required>
              <input
                className="input"
                value={form.province}
                onChange={(e) => setForm({ ...form, province: e.target.value })}
                placeholder="例如 浙江省"
                maxLength={30}
              />
            </Field>
            <Field label="市" required hint="直辖市可与省相同">
              <input
                className="input"
                value={form.city}
                onChange={(e) => setForm({ ...form, city: e.target.value })}
                placeholder="例如 杭州市"
                maxLength={30}
              />
            </Field>
            <Field label="区 / 县" required>
              <input
                className="input"
                value={form.district}
                onChange={(e) => setForm({ ...form, district: e.target.value })}
                placeholder="例如 西湖区"
                maxLength={30}
              />
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
