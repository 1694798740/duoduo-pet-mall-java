/**
 * AI 记忆库（2026-10-29 · 第二阶段）。
 *
 * <h2>为什么把「低风险」和「高风险」分成两个保存按钮</h2>
 * 物种、预算、偏好改错了，用户下次对话里改回来就行；过敏原与健康备注是**健康相关信息**，
 * 一旦写错会直接影响推荐（比如把「鸡肉过敏」记成「鸡肉不过敏」）。
 * 所以高风险内容不随表单一起保存，而是先申请一次性令牌、弹出明确的二次确认，
 * 确认后才落库 —— 令牌与内容绑定，用户确认的不是「一个可能被替换的请求」。
 *
 * <h2>为什么高风险内容不在输入时自动保存</h2>
 * 自动保存会让「用户还在打字」与「已经写入」无法区分；健康信息宁可多一次点击，
 * 也不能出现「我没确认过却被记住了」。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { memoryApi } from '@duoduo/api-sdk/endpoints';
import type { AiMemoryView } from '@duoduo/api-sdk/types';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { formatBjDateTime } from '@duoduo/shared/lib/time';

/** 与服务端同一套物种枚举；label 只影响展示 */
const SPECIES_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '未设置' },
  { value: 'cat', label: '猫' },
  { value: 'dog', label: '狗' },
  { value: 'small_pet', label: '仓鼠 / 兔 / 龙猫' },
  { value: 'bird', label: '鸟' },
  { value: 'fish', label: '鱼' },
  { value: 'reptile', label: '爬宠' },
  { value: 'other', label: '其他' },
];

/** 逗号 / 顿号 / 换行都当分隔符：用户不该被「必须用英文逗号」这种规则教育 */
const splitList = (raw: string): string[] =>
  raw
    .split(/[,，、\n]/)
    .map((item) => item.trim())
    .filter(Boolean);

const centsToYuan = (cents: number | string | null | undefined): string => {
  if (cents === null || cents === undefined || cents === '') return '';
  const n = Number(cents);
  return Number.isFinite(n) ? String(n / 100) : '';
};

/** 元 → 分；非法输入返回 null（由调用方提示，而不是静默变成 0） */
const yuanToCents = (yuan: string): number | null => {
  const trimmed = yuan.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.round(n * 100);
};

