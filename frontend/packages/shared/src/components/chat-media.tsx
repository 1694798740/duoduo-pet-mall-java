/**
 * 聊天媒体能力（B 批 + 端侧压缩）。
 *
 * <h2>为什么在端侧压缩</h2>
 * 视频消息的约束只有一条：**时长 ≤ 60 秒**。体积不该由服务端卡死 ——
 * 用户手机里的录屏动辄几十 MB，服务端一拒，「压缩失败回退原视频」这条兜底也就断了。
 * 因此在浏览器/小程序里先压到 ~720p、码率适中，再上传；压不了就原样上传。
 *
 * <h2>压缩策略</h2>
 * <ul>
 *   <li>≤ 6MB 直接上传：小文件压缩收益低，没必要让用户等；</li>
 *   <li>&gt; 6MB：canvas 重绘 + MediaRecorder 录制，最长边压到 1280、高不超过 720、30fps、
 *       视频 1.6Mbps / 音频 96kbps。这是「画质不明显下降」与「体积明显变小」的平衡点；</li>
 *   <li>优先产 mp4（Chrome 新版/Safari 支持），否则 webm（服务端已接受）；</li>
 *   <li>压缩失败、浏览器不支持、产物不比原文件小 → **一律回退原视频**；</li>
 *   <li>录制是实时的（60 秒素材约等 60 秒），所以必须有进度提示，不能假装瞬间完成。</li>
 * </ul>
 */
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

/** 端侧硬约束：时长；体积不设限 */
export const VIDEO_MAX_SECONDS = 60;
/** 低于此体积不做压缩（收益小于等待成本） */
const COMPRESS_THRESHOLD = 6 * 1024 * 1024;
/** 目标画质：高不超过 720，宽不超过 1280 */
const TARGET_HEIGHT = 720;
const TARGET_WIDTH = 1280;
const VIDEO_BITRATE = 1_600_000;
const AUDIO_BITRATE = 96_000;

export interface MediaPayload {
  mediaUrl: string;
  thumbUrl?: string;
  width?: number;
  height?: number;
  durationMs?: number;
  /** 是否经过端侧压缩（用于提示与日志） */
  compressed?: boolean;
}

export type UploadPhase = 'compress' | 'upload';
export interface UploadProgress {
  phase: UploadPhase;
  /** 0–100 */
  percent: number;
}

function readVideoMeta(file: File): Promise<{ width: number; height: number; durationMs: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.onloadedmetadata = () => {
      const meta = {
        width: video.videoWidth,
        height: video.videoHeight,
        durationMs: Math.round((video.duration || 0) * 1000),
      };
      URL.revokeObjectURL(url);
      resolve(meta);
    };
    video.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('视频无法读取，请换一个文件'));
    };
    video.src = url;
  });
}

function even(n: number): number {
  const v = Math.max(2, Math.round(n));
  return v % 2 === 0 ? v : v - 1;
}

/**
 * 从视频里抓一帧当封面（2026-10-23）。
 *
 * <h2>为什么要端侧抓帧</h2>
 * 上传接口只回一个 URL，视频消息此前**从来没有封面**：接收方看到的就是一块深色底 + 播放按钮，
 * 分不清是风景、宠物还是误发（截图里那张就是这样）。服务端抓帧要引入 ffmpeg 或
 * 数据万象的视频截帧能力，成本与运维面都大；而端侧本来就为了压缩解码过一遍视频，
 * 多画一帧 canvas 几乎不增加等待。
 *
 * <p>取 0.1s 而不是第 0 帧：不少视频首帧是黑场/淡入，抓出来是一张纯黑图，
 * 比"没有封面"更像 bug。
 *
 * <p>失败一律返回 null（不抛）：封面是锦上添花，不能因为它拦住消息发送。
 */
async function captureVideoPoster(file: File): Promise<Blob | null> {
  const url = URL.createObjectURL(file);
  const video = document.createElement('video');
  video.src = url;
  video.muted = true;
  video.playsInline = true;
  video.preload = 'auto';
  try {
    await new Promise<void>((resolve, reject) => {
      video.onloadeddata = () => resolve();
      video.onerror = () => reject(new Error('视频解码失败'));
      // 某些容器（如损坏的 moov）会一直不触发事件，不能无限等
      setTimeout(() => reject(new Error('视频加载超时')), 8000);
    });
    const target = Math.min(0.1, Math.max(0, (video.duration || 0) / 2));
    if (target > 0) {
      video.currentTime = target;
      await new Promise<void>((resolve) => {
        video.onseeked = () => resolve();
        setTimeout(resolve, 1500); // seek 失败也继续，画的是当前帧
      });
    }
    const vw = video.videoWidth;
    const vh = video.videoHeight;
    if (!vw || !vh) return null;
    // 气泡最多 260px 宽，按 2x 屏取 520 已足够清晰，再大只是浪费流量
    const scale = Math.min(1, 520 / vw);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(2, Math.round(vw * scale));
    canvas.height = Math.max(2, Math.round(vh * scale));
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    return await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.82));
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
    video.removeAttribute('src');
  }
}

/** 选一个当前浏览器真的能录的封装；都不支持返回 null（→ 回退原视频） */
function pickRecorderMime(): string | null {
  if (typeof MediaRecorder === 'undefined') return null;
  const candidates = [
    'video/mp4;codecs=avc1.42E01E',
    'video/mp4',
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ];
  return candidates.find((m) => MediaRecorder.isTypeSupported(m)) ?? null;
}

/**
 * canvas + MediaRecorder 实时重录。
 *
 * <p>返回 null 的语义是「没压成」——调用方据此回退原文件，而不是报错。
 * 之所以不用 ffmpeg.wasm：它本体几十 MB，为了压一个视频先下 30MB，
 * 在弱网场景下与需求目标（更快传上去）正好相反。
 */
