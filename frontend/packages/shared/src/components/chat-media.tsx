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
import React, { useRef, useState } from 'react';

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
  return { mediaUrl: body.data.url, width, height, durationMs, compressed };
}

/** 判断是否纯 emoji（只有 emoji 时气泡放大显示，模拟「表情包」的观感） */
export function isEmojiOnly(text: string): boolean {
  const trimmed = (text || '').trim();
  if (!trimmed) return false;
  if (trimmed.length > 12) return false;
  return /^(\p{Extended_Pictographic}|\uFE0F|\u200D|\s)+$/u.test(trimmed);
}

/** 内置贴纸（商城 public/stickers，商家端共享同一目录） */
const STICKERS = ['ok', 'thanks', 'wait', 'cry', 'love', 'angry'];

const stickerUrl = (name: string) =>
  `${typeof window !== 'undefined' ? window.location.origin : ''}/stickers/${name}.svg`;

const EMOJIS = ['😀', '😂', '🥹', '😍', '🤔', '😭', '😡', '👍', '👏', '🙏', '🎉', '❤️', '🐶', '🐱', '🐟', '🦴'];

/**
 * 输入区「+」菜单：图片 / 视频 / 表情 / 贴纸。
 *
 * <p>压缩与上传期间按钮上显示实时阶段（压缩 37% / 上传 62%），
 * 用户不会面对一个"点了没反应"的按钮。
 */
export const ChatMediaMenu: React.FC<{
  onMedia: (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => void;
  onEmoji: (emoji: string) => void;
  onSticker: (url: string) => void;
  onError: (message: string) => void;
  disabled?: boolean;
}> = ({ onMedia, onEmoji, onSticker, onError, disabled }) => {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState('');
  const imageRef = useRef<HTMLInputElement | null>(null);
  const videoRef = useRef<HTMLInputElement | null>(null);

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

  return (
    <div style={{ position: 'relative' }}>
      <button
        type="button"
        className="btn btn-ghost"
        title="发送图片 / 视频 / 表情"
        onClick={() => setOpen((v) => !v)}
        disabled={disabled || Boolean(busy)}
      >
        {busy || '＋'}
      </button>
      {open && !busy && (
        <div
          className="card"
          style={{ position: 'absolute', bottom: '110%', left: 0, padding: 8, zIndex: 20, width: 232 }}
        >
          <div className="row" style={{ gap: 6, marginBottom: 8 }}>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => imageRef.current?.click()}>
              🖼 图片
            </button>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => videoRef.current?.click()}>
              🎬 视频
            </button>
          </div>
          <div className="small muted" style={{ marginBottom: 4 }}>
            表情
          </div>
          <div className="row row-wrap" style={{ gap: 2 }}>
            {EMOJIS.map((emoji) => (
              <button
                key={emoji}
                type="button"
                className="btn btn-sm btn-ghost"
                style={{ fontSize: 18, padding: '2px 6px' }}
                onClick={() => {
                  onEmoji(emoji);
                  setOpen(false);
                }}
              >
                {emoji}
              </button>
            ))}
          </div>
          <div className="small muted" style={{ margin: '8px 0 4px' }}>
            贴纸
          </div>
          <div className="row row-wrap" style={{ gap: 4 }}>
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
                <img src={stickerUrl(name)} alt={name} style={{ width: 40, height: 40 }} />
              </button>
            ))}
          </div>
          <div className="small muted" style={{ marginTop: 8 }}>
            视频 ≤{VIDEO_MAX_SECONDS} 秒；超过 6MB 会先压缩（画质 720p）
          </div>
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

/** 媒体气泡：图片点击新窗查看；视频内嵌播放；贴纸按图片渲染 */
export const MediaBubble: React.FC<{
  kind: 'IMAGE' | 'VIDEO' | 'EMOJI';
  url: string;
  thumbUrl?: string | null;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  text?: string;
}> = ({ kind, url, thumbUrl, width, height, durationMs, text }) => {
  if (kind === 'VIDEO') {
    return (
      <video
        src={url}
        poster={thumbUrl ?? undefined}
        controls
        preload="metadata"
        style={{ maxWidth: 260, borderRadius: 10, display: 'block' }}
      >
        {durationMs ? `视频（${Math.round(durationMs / 1000)} 秒）` : '视频'}
      </video>
    );
  }
  if (kind === 'EMOJI') {
    if (/^https?:\/\//.test(url)) {
      return <img src={url} alt="表情" style={{ width: 96, height: 96, display: 'block' }} />;
    }
    return <div style={{ fontSize: 34, lineHeight: 1.2 }}>{text || url}</div>;
  }
  const w = width && width > 0 ? Math.min(width, 260) : 220;
  const h = height && width && height > 0 ? Math.round((w * height) / width) : undefined;
  return (
    <a href={url} target="_blank" rel="noreferrer">
      <img
        src={url}
        alt=""
        loading="lazy"
        style={{ width: w, height: h, maxHeight: 300, objectFit: 'cover', borderRadius: 10, display: 'block' }}
      />
    </a>
  );
};
