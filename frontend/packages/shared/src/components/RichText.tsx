/**
 * 富文本编辑器与渲染（2026-10-24，商品管理改造）。
 *
 * <h2>为什么自己写而不是引第三方编辑器</h2>
 * 本项目前端零富文本依赖（tiptap/quill 均未安装），而需求只有"商品详情的
 * 基础排版"：加粗/斜体/标题/列表/图片/链接/清除格式。为这点功能引入一个
 * 数百 KB 的编辑器框架，性价比不高；contentEditable + execCommand 虽然
 * 是"过时 API"，但在 Chromium/WebKit/Firefox 上行为稳定，且能直接输出 HTML。
 *
 * <h2>安全（重要）</h2>
 * 端侧渲染前用 DOMParser 做一次白名单清洗（{@link sanitizeRichHtml}），
 * 但**真正的防线在服务端**（jsoup 白名单）：直接调接口可以绕过任何前端清洗。
 * 两层都做，是因为端侧清洗能保证「历史脏数据/第三方注入」在本机也不会执行脚本。
 */

import React, { useCallback, useEffect, useRef } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { uploadApi } from '@duoduo/api-sdk/endpoints';
import { useToast } from './ui';

const ALLOWED_TAGS = new Set([
  'P', 'BR', 'STRONG', 'B', 'EM', 'I', 'U', 'S', 'SPAN', 'DIV',
  'H1', 'H2', 'H3', 'H4', 'UL', 'OL', 'LI', 'BLOCKQUOTE', 'PRE', 'CODE',
  'A', 'IMG', 'FIGURE', 'FIGCAPTION', 'HR',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD',
]);
const ALLOWED_ATTR: Record<string, string[]> = {
  A: ['href', 'target', 'rel'],
  IMG: ['src', 'alt', 'width', 'height'],
};

const safeUrl = (raw: string | null): boolean => {
  if (!raw) return false;
  const v = raw.trim().toLowerCase();
  return v.startsWith('http://') || v.startsWith('https://') || v.startsWith('/');
};

/** 端侧白名单清洗（服务端还会再洗一遍；两层都要，防历史脏数据在浏览器执行） */
export function sanitizeRichHtml(html: string | null | undefined): string {
  if (!html) return '';
  try {
    const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html');
    const walk = (node: Element) => {
      for (const child of Array.from(node.children)) {
        if (!ALLOWED_TAGS.has(child.tagName)) {
          // 不在白名单：保留文字，丢掉标签本身
          const text = document.createTextNode(child.textContent || '');
          child.replaceWith(text);
          continue;
        }
        for (const attr of Array.from(child.attributes)) {
          const allowed = ALLOWED_ATTR[child.tagName] || [];
          if (!allowed.includes(attr.name)) {
            child.removeAttribute(attr.name);
            continue;
          }
          if ((attr.name === 'href' || attr.name === 'src') && !safeUrl(attr.value)) {
            child.removeAttribute(attr.name);
          }
        }
        if (child.tagName === 'A') {
          child.setAttribute('rel', 'noopener noreferrer');
          child.setAttribute('target', '_blank');
        }
        walk(child);
      }
    };
    walk(doc.body);
    return doc.body.innerHTML;
  } catch {
    // 解析失败：降级为纯文本（宁可丢格式，也不能把未清洗的串塞进 innerHTML）
    return html.replace(/<[^>]*>/g, '');
  }
}

/** 只读渲染：详情页用 */
export const RichTextView: React.FC<{ html?: string | null; className?: string }> = ({ html, className }) => {
  const safe = sanitizeRichHtml(html);
  if (!safe) return null;
  return <div className={`rich-view${className ? ' ' + className : ''}`} dangerouslySetInnerHTML={{ __html: safe }} />;
};

export interface RichTextEditorProps {
  value: string;
  onChange: (html: string) => void;
  placeholder?: string;
  /** 最少高度（px），默认 220 */
  minHeight?: number;
}

/**
 * 富文本编辑器。
 *
 * <p>受控方式：内部 contentEditable 只在**首次挂载 / 外部值变化且不等于当前 DOM** 时写入 innerHTML，
 * 避免每次输入都重设 DOM（那会把光标顶到开头）。
 */
