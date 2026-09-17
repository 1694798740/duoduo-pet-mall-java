/**
 * 宠物档案页。
 *
 * 数据来源：/api/pets 全套 CRUD
 *   GET    /api/pets              列表
 *   POST   /api/pets              新增
 *   PUT    /api/pets/{id}         整体更新（注意是 PUT 不是 PATCH）
 *   POST   /api/pets/{id}/default 设为默认档案
 *   DELETE /api/pets/{id}         删除
 *
 * 关键契约：
 *  · species 只接受 cat|dog|other；lifeStage 只接受 BABY|ADULT|SENIOR；gender 只接受 MALE|FEMALE|UNKNOWN
 *    —— 后端有校验注解，前端下拉框只用这几个值，避免提交必然 400 的脏数据
 *  · allergens 是字符串数组，界面上用逗号分隔输入
 *  · 默认档案影响首页推荐排序，因此「设为默认」是核心动作
 */

import React, { useCallback, useEffect, useState } from 'react';
import { toFriendlyMessage } from '../api/client';
import { petApi } from '../api/endpoints';
import type { PetUpsertRequest, PetView } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, Empty, Field, LoadingBlock, Modal, useToast } from '../components/ui';
import { useAuth } from '../store/auth';

const SPECIES_OPTIONS = [
  { value: 'cat', label: '猫' },
  { value: 'dog', label: '狗' },
  { value: 'other', label: '其他' },
] as const;

const LIFE_STAGE_OPTIONS = [
  { value: '', label: '未指定' },
  { value: 'BABY', label: '幼年' },
  { value: 'ADULT', label: '成年' },
  { value: 'SENIOR', label: '老年' },
] as const;

const GENDER_OPTIONS = [
  { value: 'UNKNOWN', label: '未知' },
  { value: 'MALE', label: '公' },
  { value: 'FEMALE', label: '母' },
] as const;

interface FormState {
  name: string;
  species: 'cat' | 'dog' | 'other';
  breed: string;
  birthday: string;
  lifeStage: '' | 'BABY' | 'ADULT' | 'SENIOR';
  weightKg: string;
  gender: 'MALE' | 'FEMALE' | 'UNKNOWN';
  neutered: boolean;
  allergens: string;
  medicalHistory: string;
  isDefault: boolean;
}

const EMPTY_FORM: FormState = {
  name: '',
  species: 'cat',
  breed: '',
  birthday: '',
  lifeStage: '',
  weightKg: '',
  gender: 'UNKNOWN',
  neutered: false,
  allergens: '',
  medicalHistory: '',
  isDefault: false,
};

function toForm(pet: PetView): FormState {
  return {
    name: pet.name,
    species: (pet.species as FormState['species']) ?? 'cat',
    breed: pet.breed ?? '',
    birthday: pet.birthday ?? '',
    lifeStage: (pet.lifeStage as FormState['lifeStage']) ?? '',
    weightKg: pet.weightKg != null ? String(pet.weightKg) : '',
    gender: (pet.gender as FormState['gender']) ?? 'UNKNOWN',
    neutered: pet.neutered,
    allergens: (pet.allergens ?? []).join(', '),
    medicalHistory: pet.medicalHistory ?? '',
    isDefault: pet.isDefault,
  };
}

/** 表单 → 请求体：空串一律转 null，避免后端把「」当成有效值 */
function toPayload(form: FormState): PetUpsertRequest {
  const weight = form.weightKg.trim() === '' ? null : Number(form.weightKg);
  return {
    name: form.name.trim(),
    species: form.species,
    breed: form.breed.trim() || null,
    birthday: form.birthday || null,
    lifeStage: form.lifeStage || null,
    weightKg: weight !== null && Number.isFinite(weight) ? weight : null,
    gender: form.gender,
    neutered: form.neutered,
    allergens: form.allergens
      .split(/[,，、\s]+/)
      .map((s) => s.trim())
      .filter(Boolean),
    medicalHistory: form.medicalHistory.trim() || null,
    isDefault: form.isDefault,
  };
}

/** 与后端口径一致的阶段划分：幼年 ≤12 个月，成年 13–84 个月，老年 >84 个月 */
function inferLifeStage(birthday: string): FormState['lifeStage'] {
  if (!birthday) return '';
  const birth = new Date(birthday);
  if (Number.isNaN(birth.getTime())) return '';
  const now = new Date();
  let months = (now.getFullYear() - birth.getFullYear()) * 12 + (now.getMonth() - birth.getMonth());
  if (now.getDate() < birth.getDate()) months -= 1;
  if (months <= 12) return 'BABY';
  if (months <= 84) return 'ADULT';
  return 'SENIOR';
}