async function compressVideo(
  file: File,
  meta: { width: number; height: number; durationMs: number },
  onProgress: (percent: number) => void,
): Promise<{ file: File; width: number; height: number } | null> {
  const mime = pickRecorderMime();
  if (!mime || !meta.width || !meta.height) return null;

  const scale = Math.min(1, TARGET_HEIGHT / meta.height, TARGET_WIDTH / meta.width);
  const width = even(meta.width * scale);
  const height = even(meta.height * scale);

  const url = URL.createObjectURL(file);
  const video = document.createElement('video');
  video.src = url;
  video.muted = true;
  video.playsInline = true;
  video.preload = 'auto';

  try {
    await new Promise<void>((resolve, reject) => {
      video.onloadeddata = () => resolve();
      video.onerror = () => reject(new Error('视频解码失败'));
    });

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    const stream = canvas.captureStream(30);
    // 音轨直接取自 video 元素；没有 captureStream 的浏览器会丢音，直接放弃压缩
    const anyVideo = video as HTMLVideoElement & { captureStream?: () => MediaStream };
    const sourceStream = anyVideo.captureStream?.();
    const audioTracks = sourceStream?.getAudioTracks() ?? [];
    if (audioTracks.length === 0) return null;
    audioTracks.forEach((track) => stream.addTrack(track));

    const recorder = new MediaRecorder(stream, {
      mimeType: mime,
      videoBitsPerSecond: VIDEO_BITRATE,
      audioBitsPerSecond: AUDIO_BITRATE,
    });
    const chunks: BlobPart[] = [];
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data);
    };
    const stopped = new Promise<Blob>((resolve) => {
      recorder.onstop = () => resolve(new Blob(chunks, { type: mime }));
    });

    recorder.start(1000);
    await video.play();
    const duration = video.duration || meta.durationMs / 1000;

    await new Promise<void>((resolve, reject) => {
      let finished = false;
      const finish = (ok: boolean) => {
        if (finished) return;
        finished = true;
        if (ok) resolve();
        else reject(new Error('压缩超时'));
      };
      // 超时护栏：录制是实时的，超过素材时长 + 8 秒仍未结束说明卡住了
      const guard = window.setTimeout(() => finish(false), (duration + 8) * 1000);
      const draw = () => {
        if (video.paused || video.ended) return;
        ctx.drawImage(video, 0, 0, width, height);
        onProgress(Math.min(99, Math.round((video.currentTime / duration) * 100)));
        requestAnimationFrame(draw);
      };
      draw();
      video.onended = () => {
        window.clearTimeout(guard);
        finish(true);
      };
    });

    recorder.stop();
    const blob = await stopped;
    URL.revokeObjectURL(url);
    if (!blob.size) return null;

    const ext = mime.includes('mp4') ? 'mp4' : 'webm';
    const base = file.name.replace(/\.[^.]+$/, '');
    const out = new File([blob], `${base}-compressed.${ext}`, { type: blob.type });
    // 压完反而更大（短视频高压缩率片源常见）→ 不采用
    if (out.size >= file.size * 0.95) return null;
    return { file: out, width, height };
  } catch {
    URL.revokeObjectURL(url);
    return null;
  } finally {
    video.src = '';
  }
}

/** 带进度上报的上传（fetch 没有上传进度，改用 XHR） */
function uploadWithProgress(
  form: FormData,
  onProgress?: (percent: number) => void,
): Promise<{ code?: string; message?: string; data?: { url?: string } }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/uploads');
    xhr.withCredentials = true;
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onerror = () => reject(new Error('上传失败，请检查网络'));
    xhr.onload = () => {
      try {
        resolve(JSON.parse(xhr.responseText || '{}'));
      } catch {
        reject(new Error('上传响应解析失败'));
      }
    };
    xhr.send(form);
  });
}

/** 上传单个文件；返回可直接发消息的媒体信息 */
export async function uploadChatMedia(
  file: File,
  onProgress?: (progress: UploadProgress) => void,
): Promise<MediaPayload> {
  const isVideo = file.type.startsWith('video/');
  let target = file;
  let width: number | undefined;
  let height: number | undefined;
  let durationMs: number | undefined;
  let compressed = false;
  let thumbUrl: string | undefined;

  if (isVideo) {
    if (file.type !== 'video/mp4' && file.type !== 'video/webm') {
      throw new Error('视频仅支持 mp4 / webm 格式');
    }
    const meta = await readVideoMeta(file);
    // 唯一的硬约束：时长。超过 0.5 秒容差直接拒绝，不做静默裁剪
    if (meta.durationMs > VIDEO_MAX_SECONDS * 1000 + 500) {
      throw new Error(`视频不能超过 ${VIDEO_MAX_SECONDS} 秒，请先裁剪后再发送`);
    }
    width = meta.width;
    height = meta.height;
    durationMs = meta.durationMs;

    if (file.size > COMPRESS_THRESHOLD) {
      onProgress?.({ phase: 'compress', percent: 0 });
      const result = await compressVideo(file, meta, (percent) =>
        onProgress?.({ phase: 'compress', percent }),
      );
      if (result) {
        target = result.file;
        width = result.width;
        height = result.height;
        compressed = true;
      }
      // result 为 null = 没压成，静默回退原视频（需求要求的行为）
    }

    // 封面：抓上传文件的一帧（压缩后抓，保证封面与真正上传的画面对得上）
    const poster = await captureVideoPoster(target);
    if (poster) {
      const posterForm = new FormData();
      posterForm.append('file', new File([poster], 'poster.jpg', { type: 'image/jpeg' }));
      try {
        const uploaded = await uploadWithProgress(posterForm);
        if (uploaded.code === 'SUCCESS' && uploaded.data?.url) {
          thumbUrl = uploaded.data.url;
        }
      } catch {
        // 封面失败不影响消息本身：接收端还有「视频首帧」兜底
      }
    }
  } else if (file.size > 5 * 1024 * 1024) {
    throw new Error('图片不能超过 5MB');
  }

  const form = new FormData();
  form.append('file', target);
  onProgress?.({ phase: 'upload', percent: 0 });
  const body = await uploadWithProgress(form, (percent) =>
    onProgress?.({ phase: 'upload', percent }),
  );
  if (body.code !== 'SUCCESS' || !body.data?.url) {
    throw new Error(body.message || '上传失败，请稍后再试');
  }
  return { mediaUrl: body.data.url, thumbUrl, width, height, durationMs, compressed };
}

/** 判断是否纯 emoji（只有 emoji 时气泡放大显示，模拟「表情包」的观感） */
export function isEmojiOnly(text: string): boolean {
  const trimmed = (text || '').trim();
  if (!trimmed) return false;
  if (trimmed.length > 12) return false;
  return /^(\p{Extended_Pictographic}|\uFE0F|\u200D|\s)+$/u.test(trimmed);
}

/** 内置贴纸（商城 public/stickers，商家端共享同一目录） */
const STICKERS = [
  'ok', 'thanks', 'wait', 'cry', 'love', 'angry',
  'hi', 'sorry', 'busy', 'question', 'nice', 'bye',
];

const stickerUrl = (name: string) =>
  `${typeof window !== 'undefined' ? window.location.origin : ''}/stickers/${name}.svg`;

/**
 * 表情面板（2026-10-23 扩容 16 → 56）。
 * 只放「聊天常用」的一组，避免变成全量 emoji 选择器 —— 面板高度有限，
 * 翻页成本比多放几十个冷门表情更高。
 */