export const AiMemoryPage: React.FC = () => {
  const { push } = useToast();
  const confirm = useConfirm();

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<AiMemoryView | null>(null);

  // 低风险表单
  const [nickname, setNickname] = useState('');
  const [species, setSpecies] = useState('');
  const [budgetMinYuan, setBudgetMinYuan] = useState('');
  const [budgetMaxYuan, setBudgetMaxYuan] = useState('');
  const [categories, setCategories] = useState('');
  const [brands, setBrands] = useState('');

  // 高风险表单
  const [allergens, setAllergens] = useState('');
  const [healthNotes, setHealthNotes] = useState('');

  const [savingLow, setSavingLow] = useState(false);
  const [savingHigh, setSavingHigh] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const applyView = useCallback((view: AiMemoryView) => {
    setLoaded(view);
    setNickname(view.nickname ?? '');
    setSpecies(view.species ?? '');
    setBudgetMinYuan(centsToYuan(view.budgetMinCents));
    setBudgetMaxYuan(centsToYuan(view.budgetMaxCents));
    setCategories((view.categories ?? []).join('、'));
    setBrands((view.brands ?? []).join('、'));
    setAllergens((view.allergens ?? []).join('、'));
    setHealthNotes(view.healthNotes ?? '');
  }, []);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const view = await memoryApi.view(signal ? { signal } : undefined);
        if (signal?.aborted) return;
        applyView(view);
        setLoadError(null);
      } catch (error) {
        if (signal?.aborted) return;
        setLoadError(toFriendlyMessage(error));
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [applyView],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const speciesLabel = useMemo(
    () => SPECIES_OPTIONS.find((item) => item.value === species)?.label ?? species,
    [species],
  );

  const saveLowRisk = async () => {
    const min = yuanToCents(budgetMinYuan);
    const max = yuanToCents(budgetMaxYuan);
    if (budgetMinYuan.trim() && min === null) {
      push('预算下限请填写非负数字（单位：元）', 'error');
      return;
    }
    if (budgetMaxYuan.trim() && max === null) {
      push('预算上限请填写非负数字（单位：元）', 'error');
      return;
    }
    if (min !== null && max !== null && min > max) {
      push('预算下限不能大于上限', 'error');
      return;
    }
    setSavingLow(true);
    try {
      const view = await memoryApi.update({
        species: species || null,
        budgetMinCents: min,
        budgetMaxCents: max,
        // 数组总是发送：清空输入 = 清空偏好（null 在服务端表示「不修改」）
        categories: splitList(categories),
        brands: splitList(brands),
        nickname: nickname.trim() || null,
      });
      applyView(view);
      push('偏好已保存', 'success');
    } catch (error) {
      push(toFriendlyMessage(error), 'error');
    } finally {
      setSavingLow(false);
    }
  };

  /**
   * 高风险保存：申请令牌 → 明确二次确认 → 带令牌提交。
   *
   * 任何一步失败都**不写入**：令牌拿不到就提示重试，用户取消就什么都不发生。
   */
  const saveHighRisk = async () => {
    const nextAllergens = splitList(allergens);
    const nextNotes = healthNotes.trim() || null;
    const prevAllergens = loaded?.allergens ?? [];
    const prevNotes = loaded?.healthNotes ?? null;
    const changed =
      nextAllergens.join('|') !== prevAllergens.join('|') || nextNotes !== prevNotes;
    if (!changed) {
      push('过敏原与健康备注没有变化', 'info');
      return;
    }

    setSavingHigh(true);
    try {
      const ticket = await memoryApi.requestHighRisk({
        allergens: nextAllergens,
        healthNotes: nextNotes,
      });

      const preview = ticket.preview ?? { allergens: nextAllergens, healthNotes: nextNotes };
      const summary =
        (preview.allergens ?? []).length > 0
          ? `过敏原：${(preview.allergens ?? []).join('、')}`
          : '过敏原：无';
      const notes = preview.healthNotes ? `健康备注：${preview.healthNotes}` : '健康备注：无';

      const ok = await confirm({
        title: '确认保存高风险记忆',
        message: (
          <div>
            <p style={{ marginTop: 0 }}>
              过敏原与健康信息极其重要，保存后 AI 在推荐商品时会严格参考它们。
              请确认以下内容无误：
            </p>
            <p>
              <strong>{summary}</strong>
              <br />
              <strong>{notes}</strong>
            </p>
            <p className="small muted" style={{ marginBottom: 0 }}>
              确认链接 {Math.round((ticket.expireSeconds ?? 300) / 60)} 分钟内有效，且只能使用一次。
            </p>
          </div>
        ),
        confirmText: '确认保存',
        cancelText: '再检查一下',
        danger: true,
      });
      if (!ok) {
        push('已取消保存，内容未写入', 'info');
        return;
      }

      const view = await memoryApi.confirmHighRisk({
        token: ticket.token,
        allergens: preview.allergens ?? [],
        healthNotes: preview.healthNotes ?? null,
      });
      applyView(view);
      push('高风险记忆已确认保存', 'success');
    } catch (error) {
      push(toFriendlyMessage(error), 'error');
    } finally {
      setSavingHigh(false);
    }
  };

  const clearAll = async () => {
    const ok = await confirm({
      title: '清除所有 AI 记忆',
      message: (
        <div>
          <p style={{ marginTop: 0 }}>
            会删除 AI 记住的偏好、预算、过敏原与健康备注。删除后 AI 将像第一次见你一样对话。
          </p>
          <p className="small muted" style={{ marginBottom: 0 }}>
            此操作不可撤销；已发生的订单与通知不受影响。
          </p>
        </div>
      ),
      confirmText: '确认清除',
      cancelText: '先不清除',
      danger: true,
    });
    if (!ok) return;

    setDeleting(true);
    try {
      await memoryApi.remove();
      setLoaded(null);
      setNickname('');
      setSpecies('');
      setBudgetMinYuan('');
      setBudgetMaxYuan('');
      setCategories('');
      setBrands('');
      setAllergens('');
      setHealthNotes('');
      push('AI 记忆已清除', 'success');
    } catch (error) {
      push(toFriendlyMessage(error), 'error');
    } finally {
      setDeleting(false);
    }
  };

  if (loading) return <LoadingBlock text="正在读取 AI 记忆…" />;

  return (
    <div className="container section">
      <div className="section-head">
        <h1 className="section-title">AI 记忆库</h1>
        <span className="small muted">
          {loaded?.updatedAt ? `最近更新：${formatBjDateTime(loaded.updatedAt)}` : '还没有保存过记忆'}
        </span>
      </div>

      {loadError ? <Alert kind="error">{loadError}</Alert> : null}

      <Alert kind="info">
        记忆只用于让「多多」更懂你：推荐时优先考虑物种、预算、过敏原与健康备注。
        你可以随时修改或清除；AI 不会用它做诊断，也不会替代兽医。
      </Alert>

      <div className="grid grid-2" style={{ alignItems: 'start', marginTop: 14 }}>
        {/* ---------- 低风险 ---------- */}
        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>偏好（可直接保存）</h3>

          <label className="small muted" htmlFor="memory-nickname">希望怎么称呼你</label>
          <input
            id="memory-nickname"
            value={nickname}
            maxLength={20}
            placeholder="如 小明"
            onChange={(e) => setNickname(e.target.value)}
            style={{ width: '100%', margin: '6px 0 12px' }}
          />

          <label className="small muted" htmlFor="memory-species">宠物物种</label>
          <select
            id="memory-species"
            value={species}
            onChange={(e) => setSpecies(e.target.value)}
            style={{ width: '100%', margin: '6px 0 12px' }}
          >
            {SPECIES_OPTIONS.map((item) => (
              <option key={item.value || 'none'} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>

          <div className="row" style={{ gap: 10 }}>
            <div style={{ flex: 1 }}>
              <label className="small muted" htmlFor="memory-budget-min">预算下限（元）</label>
              <input
                id="memory-budget-min"
                value={budgetMinYuan}
                inputMode="decimal"
                placeholder="如 100"
                onChange={(e) => setBudgetMinYuan(e.target.value)}
                style={{ width: '100%', marginTop: 6 }}
              />
            </div>
            <div style={{ flex: 1 }}>
              <label className="small muted" htmlFor="memory-budget-max">预算上限（元）</label>
              <input
                id="memory-budget-max"
                value={budgetMaxYuan}
                inputMode="decimal"
                placeholder="如 300"
                onChange={(e) => setBudgetMaxYuan(e.target.value)}
                style={{ width: '100%', marginTop: 6 }}
              />
            </div>
          </div>
          <div className="small muted" style={{ marginTop: 4 }}>
            留空表示保持原值；清空偏好列表请输入空内容后保存。
          </div>

          <label className="small muted" htmlFor="memory-categories" style={{ display: 'block', marginTop: 12 }}>
            偏好类目（用逗号或顿号分隔）
          </label>
          <input
            id="memory-categories"
            value={categories}
            placeholder="如 主粮、猫砂"
            onChange={(e) => setCategories(e.target.value)}
            style={{ width: '100%', marginTop: 6 }}
          />

          <label className="small muted" htmlFor="memory-brands" style={{ display: 'block', marginTop: 12 }}>
            偏好品牌
          </label>
          <input
            id="memory-brands"
            value={brands}
            placeholder="如 皇家、网易严选"
            onChange={(e) => setBrands(e.target.value)}
            style={{ width: '100%', marginTop: 6 }}
          />

          <button
            type="button"
            className="btn btn-primary"
            disabled={savingLow}
            onClick={() => void saveLowRisk()}
            style={{ marginTop: 14 }}
          >
            {savingLow ? '保存中…' : '保存偏好'}
          </button>
        </div>

        {/* ---------- 高风险 ---------- */}
        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>健康信息（需二次确认）</h3>
          <Alert kind="warn">
            过敏原与健康备注属于高风险记忆：保存前会弹出确认，确认后 AI 才会参考。
          </Alert>

          <label className="small muted" htmlFor="memory-allergens" style={{ display: 'block', marginTop: 10 }}>
            过敏原
          </label>
          <input
            id="memory-allergens"
            value={allergens}
            placeholder="如 鸡肉、牛肉、谷物"
            onChange={(e) => setAllergens(e.target.value)}
            style={{ width: '100%', marginTop: 6 }}
          />

          <label className="small muted" htmlFor="memory-health" style={{ display: 'block', marginTop: 12 }}>
            健康 / 护理备注
          </label>
          <textarea
            id="memory-health"
            value={healthNotes}
            rows={4}
            maxLength={500}
            placeholder="如 肠胃敏感，换粮需 7 天过渡"
            onChange={(e) => setHealthNotes(e.target.value)}
            style={{ width: '100%', marginTop: 6 }}
          />
          <div className="small muted" style={{ marginTop: 4 }}>{healthNotes.length}/500</div>

          <button
            type="button"
            className="btn btn-primary"
            disabled={savingHigh}
            onClick={() => void saveHighRisk()}
            style={{ marginTop: 12 }}
          >
            {savingHigh ? '等待确认…' : '保存健康信息'}
          </button>
        </div>
      </div>

      {/* ---------- 危险操作 ---------- */}
      <div className="card card-pad" style={{ marginTop: 14, borderColor: '#f3c1c4' }}>
        <h3 style={{ marginBottom: 8 }}>清除所有记忆</h3>
        <div className="small muted" style={{ marginBottom: 10 }}>
          删除后「多多」会忘记 {speciesLabel ? `你的${speciesLabel}` : '你的宠物'}、
          预算与健康信息。此操作不可撤销。
        </div>
        <button
          type="button"
          className="btn btn-danger"
          disabled={deleting}
          onClick={() => void clearAll()}
        >
          {deleting ? '清除中…' : '清除所有记忆'}
        </button>
      </div>

      <div style={{ marginTop: 14 }}>
        <Link className="btn btn-ghost btn-sm" to="/ai">← 回到 AI 管家</Link>
      </div>
    </div>
  );
};
