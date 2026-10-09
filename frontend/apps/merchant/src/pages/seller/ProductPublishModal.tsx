/**
 * 商家端 · 发布商品（E14）。
 *
 * 这里是「商家提交 → 平台审核 → 上架」链路的入口。三个必须让卖家看见的事实：
 *
 *  1. **提交后不会立刻出现在前台** —— 商品进入「待审核」，
 *     审核通过的那一刻自动上架（不需要再点一次上架）；
 *  2. **价格与库存在规格（SKU）上**，一个商品可以有多个规格；
 *     表单填的是「元」，提交前换算成「分」（后端按分存储）——
 *     忘换算会让价格差 100 倍，而且不报错；
 *  3. **主图必填**：它是列表页与搜索结果里唯一的视觉锚点。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { catalogApi, sellerApi } from '@duoduo/api-sdk/endpoints';
import type { BrandView, CategoryNode, SellerSkuInput } from '@duoduo/api-sdk/types';
import { Field, Modal, useToast } from '@duoduo/shared/components/ui';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 提交成功后的回调（列表刷新由调用方负责） */
  onSuccess: () => void;
}

interface SkuDraft {
  specName: string;
  /** 元（字符串，输入框原样）—— 提交前才换算成分 */
  price: string;
  stock: string;
}

const emptySku = (): SkuDraft => ({ specName: '', price: '', stock: '0' });

/** 两级类目树展平成下拉选项，用缩进表达层级（value 是雪花 id 字符串，禁止转数字） */
function flatCategories(nodes: CategoryNode[], depth = 0): { id: string; label: string }[] {
  return nodes.flatMap((node) => [
    { id: node.id, label: `${'　'.repeat(depth)}${depth > 0 ? '└ ' : ''}${node.name}` },
    ...flatCategories(node.children ?? [], depth + 1),
  ]);
}