const EMOJIS = [
  '😀', '😄', '😁', '😊', '🥰', '😍', '😘', '😜',
  '🤪', '😎', '🤗', '🤔', '😐', '😴', '🥱', '😪',
  '😂', '🤣', '😅', '😇', '🙃', '😉', '😌', '😢',
  '🥹', '😭', '😤', '😡', '🤬', '😱', '😨', '😰',
  '👍', '👎', '👏', '🙏', '🤝', '💪', '✌️', '👌',
  '🎉', '🎁', '🔥', '💯', '❤️', '💔', '⭐', '🌈',
  '🐶', '🐱', '🐟', '🦴', '🐾', '🐹', '🐰', '🐦',
];

/**
 * 输入区「+」菜单（2026-10-22 二级化改造）。
 *
 * <h2>两层结构</h2>
 * 第一层只放四个**入口**：图片 / 视频 / 表情 / 商品（不发直接动作）；
 * 点「表情」进入第二层面板（emoji + 贴纸），点「图片/视频」拉起文件选择，
 * 点「商品」回调 onProduct（客服页弹 ProductPicker，私信页直接发当前咨询商品）。
 *
 * <p>压缩与上传期间按钮上显示实时阶段（压缩 37% / 上传 62%），
 * 用户不会面对一个"点了没反应"的按钮。
 */
