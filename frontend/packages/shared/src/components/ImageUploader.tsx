/**
 * 统一图片上传组件（2026-10-19）。
 *
 * <h2>为什么要有它</h2>
 * 站内原本有两套图片输入方式：
 * <ul>
 *   <li>评价晒图：本地文件 + 外部地址，两条路都落到对象存储（最完整）；</li>
 *   <li>头像 / 售后举证 / 品牌 logo / 商家商品图：**只有一个「图片地址」输入框**，
 *       运营和用户必须自己先把图传到别处拿到直链 —— 这是普通用户根本做不到的事。</li>
 * </ul>
 * 本组件把评价页那套行为抽出来，供所有图片字段复用：
 * **缩略图列表 + 本地选择 + 地址转存 + 删除 + 失败点名**，避免每个页面各写一套。
 *
 * <h2>两条输入路径的落地口径</h2>
 * <ul>
 *   <li><b>本地文件</b> → {@code POST /api/uploads}（服务端落本地盘或 COS），返回本站绝对 URL；</li>
 *   <li><b>外部地址</b> → {@code POST /api/uploads/from-url}（服务端下载并转存），
 *       返回本站 URL。**不直接使用外链**：外站图会失效、防盗链、混合内容；</li>
 *   <li><b>以 {@code /} 开头的本站相对路径</b> 原样采纳，不转存 ——
 *       商家端历史数据里有 {@code /img/products/xxx.svg} 这类站内地址，
 *       强行转存会失败（它们不是 http 直链）。</li>
 * </ul>
 *
 * <h2>与后端校验的关系</h2>
 * 评价 / 售后落库前，服务端 {@code ImageUrls.validate} 只接受 http/https 直链；
 * 本组件两条路径返回的都是**本站绝对 URL**，因此天然满足。
 * 唯一不满足的是「本站相对路径」分支 —— 它只用于商品图这类不走该校验的字段。
 */