export const RichTextEditor: React.FC<RichTextEditorProps> = ({ value, onChange, placeholder, minHeight = 220 }) => {
  const ref = useRef<HTMLDivElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const { push } = useToast();
  const lastValue = useRef<string>('');

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (value !== lastValue.current) {
      el.innerHTML = value || '';
      lastValue.current = value;
    }
  }, [value]);

  const emit = useCallback(() => {
    const html = ref.current?.innerHTML || '';
    lastValue.current = html;
    onChange(html);
  }, [onChange]);

  const cmd = (command: string, arg?: string) => {
    ref.current?.focus();
    document.execCommand(command, false, arg);
    emit();
  };

  const insertImage = async (file: File | null) => {
    if (!file) return;
    try {
      const res = await uploadApi.image(file);
      if (res?.url) {
        cmd('insertImage', res.url);
        push('图片已插入', 'success');
      } else {
        push('上传未返回图片地址', 'error');
      }
    } catch (err) {
      push('图片上传失败：' + toFriendlyMessage(err), 'error');
    }
  };

  return (
    <div className="rich-editor">
      <div className="rich-toolbar" role="toolbar" aria-label="富文本工具栏">
        <button type="button" onClick={() => cmd('bold')} title="加粗"><b>B</b></button>
        <button type="button" onClick={() => cmd('italic')} title="斜体"><i>I</i></button>
        <button type="button" onClick={() => cmd('underline')} title="下划线"><u>U</u></button>
        <span className="rich-sep" />
        <button type="button" onClick={() => cmd('formatBlock', 'h2')} title="标题">H2</button>
        <button type="button" onClick={() => cmd('formatBlock', 'h3')} title="小标题">H3</button>
        <button type="button" onClick={() => cmd('formatBlock', 'p')} title="正文">正文</button>
        <span className="rich-sep" />
        <button type="button" onClick={() => cmd('insertUnorderedList')} title="无序列表">• 列表</button>
        <button type="button" onClick={() => cmd('insertOrderedList')} title="有序列表">1. 列表</button>
        <button type="button" onClick={() => cmd('formatBlock', 'blockquote')} title="引用">引用</button>
        <span className="rich-sep" />
        <button type="button" onClick={() => fileRef.current?.click()} title="插入图片">插图</button>
        <button type="button" onClick={() => cmd('removeFormat')} title="清除格式">清格式</button>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          onChange={(e) => {
            void insertImage(e.target.files?.[0] ?? null);
            e.target.value = '';
          }}
        />
      </div>
      <div
        ref={ref}
        className="rich-body"
        contentEditable
        role="textbox"
        aria-multiline="true"
        data-placeholder={placeholder || '在这里编辑商品详情，可插入图片、标题与列表…'}
        style={{ minHeight }}
        onInput={emit}
        onBlur={emit}
        suppressContentEditableWarning
      />
      <div className="rich-hint">支持加粗、标题、列表、图片；粘贴的 HTML 会按白名单保留格式。</div>
    </div>
  );
};

export interface KvItem {
  name: string;
  value: string;
}

/** 键值对编辑器（规格参数 / 包装规格共用） */
export const KeyValueEditor: React.FC<{
  value: KvItem[];
  onChange: (next: KvItem[]) => void;
  namePlaceholder?: string;
  valuePlaceholder?: string;
  max?: number;
}> = ({ value, onChange, namePlaceholder = '参数名（如 净含量）', valuePlaceholder = '参数值（如 2kg）', max = 20 }) => {
  const rows = value.length ? value : [{ name: '', value: '' }];
  const update = (idx: number, patch: Partial<KvItem>) => {
    const next = rows.map((row, i) => (i === idx ? { ...row, ...patch } : row));
    onChange(next);
  };
  return (
    <div className="kv-editor">
      {rows.map((row, idx) => (
        <div className="kv-row" key={idx}>
          <input
            className="input"
            value={row.name}
            placeholder={namePlaceholder}
            maxLength={30}
            onChange={(e) => update(idx, { name: e.target.value })}
          />
          <input
            className="input"
            value={row.value}
            placeholder={valuePlaceholder}
            maxLength={200}
            onChange={(e) => update(idx, { value: e.target.value })}
          />
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            onClick={() => onChange(rows.filter((_, i) => i !== idx))}
            aria-label="删除该行"
          >
            ✕
          </button>
        </div>
      ))}
      <div className="row" style={{ gap: 8 }}>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          disabled={rows.length >= max}
          onClick={() => onChange([...rows, { name: '', value: '' }])}
        >
          + 添加一行
        </button>
        <span className="small muted">最多 {max} 条</span>
      </div>
    </div>
  );
};