export const ChatMediaMenu: React.FC<{
  onMedia: (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => void;
  onEmoji: (emoji: string) => void;
  onSticker: (url: string) => void;
  /** 传入才显示「商品」入口（客服页 = 打开选择器；私信页 = 直发当前咨询商品） */
  onProduct?: () => void;
  onError: (message: string) => void;
  disabled?: boolean;
}> = ({ onMedia, onEmoji, onSticker, onProduct, onError, disabled }) => {
  const [open, setOpen] = useState(false);
  const [panel, setPanel] = useState<'root' | 'emoji'>('root');
  const [busy, setBusy] = useState('');
  /** 弹层的视口锚点（fixed 定位，避免被 .sup-thread 的 overflow:hidden 裁切） */
  const [anchor, setAnchor] = useState<{ left: number; bottom: number; width: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const imageRef = useRef<HTMLInputElement | null>(null);
  const videoRef = useRef<HTMLInputElement | null>(null);

  /**
   * 面板的受控关闭（2026-10-23 补）：
   * 此前只有「再点一次 +」才能收起，Escape 和点击空白处都关不掉，面板会一直浮在页面上，
   * 既挡住下方内容，也会吞掉落在它上面的滚轮事件（灯箱 Ctrl+滚轮缩放曾因此失效）。
   */
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node | null;
      if (!target) return;
      if (wrapRef.current?.contains(target) || panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('pointerdown', onPointerDown);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('pointerdown', onPointerDown);
    };
  }, [open]);

  /** 按当前面板宽度计算锚点：左边界贴住按钮、右边界不越出视口，向上展开 */
  const place = (width: number) => {
    const rect = wrapRef.current?.getBoundingClientRect();
    if (!rect) return null;
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8));
    return { left, bottom: Math.max(8, window.innerHeight - rect.top + 8), width };
  };

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    setAnchor(place(panel === 'emoji' ? 336 : 232));
    setPanel('root');
    setOpen(true);
  };

  const gotoEmoji = () => {
    setPanel('emoji');
    setAnchor(place(336));
  };

  const pick = async (file: File | null, kind: 'IMAGE' | 'VIDEO') => {
    if (!file) return;
    setBusy(kind === 'VIDEO' ? '准备中…' : '上传中…');
    try {
      const payload = await uploadChatMedia(file, ({ phase, percent }) => {
        setBusy(`${phase === 'compress' ? '压缩中' : '上传中'} ${percent}%`);
      });
      onMedia(payload, kind);
      setOpen(false);
    } catch (e) {
      onError(e instanceof Error ? e.message : '上传失败');
    } finally {
      setBusy('');
    }
  };

  const entryBtn = 'btn btn-sm btn-ghost';
  const entryStyle = { justifyContent: 'flex-start', width: '100%' } as const;

  return (
    <div ref={wrapRef} style={{ position: 'relative' }}>
      <button
        type="button"
        className="btn btn-ghost"
        title="发送图片 / 视频 / 表情 / 商品"
        aria-expanded={open}
        onClick={toggle}
        disabled={disabled || Boolean(busy)}
      >
        {busy || '＋'}
      </button>
      {open && !busy && anchor && (
        <div
          ref={panelRef}
          className="card"
          data-testid="chat-media-panel"
          data-panel={panel}
          style={{
            position: 'fixed',
            left: anchor.left,
            bottom: anchor.bottom,
            width: anchor.width,
            maxHeight: 'min(58vh, 420px)',
            overflowY: 'auto',
            overscrollBehavior: 'contain',
            padding: 10,
            zIndex: 1200,
            boxShadow: 'var(--shadow-lg, 0 16px 48px rgba(16,20,32,.18))',
          }}
        >
          {panel === 'root' ? (
            <div className="col" style={{ gap: 4 }}>
              <button type="button" className={entryBtn} style={entryStyle} onClick={() => imageRef.current?.click()}>
                🖼 图片
              </button>
              <button type="button" className={entryBtn} style={entryStyle} onClick={() => videoRef.current?.click()}>
                🎬 视频
              </button>
              <button type="button" className={entryBtn} style={entryStyle} onClick={gotoEmoji}>
                😀 表情
              </button>
              {onProduct && (
                <button
                  type="button"
                  className={entryBtn}
                  style={entryStyle}
                  onClick={() => {
                    setOpen(false);
                    onProduct();
                  }}
                >
                  🛍 商品
                </button>
              )}
              <div className="small muted" style={{ marginTop: 4 }}>
                视频 ≤{VIDEO_MAX_SECONDS} 秒；超过 6MB 会先压缩（画质 720p）
              </div>
            </div>
          ) : (
            <div>
              <div className="row" style={{ alignItems: 'center', gap: 6, marginBottom: 6 }}>
                <button
                  type="button"
                  className="btn btn-sm btn-ghost"
                  onClick={() => {
                    setPanel('root');
                    setAnchor(place(232));
                  }}
                >
                  ← 返回
                </button>
                <span className="small muted">表情与贴纸</span>
              </div>
              <div
                data-testid="emoji-grid"
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(8, minmax(0, 1fr))',
                  gap: 2,
                }}
              >
                {EMOJIS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    className="btn btn-sm btn-ghost"
                    style={{ fontSize: 18, padding: '2px 0', lineHeight: 1.6 }}
                    onClick={() => {
                      onEmoji(emoji);
                      setOpen(false);
                    }}
                  >
                    {emoji}
                  </button>
                ))}
              </div>
              <div className="small muted" style={{ margin: '10px 0 4px' }}>
                贴纸
              </div>
              <div
                data-testid="sticker-grid"
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
                  gap: 6,
                }}
              >
                {STICKERS.map((name) => (
                  <button
                    key={name}
                    type="button"
                    className="btn btn-sm btn-ghost"
                    style={{ padding: 2 }}
                    onClick={() => {
                      onSticker(stickerUrl(name));
                      setOpen(false);
                    }}
                  >
                    <img src={stickerUrl(name)} alt={name} style={{ width: 44, height: 44 }} />
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
      <input
        ref={imageRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        style={{ display: 'none' }}
        onChange={(e) => {
          void pick(e.target.files?.[0] ?? null, 'IMAGE');
          e.target.value = '';
        }}
      />
      <input
        ref={videoRef}
        type="file"
        accept="video/mp4,video/webm"
        style={{ display: 'none' }}
        onChange={(e) => {
          void pick(e.target.files?.[0] ?? null, 'VIDEO');
          e.target.value = '';
        }}
      />
    </div>
  );
};

/** 媒体资源下载：优先 blob 落盘（保留原文件名），CORS 拦截时退回新开标签页 */
async function downloadMedia(url: string, filename: string): Promise<void> {
  try {
    const resp = await fetch(url, { mode: 'cors' });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const blob = await resp.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(objectUrl);
  } catch {
    // 跨域 fetch 被桶策略挡住时不静默失败：新开标签页让用户手动保存
    window.open(url, '_blank', 'noopener');
  }
}

const fmtTime = (seconds: number): string => {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
};

/** 轻量蒙层：只压暗背景，不再套一层白色卡片 —— 媒体本身就是主体 */
const Overlay: React.FC<{ onClose: () => void; children: React.ReactNode }> = ({ onClose, children }) => (
  <div
    role="dialog"
    aria-modal="true"
    onClick={onClose}
    style={{
      position: 'fixed', inset: 0, zIndex: 1000,
      background: 'rgba(6, 8, 12, 0.55)',
      backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 16,
    }}
  >
    <div
      onClick={(e) => e.stopPropagation()}
      style={{
        position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center',
        maxWidth: '100%', maxHeight: '100%',
      }}
    >
      {children}
    </div>
  </div>
);

/**
 * 播放器图标（2026-10-23 补）。
 *
 * 此前用 `▶ ⏸ ⛶ ⤢` 这类字符：Windows 的默认中文字体对 U+26F6 / U+25B6 覆盖不全，
 * 实测会渲染成「空心方块」（tofu），换成内联 SVG 后与字体无关，三端一致。
 */
const svgBase = {
  width: 16,
  height: 16,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
};

/**
 * 暗色浮条上的按钮。
 * `.btn-ghost` 只改边框和文字色、**背景仍是白色**（styles.css:679），直接放在深色播放条上
 * 就是一块白方块，白色图标反而看不见。这里显式清掉底色与边框。
 */
const darkGhost = { color: '#fff', background: 'transparent', borderColor: 'transparent' } as const;

const PlayIcon = () => (
  <svg {...svgBase}><path d="M8 5.5v13l11-6.5z" fill="currentColor" stroke="none" /></svg>
);
const PauseIcon = () => (
  <svg {...svgBase}>
    <rect x="7" y="5" width="3.6" height="14" rx="1" fill="currentColor" stroke="none" />
    <rect x="13.4" y="5" width="3.6" height="14" rx="1" fill="currentColor" stroke="none" />
  </svg>
);
const VolumeOnIcon = () => (
  <svg {...svgBase}>
    <path d="M4 9.5h3l4-3.2v11.4l-4-3.2H4z" fill="currentColor" />
    <path d="M15.5 9a4.2 4.2 0 0 1 0 6M18 6.5a7.7 7.7 0 0 1 0 11" />
  </svg>
);
const VolumeOffIcon = () => (
  <svg {...svgBase}>
    <path d="M4 9.5h3l4-3.2v11.4l-4-3.2H4z" fill="currentColor" />
    <path d="M16 9.5l4.5 5M20.5 9.5L16 14.5" />
  </svg>
);
const FullscreenIcon = () => (
  <svg {...svgBase}><path d="M4 9V4h5M20 15v5h-5M15 4h5v5M9 20H4v-5" /></svg>
);
const ExitFullscreenIcon = () => (
  <svg {...svgBase}><path d="M9 4v5H4M15 20v-5h5M20 9h-5V4M4 15h5v5" /></svg>
);
const DownloadIcon = () => (
  <svg {...svgBase}><path d="M12 4v10M8 10l4 4 4-4M5 19h14" /></svg>
);

/** 浮在蒙层上的关闭按钮（不占媒体区域） */
const CloseButton: React.FC<{ onClose: () => void }> = ({ onClose }) => (
  <button
    type="button"
    aria-label="关闭"
    onClick={onClose}
    style={{
      position: 'fixed', top: 16, right: 16, width: 36, height: 36, borderRadius: '50%',
      border: 'none', cursor: 'pointer', zIndex: 1010,
      background: 'rgba(255,255,255,0.16)', color: '#fff', lineHeight: 1,
      display: 'grid', placeItems: 'center',
      backdropFilter: 'blur(6px)',
    }}
  >
    <svg {...svgBase} width={18} height={18}><path d="M6 6l12 12M18 6L6 18" /></svg>
  </button>
);

/**
 * 图片灯箱（2026-10-23 改造）：
 *  · **Ctrl + 滚轮缩放**（0.3x ~ 6x），缩放后按住拖动可平移，双击复位；
 *  · 不再套白色容器：图片直接铺在轻量蒙层上，只有一圈阴影把它与背景分开；
 *  · 缩放比例 / 下载 / 关闭都是浮动控件，不占据媒体区域。
 */
const ImageLightbox: React.FC<{ url: string; onClose: () => void }> = ({ url, onClose }) => {
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const dragRef = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // 原生监听 + passive:false：Ctrl+滚轮要阻止浏览器整页缩放，React 的合成事件做不到
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return undefined;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      setScale((prev) => {
        const next = Math.min(6, Math.max(0.3, prev * (1 - e.deltaY * 0.0015)));
        const rounded = Math.round(next * 1000) / 1000;
        if (rounded <= 1) setOffset({ x: 0, y: 0 });
        return rounded;
      });
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  const reset = () => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  };

  const filename = url.split('/').pop() || 'image';
  return (
    <Overlay onClose={onClose}>
      <div
        ref={wrapRef}
        onDoubleClick={reset}
        onPointerDown={(e) => {
          if (scale <= 1) return;
          dragRef.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y };
          setDragging(true);
          e.currentTarget.setPointerCapture?.(e.pointerId);
        }}
        onPointerMove={(e) => {
          const d = dragRef.current;
          if (!d) return;
          setOffset({ x: d.ox + (e.clientX - d.x), y: d.oy + (e.clientY - d.y) });
        }}
        onPointerUp={() => {
          dragRef.current = null;
          setDragging(false);
        }}
        style={{ display: 'grid', placeItems: 'center', maxWidth: '94vw', maxHeight: '90vh', cursor: scale > 1 ? (dragging ? 'grabbing' : 'grab') : 'default' }}
      >
        <div ref={stageRef} style={{ overflow: 'hidden', maxWidth: '94vw', maxHeight: '88vh' }}>
          <img
            src={url}
            alt=""
            draggable={false}
            style={{
              display: 'block', maxWidth: '94vw', maxHeight: '88vh', objectFit: 'contain',
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
              transition: dragging ? 'none' : 'transform 0.12s ease',
              boxShadow: '0 12px 48px rgba(0,0,0,0.45)',
              borderRadius: 4,
              userSelect: 'none',
            }}
          />
        </div>
      </div>

      <div
        style={{
          position: 'fixed', left: '50%', bottom: 20, transform: 'translateX(-50%)',
          display: 'flex', alignItems: 'center', gap: 10, padding: '6px 12px',
          borderRadius: 999, background: 'rgba(0,0,0,0.55)', color: '#fff',
          backdropFilter: 'blur(8px)', zIndex: 1010, fontSize: 12,
        }}
      >
        <button type="button" className="btn btn-sm btn-ghost" style={darkGhost}
          onClick={() => setScale((v) => Math.max(0.3, Math.round((v - 0.2) * 100) / 100))}>－</button>
        <span style={{ minWidth: 46, textAlign: 'center', fontVariantNumeric: 'tabular-nums' }}>
          {Math.round(scale * 100)}%
        </span>
        <button type="button" className="btn btn-sm btn-ghost" style={darkGhost}
          onClick={() => setScale((v) => Math.min(6, Math.round((v + 0.2) * 100) / 100))}>＋</button>
        <span style={{ opacity: 0.75 }}>Ctrl + 滚轮缩放 · 双击复位</span>
        <button type="button" className="btn btn-sm btn-primary"
          onClick={() => void downloadMedia(url, filename)}><DownloadIcon /> 保存</button>
      </div>
      <CloseButton onClose={onClose} />
    </Overlay>
  );
};

