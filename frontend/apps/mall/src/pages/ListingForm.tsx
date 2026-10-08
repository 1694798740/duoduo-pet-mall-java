/**
 * 闲置发布 / 编辑表单（Web 端 · 第六十六轮 A 方案补齐）。
 *
 * <p>发布与编辑共用同一张表单：后端两个端点的请求体完全相同
 * （`ListingPublishRequest`），差异只在「提交到哪个端点」与「是否先回填」。
 * 分成两个组件会让校验规则、字段顺序、提示文案各写一遍，然后慢慢漂移。
 *
 * <p>三条与后端契约对齐的要点：
 * <ol>
 *   <li><b>价格在前端是「元」、上行必须是「分」</b>。后端 `requirePrice` 直接按分校验
 *       （上限 999,990 分 = 99,999 元），把「元」当「分」发上去会让价格差 100 倍。</li>
 *   <li><b>编辑后审核态会回到 PENDING</b>：审核结论只对「当时那段内容」有效，
 *       否则就成了「先过审、再改成违规内容」。页面上必须提前告知。</li>
 *   <li><b>成色 / 物种由后端字典驱动</b>（`/api/meta/dictionaries`）：前端不硬编码枚举，
 *       否则后端加一个取值，前端就会静默丢选项。</li>
 * </ol>
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { catalogApi, listingApi, uploadApi } from '@duoduo/api-sdk/endpoints';
import type { DictItem, ListingPublishRequest } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Field, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';

const MAX_IMAGES = 9;
const MAX_TITLE = 200;
const MAX_DESC = 2000;
const MAX_PRICE_YUAN = 99999;

const yuanToFen = (v: string): number => Math.round(Number(v) * 100);
const fenToYuan = (fen: number | null | undefined): string =>
  fen === null || fen === undefined ? '' : String(fen / 100);

export const ListingFormPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const { push } = useToast();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priceYuan, setPriceYuan] = useState('');
  const [originalYuan, setOriginalYuan] = useState('');
  const [condition, setCondition] = useState('');
  const [species, setSpecies] = useState('');
  const [breed, setBreed] = useState('');
  const [city, setCity] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);

  const dicts = useAsync(useCallback(() => catalogApi.dictionaries(), []), true, []);
  const detail = useAsync(
    useCallback(() => listingApi.mineDetail(id as string), [id]),
    editing,
    [editing, id],
  );

  // 回填只在拿到数据的那一刻做一次；之后用户的编辑不能被覆盖
  useEffect(() => {
    const d = detail.data;
    if (!d) return;
    setTitle(d.title);
    setDescription(d.description ?? '');
    setPriceYuan(fenToYuan(d.price));
    setOriginalYuan(fenToYuan(d.originalPrice));
    setCondition(d.conditionLevel ?? '');
    setSpecies(d.species ?? '');
    setBreed(d.breed ?? '');
    setCity(d.city ?? '');
    setImages(d.images ?? []);
  }, [detail.data]);

  const conditionOptions: DictItem[] = useMemo(
    () => dicts.data?.conditionLevel ?? [],
    [dicts.data],
  );
  const speciesOptions: DictItem[] = useMemo(() => dicts.data?.species ?? [], [dicts.data]);

  const onPickImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const room = MAX_IMAGES - images.length;
    if (room <= 0) {
      push(`最多上传 ${MAX_IMAGES} 张图片`, 'error');
      return;
    }
    setUploading(true);
    try {
      const picked = Array.from(files).slice(0, room);
      const uploaded: string[] = [];
      for (const f of picked) {
        const res = await uploadApi.image(f);
        if (res?.url) uploaded.push(res.url);
      }
      setImages((prev) => [...prev, ...uploaded]);
      push(`已上传 ${uploaded.length} 张`, 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setUploading(false);
    }
  };

  const validate = (): string | null => {
    const t = title.trim();
    if (!t) return '请填写标题';
    if (t.length > MAX_TITLE) return `标题不能超过 ${MAX_TITLE} 字`;
    if (description.length > MAX_DESC) return `描述不能超过 ${MAX_DESC} 字`;
    const cents = yuanToFen(priceYuan);
    if (!priceYuan || Number.isNaN(cents) || cents <= 0) return '请填写有效的价格';
    if (cents > MAX_PRICE_YUAN * 100) return `价格不能超过 ${MAX_PRICE_YUAN} 元`;
    if (originalYuan) {
      const o = yuanToFen(originalYuan);
      if (Number.isNaN(o) || o < 0) return '原价不合法';
    }
    return null;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const invalid = validate();
    if (invalid) {
      setError(invalid);
      return;
    }
    setError(null);
    setBusy(true);

    /**
     * 空串一律转 `null` 而不是略过：
     * 后端把「不传」当「不改」，把 `""` 当「清空」。用户确实删掉了描述时，
     * 我们希望它被清空 —— 传 `""` 或 `null` 都表达这个意思，而 `undefined` 表达的是「别动」。
     */
    const body: ListingPublishRequest = {
      title: title.trim(),
      description: description.trim() || null,
      price: yuanToFen(priceYuan),
      originalPrice: originalYuan ? yuanToFen(originalYuan) : null,
      conditionLevel: condition || null,
      species: species || null,
      breed: breed.trim() || null,
      city: city.trim() || null,
      images,
    };

    try {
      if (editing) {
        await listingApi.edit(id as string, body);
        push('已保存，内容变更后需要重新审核', 'success');
      } else {
        await listingApi.publish(body);
        push('发布成功，审核通过后自动上架', 'success');
      }
      navigate('/listings/mine');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (editing && detail.loading && !detail.data) {
    return <LoadingBlock text="正在读取这件闲置…" />;
  }

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">{editing ? '编辑闲置' : '发布闲置'}</h2>
        <span className="section-sub">
          {editing ? '保存后内容变更，会重新进入审核' : '需要先完成实名认证；发布的是个人闲置，不需要卖家身份'}
        </span>
      </div>

      {detail.error ? <Alert kind="error">{detail.error}</Alert> : null}

      <form className="card card-pad" onSubmit={submit} style={{ maxWidth: 720 }}>
        {error ? (
          <div style={{ marginBottom: 14 }}>
            <Alert kind="error">{error}</Alert>
          </div>
        ) : null}

        <Field label="标题" required hint={`最多 ${MAX_TITLE} 字。避免「绝对」「最」这类广告法禁用语`}>
          <input
            className="input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="例如：闲置猫爬架 九成新 自提"
            maxLength={MAX_TITLE}
          />
        </Field>

        <Field label="描述" hint={`最多 ${MAX_DESC} 字，写清成色瑕疵与交易方式`}>
          <textarea
            className="input"
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={MAX_DESC}
            placeholder="用了多久、有无磕碰、能不能包邮…"
          />
        </Field>

        <div className="row" style={{ gap: 12 }}>
          <div style={{ flex: 1 }}>
            <Field label="价格（元）" required hint="二手价，上限 99999 元">
              <input
                className="input"
                value={priceYuan}
                onChange={(e) => setPriceYuan(e.target.value.replace(/[^\d.]/g, ''))}
                placeholder="0.00"
                inputMode="decimal"
              />
            </Field>
          </div>
          <div style={{ flex: 1 }}>
            <Field label="原价（元）" hint="选填，用于显示划线价">
              <input
                className="input"
                value={originalYuan}
                onChange={(e) => setOriginalYuan(e.target.value.replace(/[^\d.]/g, ''))}
                placeholder="0.00"
                inputMode="decimal"
              />
            </Field>
          </div>
        </div>

        <div className="row" style={{ gap: 12 }}>
          <div style={{ flex: 1 }}>
            <Field label="成色">
              <select className="input" value={condition} onChange={(e) => setCondition(e.target.value)}>
                <option value="">不指定（服务端按「良好可用」处理）</option>
                {conditionOptions.map((o) => (
                  <option key={o.code} value={o.code}>{o.label}</option>
                ))}
              </select>
            </Field>
          </div>
          <div style={{ flex: 1 }}>
            <Field label="适用宠物">
              <select className="input" value={species} onChange={(e) => setSpecies(e.target.value)}>
                <option value="">不指定</option>
                {speciesOptions.map((o) => (
                  <option key={o.code} value={o.code}>{o.label}</option>
                ))}
              </select>
            </Field>
          </div>
        </div>

        <div className="row" style={{ gap: 12 }}>
          <div style={{ flex: 1 }}>
            <Field label="品种">
              <input className="input" value={breed} onChange={(e) => setBreed(e.target.value)} placeholder="选填" />
            </Field>
          </div>
          <div style={{ flex: 1 }}>
            <Field label="所在城市">
              <input className="input" value={city} onChange={(e) => setCity(e.target.value)} placeholder="选填，便于同城自提" />
            </Field>
          </div>
        </div>

        <Field label={`图片（${images.length}/${MAX_IMAGES}）`} hint="支持 jpg / png / webp / gif，第一张作为封面">
          <div className="row row-wrap" style={{ gap: 10 }}>
            {images.map((src, i) => (
              <div key={src} style={{ position: 'relative', width: 84, height: 84 }}>
                <img
                  src={src}
                  alt=""
                  style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 10 }}
                />
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  style={{ position: 'absolute', top: -6, right: -6, padding: '0 6px', lineHeight: '20px' }}
                  onClick={() => setImages((prev) => prev.filter((_, idx) => idx !== i))}
                  aria-label={`删除第 ${i + 1} 张`}
                >
                  ×
                </button>
              </div>
            ))}

            {images.length < MAX_IMAGES ? (
              <label
                className="btn btn-ghost btn-sm"
                style={{ width: 84, height: 84, display: 'grid', placeItems: 'center', cursor: 'pointer' }}
              >
                {uploading ? '上传中…' : '+ 添加'}
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif"
                  multiple
                  style={{ display: 'none' }}
                  onChange={(e) => {
                    void onPickImages(e.target.files);
                    e.target.value = '';
                  }}
                />
              </label>
            ) : null}
          </div>
        </Field>

        <div className="row" style={{ gap: 10, marginTop: 6 }}>
          <button className="btn btn-primary" type="submit" disabled={busy || uploading}>
            {busy ? '提交中…' : editing ? '保存修改' : '发布'}
          </button>
          <button className="btn btn-ghost" type="button" onClick={() => navigate('/listings/mine')}>
            取消
          </button>
        </div>

        <div className="divider" />
        <div className="small muted">
          {editing
            ? '保存后审核状态会回到「审核中」：审核结论是针对当时那段内容作出的，内容变了结论就失效。'
            : '提交后会进入人工审核，通过后自动上架。审核期间只有你自己能看到这件闲置。'}
        </div>
      </form>
    </div>
  );
};