import React, { useRef, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { uploadApi } from '@duoduo/api-sdk/endpoints';
import { useToast } from './ui';

export interface ImageUploaderProps {
  /** 当前图片地址列表（受控） */
  value: string[];
  onChange: (next: string[]) => void;
  /** 张数上限，默认 9；头像 / 单图封面传 1 */
  max?: number;
  /** 缩略图边长（px），默认 72 */
  size?: number;
  /** 圆形预览（头像）；默认方形圆角 */
  shape?: 'square' | 'circle';
  /** 是否展示「粘贴地址」这一行，默认 true */
  allowUrl?: boolean;
  disabled?: boolean;
  /** 上传/转存进行中回调 —— 父级用它禁用「提交」按钮 */
  onBusyChange?: (busy: boolean) => void;
  /** 底部说明文案；传空字符串可隐藏 */
  hint?: string;
  /** 添加按钮文案（不传则只显示 +） */
  addLabel?: string;
  /** 外部地址输入框占位文案 */
  urlPlaceholder?: string;
}

const ACCEPT = 'image/png,image/jpeg,image/webp,image/gif';

/** 拆分用户粘贴的多个地址：空格 / 逗号 / 换行都可作分隔符 */
function splitUrls(raw: string): string[] {
  return raw
    .split(/[\s,，]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export const ImageUploader: React.FC<ImageUploaderProps> = ({
  value,
  onChange,
  max = 9,
  size = 72,
  shape = 'square',
  allowUrl = true,
  disabled = false,
  onBusyChange,
  hint = '本地图片与外部地址都会转存到对象存储，页面展示使用转存后的地址。',
  addLabel,
  urlPlaceholder = '或粘贴图片地址（多张用空格 / 逗号分隔）',
}) => {
  const { push } = useToast();
  const [busy, setBusy] = useState(false);
  const [urlInput, setUrlInput] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  /**
   * 异步上传期间用户可能删图/加图，闭包里的 value 会过期。
   * 用 ref 持有「最新列表」，合并时以它为准，避免把刚删掉的图又加回来。
   */
  const valueRef = useRef(value);
  valueRef.current = value;

  const setBusyBoth = (next: boolean) => {
    setBusy(next);
    onBusyChange?.(next);
  };

  const merge = (incoming: string[]) => {
    /**
     * 单图字段（头像 / 封面 / Logo）语义是「换一张」而不是「再加一张」：
     * 若按 max 截断，已有图时选新图会被直接丢掉，用户只能先点 × 删掉才换得了 ——
     * 这一步没人猜得到。所以 max===1 时新图直接替换旧图。
     */
    if (max === 1) {
      const next = incoming.slice(0, 1);
      if (next.length === 0) return;
      onChange(next);
      valueRef.current = next;
      return;
    }
    const next = [...valueRef.current];
    for (const url of incoming) {
      if (next.length >= max) break;
      if (!next.includes(url)) next.push(url);
    }
    onChange(next);
    valueRef.current = next;
  };

  /** 还能收几张：单图字段永远按「可换 1 张」算，否则已有图时连选图都点不动 */
  const room = () => {
    if (max === 1) return 1;
    return max - valueRef.current.length;
  };

  /** 本地文件：逐张上传；失败的不静默，点名文件与原因 */
  const pickFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const left = room();
    if (left <= 0) {
      push(`最多 ${max} 张图片`, 'error');
      return;
    }
    const picked = Array.from(files).slice(0, left);
    setBusyBoth(true);
    const uploaded: string[] = [];
    const failed: string[] = [];
    for (const file of picked) {
      try {
        const res = await uploadApi.image(file);
        if (res?.url) uploaded.push(res.url);
        else failed.push(`${file.name}：未返回图片地址`);
      } catch (err) {
        failed.push(`${file.name}：${toFriendlyMessage(err)}`);
      }
    }
    merge(uploaded);
    setBusyBoth(false);
    if (uploaded.length) push(`已上传 ${uploaded.length} 张图片`, 'success');
    if (failed.length) push(`上传失败 ${failed.length} 张 —— ${failed[0]}`, 'error');
  };

  /** 外部地址：http(s) 转存；以 / 开头的本站路径原样采纳 */
  const importUrls = async () => {
    const urls = splitUrls(urlInput);
    if (urls.length === 0) {
      push('请先填写图片地址', 'info');
      return;
    }
    const left = room();
    if (left <= 0) {
      push(`最多 ${max} 张图片`, 'error');
      return;
    }
    setBusyBoth(true);
    const accepted: string[] = [];
    const failed: string[] = [];
    for (const url of urls.slice(0, left)) {
      if (url.startsWith('/')) {
        // 本站相对路径（历史商品图 /img/... 或上传后的 /uploads/...）：无需转存
        accepted.push(url);
        continue;
      }
      if (!/^https?:\/\//i.test(url)) {
        failed.push(`${url}（只接受 http/https 或以 / 开头的本站地址）`);
        continue;
      }
      try {
        const res = await uploadApi.fromUrl(url);
        if (res?.url) accepted.push(res.url);
        else failed.push(`${url}：未返回图片地址`);
      } catch (err) {
        failed.push(`${url}：${toFriendlyMessage(err)}`);
      }
    }
    merge(accepted);
    if (accepted.length) setUrlInput('');
    setBusyBoth(false);
    if (accepted.length) push(`已添加 ${accepted.length} 张图片`, 'success');
    if (failed.length) push(`添加失败 ${failed.length} 张 —— ${failed[0]}`, 'error');
  };

  const removeAt = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  const locked = disabled || busy;
  const radius = shape === 'circle' ? '50%' : 10;

  return (
    <div className="img-uploader">
      <div className="img-uploader-list">
        {value.map((url, index) => (
          <div key={`${url}-${index}`} className="img-uploader-item" style={{ width: size, height: size }}>
            <img src={url} alt={`已选图片 ${index + 1}`} style={{ borderRadius: radius }} />
            {locked ? null : (
              <button
                type="button"
                className="img-uploader-remove"
                aria-label={`移除第 ${index + 1} 张图片`}
                onClick={() => removeAt(index)}
              >
                ×
              </button>
            )}
          </div>
        ))}

        {max === 1 || value.length < max ? (
          <button
            type="button"
            className="img-uploader-add"
            style={{ width: size, height: size, borderRadius: radius }}
            disabled={locked}
            onClick={() => fileRef.current?.click()}
          >
            {busy ? <span className="img-uploader-busy">…</span> : <span className="img-uploader-plus">+</span>}
            {addLabel ? <span className="img-uploader-add-label">{addLabel}</span> : null}
            <input
              ref={fileRef}
              type="file"
              accept={ACCEPT}
              multiple={max > 1}
              style={{ display: 'none' }}
              onChange={(e) => {
                void pickFiles(e.target.files);
                e.target.value = '';
              }}
            />
          </button>
        ) : null}
      </div>

      {allowUrl ? (
        <div className="img-uploader-url">
          <input
            className="input"
            style={{ flex: 1 }}
            placeholder={urlPlaceholder}
            value={urlInput}
            disabled={locked}
            onChange={(e) => setUrlInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void importUrls();
              }
            }}
          />
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={locked || !urlInput.trim()}
            onClick={() => void importUrls()}
          >
            {busy ? '处理中…' : '添加地址'}
          </button>
        </div>
      ) : null}

      {hint ? <div className="small muted img-uploader-hint">{hint}</div> : null}
    </div>
  );
};