/**
 * 视频播放器（2026-10-23 改造）：
 *  · 去掉白色容器，视频直接铺在轻量蒙层上，控制条是半透明浮条；
 *  · 进度条支持**按住拖动**（pointer 事件 + 视口级监听，拖出条外也不断），
 *    点击任意位置直接跳转；
 *  · 全屏作用于整个播放器盒子，视频高度改为 100vh - 控制条高度，
 *    不再受 maxHeight: 60vh 限制（此前全屏只铺满一半窗口就是这个原因）。
 */
const VideoPlayerModal: React.FC<{
  url: string;
  poster?: string | null;
  onClose: () => void;
}> = ({ url, poster, onClose }) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const boxRef = useRef<HTMLDivElement | null>(null);
  const trackRef = useRef<HTMLDivElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  const [volume, setVolume] = useState(1);
  const [current, setCurrent] = useState(0);
  const [duration, setDuration] = useState(0);
  const [isFs, setIsFs] = useState(false);
  const [dragging, setDragging] = useState(false);
  /** 控制条显隐（2026-10-23）：五秒无操作渐隐，移动鼠标/触摸/点击重新出现 */
  const [controlsVisible, setControlsVisible] = useState(true);
  const hideTimer = useRef<number | null>(null);

  const wake = useCallback(() => {
    setControlsVisible(true);
    if (hideTimer.current) window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setControlsVisible(false), 5000);
  }, []);

  useEffect(() => {
    wake();
    return () => {
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
    };
  }, [wake]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // 全屏时先退全屏（真实浏览器会自己处理，这里兜住自动化/部分内嵌环境）
      if (document.fullscreenElement) {
        void document.exitFullscreen();
        return;
      }
      onClose();
    };
    const onFsChange = () => setIsFs(Boolean(document.fullscreenElement));
    window.addEventListener('keydown', onKey);
    document.addEventListener('fullscreenchange', onFsChange);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.removeEventListener('fullscreenchange', onFsChange);
    };
  }, [onClose]);

  /**
   * 关闭播放器（2026-10-23 修）：
   * 全屏时先退全屏 —— 全屏元素在浏览器顶层，元素之外的关闭按钮会被 `<video>` 盖住点不到。
   * 现在关闭按钮本身渲染在播放器盒子内（见下方 JSX），全屏下也在顶层，可直接点。
   */
  const close = () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    onClose();
  };

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) void v.play();
    else v.pause();
  };

  const seekTo = (clientX: number) => {
    const track = trackRef.current;
    const v = videoRef.current;
    if (!track || !v || !duration) return;
    const rect = track.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const next = ratio * duration;
    v.currentTime = next;
    setCurrent(next);
  };

  // 拖动期间监听 window：鼠标移出进度条甚至移到窗口外仍然跟手
  useEffect(() => {
    if (!dragging) return undefined;
    const move = (e: PointerEvent) => seekTo(e.clientX);
    const up = () => setDragging(false);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dragging, duration]);

  const fullscreen = () => {
    const box = boxRef.current;
    if (!box) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void box.requestFullscreen?.();
  };

  const ratio = duration > 0 ? Math.min(1, current / duration) : 0;

  return (
    <Overlay onClose={onClose}>
      <div
        ref={boxRef}
        onPointerMove={wake}
        onPointerDown={wake}
        style={{
          // ★ 控制条改**绝对定位浮在画面上**（2026-10-23）：不再占据垂直空间，
          //   视频因此能完整铺满容器；磨砂由「半透明渐变 + backdropFilter」实现。
          position: 'relative',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          width: isFs ? '100%' : 'auto',
          height: isFs ? '100%' : 'auto',
          maxWidth: '100%', maxHeight: '100%',
          background: isFs ? '#000' : 'transparent',
          borderRadius: isFs ? 0 : 10,
          overflow: 'hidden',
          cursor: controlsVisible ? 'default' : 'none',
        }}
      >
        <video
          ref={videoRef}
          src={url}
          poster={poster ?? undefined}
          playsInline
          onClick={() => {
            wake();
            togglePlay();
          }}
          style={{
            display: 'block', background: '#000',
            width: isFs ? '100%' : 'auto',
            maxWidth: isFs ? '100%' : 'min(92vw, 1100px)',
            // 全屏铺满整屏（控制条浮层不占位），非全屏按比例自适应
            height: isFs ? '100%' : 'auto',
            maxHeight: isFs ? 'none' : '84vh',
            objectFit: 'contain',
            borderRadius: isFs ? 0 : 8,
            boxShadow: isFs ? 'none' : '0 16px 56px rgba(0,0,0,0.5)',
            cursor: 'pointer',
          }}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onTimeUpdate={(e) => setCurrent(e.currentTarget.currentTime)}
          onLoadedMetadata={(e) => setDuration(e.currentTarget.duration || 0)}
        />

        {/* 悬浮磨砂控制条：底部渐变 + 背景模糊，五秒无操作渐隐 */}
        <div
          data-testid="video-controls"
          style={{
            position: 'absolute', left: 0, right: 0, bottom: 0,
            display: 'flex', alignItems: 'center', gap: 10,
            padding: isFs ? '20px 18px 14px' : '16px 12px 10px',
            background:
              'linear-gradient(to top, rgba(0,0,0,0.78) 0%, rgba(0,0,0,0.48) 55%, rgba(0,0,0,0) 100%)',
            backdropFilter: 'blur(10px)',
            WebkitBackdropFilter: 'blur(10px)',
            color: '#fff',
            opacity: controlsVisible ? 1 : 0,
            transform: controlsVisible ? 'translateY(0)' : 'translateY(8px)',
            transition: 'opacity 260ms ease, transform 260ms ease',
            pointerEvents: controlsVisible ? 'auto' : 'none',
          }}
        >
          <button type="button" className="btn btn-sm btn-ghost" style={darkGhost} onClick={togglePlay} aria-label={playing ? '暂停' : '播放'}>
            {playing ? <PauseIcon /> : <PlayIcon />}
          </button>
          <span style={{ fontVariantNumeric: 'tabular-nums', fontSize: 12, minWidth: 84 }}>
            {fmtTime(current)} / {fmtTime(duration)}
          </span>
          {/* 进度条：外层 16px 命中区，内层 4px 视觉轨 */}
          <div
            ref={trackRef}
            role="slider"
            aria-label="播放进度"
            aria-valuemin={0}
            aria-valuemax={Math.round(duration)}
            aria-valuenow={Math.round(current)}
            onPointerDown={(e) => {
              e.preventDefault();
              wake();
              seekTo(e.clientX);
              setDragging(true);
            }}
            style={{ flex: 1, height: 16, display: 'flex', alignItems: 'center', cursor: 'pointer', minWidth: 120, touchAction: 'none' }}
          >
            <div style={{ position: 'relative', width: '100%', height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.28)' }}>
              <div style={{ position: 'absolute', inset: 0, width: `${ratio * 100}%`, borderRadius: 2, background: 'var(--primary, #4f6ef2)' }} />
              <div
                style={{
                  position: 'absolute', top: '50%', left: `${ratio * 100}%`, width: 12, height: 12,
                  marginLeft: -6, marginTop: -6, borderRadius: '50%', background: '#fff',
                  boxShadow: '0 1px 4px rgba(0,0,0,0.4)', transform: dragging ? 'scale(1.25)' : 'scale(1)',
                  transition: 'transform 0.1s ease',
                }}
              />
            </div>
          </div>
          <button type="button" className="btn btn-sm btn-ghost" style={darkGhost} aria-label={muted ? '取消静音' : '静音'} onClick={() => {
            const v = videoRef.current;
            if (!v) return;
            const next = !muted;
            v.muted = next;
            setMuted(next);
          }}>
            {muted ? <VolumeOffIcon /> : <VolumeOnIcon />}
          </button>
          <input
            type="range" min={0} max={1} step={0.05} value={volume} style={{ width: 64, accentColor: '#fff' }}
            onChange={(e) => {
              const val = Number(e.target.value);
              setVolume(val);
              const v = videoRef.current;
              if (v) {
                v.volume = val;
                v.muted = val === 0;
                setMuted(val === 0);
              }
            }}
            aria-label="音量"
          />
          <button type="button" className="btn btn-sm btn-ghost" style={darkGhost} onClick={fullscreen} title="全屏" aria-label={isFs ? '退出全屏' : '全屏'}>
            {isFs ? <ExitFullscreenIcon /> : <FullscreenIcon />}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-primary"
            onClick={() => void downloadMedia(url, url.split('/').pop() || 'video.mp4')}
          >
            <DownloadIcon /> 保存
          </button>
        </div>
        {/* 关闭按钮放在全屏元素内部：全屏时它也在顶层，不会被 video 盖住 */}
        <CloseButton onClose={close} />
      </div>
    </Overlay>
  );
};