function monthsOf(birthday: string): number | null {
  if (!birthday) return null;
  const birth = new Date(birthday);
  if (Number.isNaN(birth.getTime())) return null;
  const now = new Date();
  let months = (now.getFullYear() - birth.getFullYear()) * 12 + (now.getMonth() - birth.getMonth());
  if (now.getDate() < birth.getDate()) months -= 1;
  return months;
}

const STAGE_LABEL: Record<string, string> = { BABY: '幼年', ADULT: '成年', SENIOR: '老年' };

export const PetsPage: React.FC = () => {
  const { push } = useToast();
  const { refreshPets: refreshGlobalPets } = useAuth();

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<PetView | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const fetcher = useCallback(() => petApi.list(), []);
  const { data, loading, error, reload } = useAsync<PetView[]>(fetcher);
  const [pets, setPets] = useState<PetView[]>([]);

  useEffect(() => {
    if (data) setPets(data);
  }, [data]);

  const openCreate = () => {
    setEditing(null);
    setForm({ ...EMPTY_FORM, isDefault: pets.length === 0 });
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (pet: PetView) => {
    setEditing(pet);
    setForm(toForm(pet));
    setFormError(null);
    setModalOpen(true);
  };

  // 生日与生命阶段的实时一致性提示（后端会做同样校验，这里提前给出可读原因）
  const inferredStage = inferLifeStage(form.birthday);
  const inferredMonths = monthsOf(form.birthday);
  const stageMismatch =
    form.birthday !== '' && form.lifeStage !== '' && inferredStage !== '' && inferredStage !== form.lifeStage;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!form.name.trim()) {
      setFormError('请填写宠物名字');
      return;
    }
    if (form.weightKg.trim() && !Number.isFinite(Number(form.weightKg))) {
      setFormError('体重需要是数字，单位为 kg');
      return;
    }

    // 后端强校验：lifeStage 必须与 birthday 推算结果一致（否则 422）。提交前先拦截并说明原因。
    const inferred = inferLifeStage(form.birthday);
    if (form.birthday && form.lifeStage && inferred && inferred !== form.lifeStage) {
      const months = monthsOf(form.birthday);
      setFormError(
        `生命阶段与生日不一致：按生日推算月龄为 ${months} 个月，应选「${STAGE_LABEL[inferred]}」` +
          `（幼年 ≤12 个月、成年 13–84 个月、老年 84 个月以上），请调整后重新提交`,
      );
      return;
    }

    setBusy(true);
    try {
      const payload = toPayload(form);
      if (editing) {
        await petApi.update(editing.id, payload);
        push('档案已更新', 'success');
      } else {
        await petApi.create(payload);
        push('档案已创建', 'success');
      }
      setModalOpen(false);
      await reload();
      await refreshGlobalPets();
    } catch (err) {
      setFormError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const setDefault = async (pet: PetView) => {
    try {
      await petApi.setDefault(pet.id);
      push(`已把「${pet.name}」设为默认档案`, 'success');
      await reload();
      await refreshGlobalPets();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const remove = async (pet: PetView) => {
    try {
      await petApi.remove(pet.id);
      push('档案已删除', 'success');
      await reload();
      await refreshGlobalPets();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的宠物档案</h2>
        <button className="btn btn-primary btn-sm" onClick={openCreate}>
          + 新建档案
        </button>
      </div>

      <Alert kind="info">
        档案用于商品适配与过敏原提示。过敏原冲突时商品详情页会给出显式提醒，
        下单前请再确认一次。
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
          <LoadingBlock text="正在加载宠物档案…" />
        ) : pets.length === 0 ? (
          <Empty
            icon="🐾"
            title="还没有宠物档案"
            desc="建档后首页推荐与商品适配提示会更准确"
            action={
              <button className="btn btn-primary" onClick={openCreate}>
                立即建档
              </button>
            }
          />
        ) : (
          <div className="grid grid-3">
            {pets.map((pet) => (
              <div className="card card-pad" key={pet.id}>
                <div className="row-between">
                  <strong style={{ fontSize: 16 }}>
                    {pet.species === 'cat' ? '🐱' : pet.species === 'dog' ? '🐶' : '🐾'} {pet.name}
                  </strong>
                  {pet.isDefault ? <span className="tag tag-success">默认</span> : null}
                </div>

                <dl className="spec-list" style={{ marginTop: 10 }}>
                  <dt>品种</dt>
                  <dd>{pet.breed ?? '未填写'}</dd>
                  <dt>阶段</dt>
                  <dd>
                    {pet.lifeStage
                      ? (LIFE_STAGE_OPTIONS.find((o) => o.value === pet.lifeStage)?.label ??
                        pet.lifeStage)
                      : '未指定'}
                    {pet.ageMonths != null ? ` · ${pet.ageMonths} 个月` : ''}
                  </dd>
                  <dt>体重</dt>
                  <dd>{pet.weightKg != null ? `${pet.weightKg} kg` : '未填写'}</dd>
                  <dt>性别 / 绝育</dt>
                  <dd>
                    {GENDER_OPTIONS.find((o) => o.value === pet.gender)?.label ?? '未知'} ·{' '}
                    {pet.neutered ? '已绝育' : '未绝育'}
                  </dd>
                  <dt>过敏原</dt>
                  <dd>{pet.allergens.length ? pet.allergens.join('、') : '无记录'}</dd>
                  <dt>病史</dt>
                  <dd>{pet.medicalHistory ?? '无记录'}</dd>
                </dl>

                <div className="row row-wrap" style={{ marginTop: 12 }}>
                  <button className="btn btn-ghost btn-sm" onClick={() => openEdit(pet)}>
                    编辑
                  </button>
                  {!pet.isDefault ? (
                    <button className="btn btn-ghost btn-sm" onClick={() => void setDefault(pet)}>
                      设为默认
                    </button>
                  ) : null}
                  <button className="btn btn-danger btn-sm" onClick={() => void remove(pet)}>
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
        title={editing ? `编辑「${editing.name}」的档案` : '新建宠物档案'}
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setModalOpen(false)}>
              取消
            </button>
            <button className="btn btn-primary" type="submit" form="pet-form" disabled={busy}>
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

        <form id="pet-form" onSubmit={submit}>
          <Field label="名字" required>
            <input
              className="input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="例如 年糕"
              maxLength={20}
            />
          </Field>

          <div className="grid grid-2">
            <Field label="物种" required>
              <select
                className="select"
                value={form.species}
                onChange={(e) =>
                  setForm({ ...form, species: e.target.value as FormState['species'] })
                }
              >
                {SPECIES_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="品种">
              <input
                className="input"
                value={form.breed}
                onChange={(e) => setForm({ ...form, breed: e.target.value })}
                placeholder="例如 布偶"
                maxLength={40}
              />
            </Field>

            <Field label="生日" hint="用于自动推算月龄">
              <input
                className="input"
                type="date"
                value={form.birthday}
                onChange={(e) => setForm({ ...form, birthday: e.target.value })}
              />
            </Field>

            <Field label="生命阶段">
              <select
                className="select"
                value={form.lifeStage}
                onChange={(e) =>
                  setForm({ ...form, lifeStage: e.target.value as FormState['lifeStage'] })
                }
              >
                {LIFE_STAGE_OPTIONS.map((o) => (
                  <option key={o.label} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>

            {stageMismatch && (
              <Alert kind="warn">
                按生日推算月龄为 {inferredMonths} 个月，与所选阶段不一致，应选
                「{STAGE_LABEL[inferredStage] ?? inferredStage}」
                （幼年 ≤12 个月、成年 13–84 个月、老年 84 个月以上）。
                <button
                  type="button"
                  className="link"
                  onClick={() => setForm({ ...form, lifeStage: inferredStage })}
                >
                  改为{STAGE_LABEL[inferredStage] ?? inferredStage}
                </button>
              </Alert>
            )}

            <Field label="体重" hint="单位 kg，可填小数">
              <input
                className="input"
                value={form.weightKg}
                onChange={(e) => setForm({ ...form, weightKg: e.target.value })}
                placeholder="例如 4.2"
                inputMode="decimal"
              />
            </Field>

            <Field label="性别">
              <select
                className="select"
                value={form.gender}
                onChange={(e) => setForm({ ...form, gender: e.target.value as FormState['gender'] })}
              >
                {GENDER_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <Field label="过敏原" hint="多个用逗号分隔，例如：鸡肉, 谷物">
            <input
              className="input"
              value={form.allergens}
              onChange={(e) => setForm({ ...form, allergens: e.target.value })}
              placeholder="鸡肉, 谷物"
            />
          </Field>

          <Field label="病史">
            <textarea
              className="textarea"
              rows={3}
              value={form.medicalHistory}
              onChange={(e) => setForm({ ...form, medicalHistory: e.target.value })}
              placeholder="例如：幼时患过猫鼻支，已痊愈"
            />
          </Field>

          <div className="row row-wrap">
            <label className="row small" style={{ gap: 6 }}>
              <input
                type="checkbox"
                checked={form.neutered}
                onChange={(e) => setForm({ ...form, neutered: e.target.checked })}
              />
              已绝育
            </label>
            <label className="row small" style={{ gap: 6 }}>
              <input
                type="checkbox"
                checked={form.isDefault}
                onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
              />
              设为默认档案
            </label>
          </div>
        </form>
      </Modal>
    </div>
  );
};