export const ProductPublishModal: React.FC<Props> = ({ open, onClose, onSuccess }) => {
  const { push } = useToast();
  const [catalog, setCatalog] = useState<{ id: string; label: string }[]>([]);
  const [brands, setBrands] = useState<BrandView[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const [title, setTitle] = useState('');
  const [subtitle, setSubtitle] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [brandId, setBrandId] = useState('');
  const [mainImage, setMainImage] = useState('');
  const [imagesText, setImagesText] = useState('');
  const [detail, setDetail] = useState('');
  const [skus, setSkus] = useState<SkuDraft[]>([emptySku()]);

  // 类目 / 品牌是超小字典表，弹窗打开时取一次；失败不阻塞表单（类目会在提交时被服务端拦）
  const loadCatalog = useCallback(async () => {
    try {
      const [tree, brandList] = await Promise.all([catalogApi.categories(), catalogApi.brands()]);
      setCatalog(flatCategories(tree));
      setBrands(brandList);
    } catch {
      push('类目 / 品牌加载失败，请稍后重试', 'error');
    }
  }, [push]);

  useEffect(() => {
    if (open) {
      loadCatalog();
    }
  }, [open, loadCatalog]);

  const reset = () => {
    setTitle('');
    setSubtitle('');
    setCategoryId('');
    setBrandId('');
    setMainImage('');
    setImagesText('');
    setDetail('');
    setSkus([emptySku()]);
  };

  const close = () => {
    reset();
    onClose();
  };

  const patchSku = (index: number, patch: Partial<SkuDraft>) => {
    setSkus((prev) => prev.map((sku, i) => (i === index ? { ...sku, ...patch } : sku)));
  };

  const submit = async () => {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      push('请填写商品标题', 'error');
      return;
    }
    if (!categoryId) {
      push('请选择商品类目', 'error');
      return;
    }
    if (!mainImage.trim()) {
      push('请填写商品主图地址', 'error');
      return;
    }

    // 价格换算：表单是元，接口是分。用 Math.round 避免 19.9 * 100 = 1989.9999… 这类浮点误差
    const payloadSkus: SellerSkuInput[] = [];
    for (const sku of skus) {
      const yuan = Number(sku.price);
      if (!Number.isFinite(yuan) || yuan <= 0) {
        push('每个规格都要填写大于 0 的价格', 'error');
        return;
      }
      const cents = Math.round(yuan * 100);
      if (cents > 9_999_900) {
        push('价格超出上限（单个规格最高 99999 元）', 'error');
        return;
      }
      const stockValue = sku.stock.trim() === '' ? 0 : Number(sku.stock);
      if (!Number.isInteger(stockValue) || stockValue < 0) {
        push('库存必须是不小于 0 的整数', 'error');
        return;
      }
      payloadSkus.push({
        specName: sku.specName.trim() || null,
        price: cents,
        stock: stockValue,
        image: null,
      });
    }

    const images = imagesText
      .split(/[\n,，]/)
      .map((line) => line.trim())
      .filter(Boolean);

    setSubmitting(true);
    try {
      await sellerApi.createProduct({
        title: trimmedTitle,
        subtitle: subtitle.trim() || null,
        categoryId,
        brandId: brandId || null,
        mainImage: mainImage.trim(),
        images: images.length ? images : null,
        detail: detail.trim() || null,
        skus: payloadSkus,
      });
      push('商品已提交，正在等待平台审核（审核通过后自动上架）', 'success');
      reset();
      onSuccess();
      onClose();
    } catch (err) {
      push(err instanceof Error ? err.message : '提交失败，请重试', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="发布商品"
      onClose={close}
      footer={
        <>
          <button className="btn btn-ghost" onClick={close}>
            取消
          </button>
          <button className="btn btn-primary" disabled={submitting} onClick={submit}>
            {submitting ? '提交中…' : '提交审核'}
          </button>
        </>
      }
    >
      <div style={{ maxHeight: '60vh', overflowY: 'auto', paddingRight: 4 }}>
        <Field label="商品标题（必填）">
          <input
            className="input"
            value={title}
            maxLength={60}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="如：麦富迪 冻干双拼全价猫粮 2kg"
          />
        </Field>

        <Field label="副标题">
          <input
            className="input"
            value={subtitle}
            maxLength={60}
            onChange={(e) => setSubtitle(e.target.value)}
            placeholder="选填，一句话卖点"
          />
        </Field>

        <div className="row" style={{ gap: 12 }}>
          <div style={{ flex: 1 }}>
            <Field label="商品类目（必填）">
              <select className="input" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                <option value="">请选择</option>
                {catalog.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div style={{ flex: 1 }}>
            <Field label="品牌">
              <select className="input" value={brandId} onChange={(e) => setBrandId(e.target.value)}>
                <option value="">不指定</option>
                {brands.map((brand) => (
                  <option key={brand.id} value={brand.id}>
                    {brand.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </div>

        <Field label="商品主图（必填）">
          <input
            className="input"
            value={mainImage}
            maxLength={500}
            onChange={(e) => setMainImage(e.target.value)}
            placeholder="图片地址，如 /img/products/xxx.svg"
          />
        </Field>

        <Field label="详情图（每行一个地址，最多 9 张）">
          <textarea
            className="input"
            rows={2}
            value={imagesText}
            onChange={(e) => setImagesText(e.target.value)}
            placeholder={'/img/products/a.svg\n/img/products/b.svg'}
          />
        </Field>

        <Field label="商品详情">
          <textarea
            className="input"
            rows={4}
            maxLength={4000}
            value={detail}
            onChange={(e) => setDetail(e.target.value)}
            placeholder="材质、成分、适用宠物、喂养建议…"
          />
        </Field>

        <div style={{ marginTop: 4, marginBottom: 6 }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <strong>规格与库存</strong>
            <button
              className="btn btn-ghost btn-sm"
              disabled={skus.length >= 20}
              onClick={() => setSkus((prev) => [...prev, emptySku()])}
            >
              + 添加规格
            </button>
          </div>
          <p className="small muted" style={{ marginTop: 4 }}>
            价格与库存填在规格上（一个商品可以有多个规格，如 2kg / 5kg）。
            价格单位是元；提交后价格与库存不能在此修改。
          </p>
        </div>

        {skus.map((sku, index) => (
          <div key={index} className="row" style={{ gap: 8, alignItems: 'flex-end', marginBottom: 8 }}>
            <div style={{ flex: 1 }}>
              <Field label={index === 0 ? '规格名' : ''}>
                <input
                  className="input"
                  value={sku.specName}
                  maxLength={30}
                  onChange={(e) => patchSku(index, { specName: e.target.value })}
                  placeholder="如 2kg（可留空）"
                />
              </Field>
            </div>
            <div style={{ width: 130 }}>
              <Field label={index === 0 ? '价格（元）' : ''}>
                <input
                  className="input"
                  value={sku.price}
                  onChange={(e) => patchSku(index, { price: e.target.value })}
                  placeholder="0.00"
                />
              </Field>
            </div>
            <div style={{ width: 110 }}>
              <Field label={index === 0 ? '库存' : ''}>
                <input
                  className="input"
                  value={sku.stock}
                  onChange={(e) => patchSku(index, { stock: e.target.value })}
                  placeholder="0"
                />
              </Field>
            </div>
            <button
              className="btn btn-ghost btn-sm"
              style={{ marginBottom: 6 }}
              disabled={skus.length <= 1}
              onClick={() => setSkus((prev) => prev.filter((_, i) => i !== index))}
            >
              删除
            </button>
          </div>
        ))}

        <p className="small muted" style={{ marginTop: 8 }}>
          提交后商品进入<b>待审核</b>，审核期间前台不可见；审核通过后自动上架。
          标题、详情等内容请勿包含平台禁售或违规内容（会在提交时被直接拦截）。
        </p>
      </div>
    </Modal>
  );
};