/**
 * 媒体气泡（2026-10-22 重构）：
 *  · 图片：点击弹**灯箱**（缩放 + 下载按钮），不再新开标签页直接下载；
 *  · 视频：气泡只渲染海报 + 精美播放按钮（无原生控件条），点击弹自绘播放窗口
 *    （音量 / 全屏 / 保存到本地）。媒体文件已确认存 COS（STORAGE_TYPE=cos，mp4 不追加处理后缀）。
 *  · 贴纸：按图片渲染（保持原行为）。
 */
export const MediaBubble: React.FC<{
  kind: 'IMAGE' | 'VIDEO' | 'EMOJI';
  url: string;
  thumbUrl?: string | null;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  text?: string;
}> = ({ kind, url, thumbUrl, width, height, durationMs, text }) => {
  const [viewer, setViewer] = useState<null | 'image' | 'video'>(null);

  if (kind === 'EMOJI') {
    if (/^https?:\/\//.test(url)) {
      return <img src={url} alt="表情" style={{ width: 96, height: 96, display: 'block' }} />;
    }
    return <div style={{ fontSize: 34, lineHeight: 1.2 }}>{text || url}</div>;
  }

  if (kind === 'VIDEO') {
    const w = width && width > 0 ? Math.min(width, 260) : 220;
    const h = width && height && height > 0 ? Math.round((w * height) / width) : Math.round(w * 0.56);
    return (
      <>
        <button
          type="button"
          onClick={() => setViewer('video')}
          title="点击播放"
          style={{
            position: 'relative', width: w, height: Math.min(h, 200), padding: 0,
            border: 'none', cursor: 'pointer', borderRadius: 10, overflow: 'hidden',
            background: '#1c2130', display: 'block',
          }}
        >
          {thumbUrl ? (
            <img
              src={thumbUrl}
              alt="视频封面"
              style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
            />
          ) : (
            /**
             * 历史消息（2026-10-23 之前发送的）没有 thumb_url。
             * 用 `<video>` 自身画首帧兜底：`#t=0.1` 是媒体片段语法，浏览器会 seek 到 0.1s 并渲染那一帧，
             * 比纯色占位强得多，也不需要服务端补数据。`pointerEvents:none` 让点击穿透到外层按钮。
             */
            <video
              src={`${url}#t=0.1`}
              muted
              playsInline
              preload="metadata"
              aria-hidden
              style={{
                width: '100%', height: '100%', objectFit: 'cover', display: 'block',
                pointerEvents: 'none', background: '#1c2130',
              }}
            />
          )}
          {/* 自绘播放按钮：白色半透明圆底 + 三角，不用原生控件 */}
          <span
            aria-hidden
            style={{
              position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
              background: 'rgba(0,0,0,0.18)',
            }}
          >
            <span
              style={{
                width: 44, height: 44, borderRadius: '50%',
                background: 'rgba(255,255,255,0.92)', display: 'grid', placeItems: 'center',
                boxShadow: '0 2px 8px rgba(0,0,0,0.35)',
              }}
            >
              <span
                style={{
                  width: 0, height: 0, marginLeft: 4,
                  borderTop: '9px solid transparent', borderBottom: '9px solid transparent',
                  borderLeft: '15px solid #1c2130',
                }}
              />
            </span>
          </span>
          {durationMs ? (
            <span
              style={{
                position: 'absolute', right: 6, bottom: 6, padding: '1px 6px', borderRadius: 6,
                background: 'rgba(0,0,0,0.6)', color: '#fff', fontSize: 11,
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {fmtTime(durationMs / 1000)}
            </span>
          ) : null}
        </button>
        {viewer === 'video' && (
          <VideoPlayerModal url={url} poster={thumbUrl} onClose={() => setViewer(null)} />
        )}
      </>
    );
  }

  const w = width && width > 0 ? Math.min(width, 260) : 220;
  // ★ 消息里的图片可能没有宽高（上传接口只回 URL）：不能留 height:undefined + lazy ——
  //   图片在滚动容器里没进入视口就永远不加载，元素高度为 0，用户看到的是「什么都没有」。
  //   给一个稳定占位高度并去掉 lazy（会话内图片本就在可视区附近）。
  const h = height && width && height > 0 ? Math.round((w * height) / width) : 180;
  return (
    <>
      <img
        src={url}
        alt=""
        onClick={() => setViewer('image')}
        style={{
          width: w, height: Math.min(h, 300), maxHeight: 300, objectFit: 'cover', borderRadius: 10,
          display: 'block', cursor: 'zoom-in', background: 'var(--surface-2)',
        }}
      />
      {viewer === 'image' && <ImageLightbox url={url} onClose={() => setViewer(null)} />}
    </>
  );
};

// ---------- 语音 / 引用 / 撤回（2026-10-24 第九十一轮） ----------

/** 语音气泡：点击播放/暂停；没有花哨波形 —— 一条比例进度条足够传达状态 */
export const VoiceBubble: React.FC<{ url: string; durationMs: number | null }> = ({ url, durationMs }) => {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const secs = durationMs ? Math.max(1, Math.round(durationMs / 1000)) : 0;
  return (
    <div
      className="voice-bubble"
      role="button"
      aria-label={playing ? `暂停语音 ${secs} 秒` : `播放语音 ${secs} 秒`}
      onClick={() => {
        const el = audioRef.current;
        if (!el) return;
        if (playing) {
          el.pause();
        } else {
          void el.play();
        }
      }}
    >
      <audio
        ref={audioRef}
        src={url}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => { setPlaying(false); setProgress(0); }}
        onTimeUpdate={(e) => {
          const el = e.currentTarget;
          setProgress(el.duration > 0 ? Math.min(1, el.currentTime / el.duration) : 0);
        }}
      />
      <span className="voice-icon" aria-hidden>{playing ? '⏸' : '▶'}</span>
      <span className="voice-bar" aria-hidden>
        <span className="voice-bar-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
      </span>
      <span className="voice-dur">{secs}"</span>
    </div>
  );
};

/**
 * 点击录音（2026-10-25 重构）：点麦克风开始 → 可暂停/继续 → 点结束发送。
 *
 * <p>为什么不再「按住说话」：按住对手指按压力道、按钮焦点都敏感，实测大量用户
 * 按一下就松手，反复弹出「说话时间太短」。点击式把「开始」变成显式动作，
 * 暂停/继续是 MediaRecorder 原生能力，时长只累计「正在录」的片段。
 */
export const VOICE_MAX_MS_WEB = 60_000;

export type VoiceRecorderStatus = 'idle' | 'recording' | 'paused';

export const useVoiceRecorder: (opts: {
  onReady: (blob: Blob, durationMs: number) => void;
  onError: (message: string) => void;
}) => {
  status: VoiceRecorderStatus;
  recording: boolean;
  seconds: number;
  start: () => void;
  pause: () => void;
  resume: () => void;
  stop: () => void;
  cancel: () => void;
} = ({ onReady, onError }) => {
  const [status, setStatus] = useState<VoiceRecorderStatus>('idle');
  const [seconds, setSeconds] = useState(0);
  const recRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const segmentStartRef = useRef(0);
  const activeMsRef = useRef(0);
  const mimeRef = useRef('');
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const optsRef = useRef({ onReady, onError });
  optsRef.current = { onReady, onError };

  const totalMs = useCallback(
    () => activeMsRef.current + (recRef.current?.state === 'recording' ? Date.now() - segmentStartRef.current : 0),
    [],
  );

  const cleanup = useCallback(() => {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    recRef.current = null;
    activeMsRef.current = 0;
    setStatus('idle');
    setSeconds(0);
  }, []);

  const finish = useCallback(() => {
    const rec = recRef.current;
    if (!rec || rec.state === 'inactive') return;
    // ★ 2026-10-24 修复：正在录的那一段此前从未结算 —— onstop 只读 activeMsRef，
    // 于是「录了 N 秒后直接点结束」永远是 0ms，被误判「录音太短」；先暂停再结束才能成功。
    // 这里在 stop 之前把最后一段补进累计值（暂停态已由 pause() 结算过，不能重复加）。
    if (rec.state === 'recording') {
      activeMsRef.current += Date.now() - segmentStartRef.current;
    }
    if (rec.state === 'paused') rec.resume(); // stop() 在 paused 态不触发 ondataavailable 收尾
    rec.stop();
  }, []);

  const stop = useCallback(() => { finish(); }, [finish]);

  const cancel = useCallback(() => {
    const rec = recRef.current;
    chunksRef.current = [];
    if (rec) {
      rec.onstop = () => cleanup(); // 丢弃数据，不回调 onReady
      if (rec.state === 'paused') rec.resume();
      rec.stop();
      recRef.current = null;
    } else {
      cleanup();
    }
  }, [cleanup]);

  const start = useCallback(() => {
    if (recRef.current) return;
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      optsRef.current.onError('当前浏览器不支持录音');
      return;
    }
    navigator.mediaDevices.getUserMedia({ audio: true }).then((stream) => {
      streamRef.current = stream;
      const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm'
        : MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : '';
      mimeRef.current = mime;
      const rec = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
      chunksRef.current = [];
      activeMsRef.current = 0;
      rec.ondataavailable = (e) => { if (e.data.size > 0) chunksRef.current.push(e.data); };
      rec.onstop = () => {
        const durationMs = activeMsRef.current;
        const blob = new Blob(chunksRef.current, { type: mimeRef.current || 'audio/webm' });
        cleanup();
        if (durationMs < 600) {
          optsRef.current.onError('录音太短，请按住麦克风图标后再结束');
          return;
        }
        optsRef.current.onReady(blob, Math.min(durationMs, VOICE_MAX_MS_WEB));
      };
      segmentStartRef.current = Date.now();
      rec.start();
      recRef.current = rec;
      setStatus('recording');
      timerRef.current = setInterval(() => {
        const ms = totalMs();
        setSeconds(Math.floor(ms / 1000));
        if (ms >= VOICE_MAX_MS_WEB) finish(); // 60 秒自动结束并发送
      }, 250);
    }).catch(() => optsRef.current.onError('无法访问麦克风，请检查浏览器权限'));
  }, [cleanup, finish, totalMs]);

  const pause = useCallback(() => {
    const rec = recRef.current;
    if (!rec || rec.state !== 'recording') return;
    activeMsRef.current += Date.now() - segmentStartRef.current;
    rec.pause();
    setStatus('paused');
  }, []);

  const resume = useCallback(() => {
    const rec = recRef.current;
    if (!rec || rec.state !== 'paused') return;
    segmentStartRef.current = Date.now();
    rec.resume();
    setStatus('recording');
  }, []);

  useEffect(() => () => {
    recRef.current?.state !== 'inactive' && recRef.current?.stop();
    cleanup();
  }, [cleanup]);

  return { status, recording: status === 'recording', seconds, start, pause, resume, stop, cancel };
};

/** 引用条：气泡顶部灰条 + 点击定位到原消息 */
export const QuotedStrip: React.FC<{
  excerpt: string;
  onClick?: () => void;
}> = ({ excerpt, onClick }) => (
  <div
    className="chat-quoted"
    role={onClick ? 'button' : undefined}
    onClick={onClick}
    title={onClick ? '点击查看原消息' : undefined}
  >
    {excerpt}
  </div>
);

/** 撤回后整行替换为居中系统灰字（2026-10-25：文案居中，不再是气泡内小字） */
export const RecallLine: React.FC<{ mine: boolean }> = ({ mine }) => (
  <div className="chat-recalled">{mine ? '你撤回了一条消息' : '对方撤回了一条消息'}</div>
);

/**
 * 消息操作菜单（2026-10-25）：悬停消息行尾出现「···」，点击弹出 引用/删除/撤回/多选。
 * 移动端触摸没有 hover —— 长按场景在小程序端实现，web 端也兼容点按（菜单按钮始终可点）。
 */
/**
 * 消息操作菜单（2026-10-25，2026-10-24 修复定位）。
 *
 * <p>为什么用 Portal + fixed：菜单此前是气泡行内的 absolute 元素，聊天滚动区
 * （`.sup-body`，overflow: auto）会把它裁掉 —— 对方消息靠近左边缘时尤其明显，
 * 表现为「只露出一个空白小框」。改为挂到 document.body 并按触发按钮的
 * 视口坐标定位，再按菜单实际尺寸做边界夹取（右/下越界时翻转或内推），
 * 既不受滚动容器裁剪，也不会跑出屏幕。
 *
 * <p>滚动/resize 时直接关闭：fixed 坐标不会跟随内容滚动，留着会「飘」。
 */
export const MessageActions: React.FC<{
  onQuote?: (() => void) | null;
  onRecall?: (() => void) | null;
  onDelete?: (() => void) | null;
  onMultiSelect?: (() => void) | null;
  /** 复选模式下整行不可再弹菜单 */
  disabled?: boolean;
}> = ({ onQuote, onRecall, onDelete, onMultiSelect, disabled }) => {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      const t = e.target as Node;
      if (wrapRef.current?.contains(t) || menuRef.current?.contains(t)) return;
      setOpen(false);
    };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const dismiss = () => setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    // fixed 定位不随内容滚动 → 滚动即关，避免菜单与气泡错位
    window.addEventListener('scroll', dismiss, true);
    window.addEventListener('resize', dismiss);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', esc);
      window.removeEventListener('scroll', dismiss, true);
      window.removeEventListener('resize', dismiss);
    };
  }, [open]);

  // 打开后按菜单真实尺寸夹取到视口内（先渲染到屏外量尺寸，再落位，避免闪烁）
  useLayoutEffect(() => {
    if (!open || !triggerRef.current || !menuRef.current) return;
    const t = triggerRef.current.getBoundingClientRect();
    const m = menuRef.current.getBoundingClientRect();
    const pad = 8;
    let left = Math.min(t.right - m.width, window.innerWidth - m.width - pad);
    left = Math.max(pad, left);
    let top = t.bottom + 6;
    if (top + m.height > window.innerHeight - pad) top = t.top - m.height - 6;
    top = Math.max(pad, top);
    setPos({ top, left });
  }, [open]);

  if (disabled) return null;
  const hasAny = onQuote || onRecall || onDelete || onMultiSelect;
  if (!hasAny) return null;
  return (
    <div className="msg-menu-wrap" ref={wrapRef}>
      <button
        ref={triggerRef}
        type="button"
        className="msg-menu-trigger"
        aria-label="消息操作"
        onClick={() => {
          setPos(null);
          setOpen((v) => !v);
        }}
      >
        ···
      </button>
      {open
        ? createPortal(
            <div
              ref={menuRef}
              className="msg-menu"
              role="menu"
              style={{
                top: pos?.top ?? -9999,
                left: pos?.left ?? -9999,
                visibility: pos ? 'visible' : 'hidden',
              }}
            >
              {onQuote ? <button type="button" role="menuitem" onClick={() => { setOpen(false); onQuote(); }}>引用</button> : null}
              {onDelete ? <button type="button" role="menuitem" onClick={() => { setOpen(false); onDelete(); }}>删除</button> : null}
              {onRecall ? <button type="button" role="menuitem" onClick={() => { setOpen(false); onRecall(); }}>撤回</button> : null}
              {onMultiSelect ? <button type="button" role="menuitem" onClick={() => { setOpen(false); onMultiSelect(); }}>多选</button> : null}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
};

/** 多选删除工具条（2026-10-25）：进入选择模式后固定在会话底部 */
export const MultiSelectBar: React.FC<{
  count: number;
  totalCount: number;
  onToggleAll: () => void;
  onDelete: () => void;
  onCancel: () => void;
}> = ({ count, totalCount, onToggleAll, onDelete, onCancel }) => (
  <div className="msg-select-bar" role="toolbar">
    <button type="button" className="btn btn-sm btn-ghost" onClick={onToggleAll}>
      {count === totalCount && totalCount > 0 ? '取消全选' : '全选'}
    </button>
    <span className="small muted">已选 {count} 条</span>
    <span style={{ flex: 1 }} />
    <button type="button" className="btn btn-sm btn-ghost" onClick={onCancel}>取消</button>
    <button type="button" className="btn btn-sm btn-danger" disabled={count === 0} onClick={onDelete}>
      删除
    </button>
  </div>
);
