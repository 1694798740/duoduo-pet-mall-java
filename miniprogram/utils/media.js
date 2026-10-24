/**
 * 视频发送前的端侧处理（第七十九轮）。
 *
 * <h2>规则</h2>
 * 只校验**时长 ≤ 60 秒**，不限制文件大小；超过 6MB 先尝试 `wx.compressVideo` 压缩，
 * 压缩失败或接口不可用就原样上传 —— 与 Web 端同一套口径。
 *
 * <p>为什么不在这里做分辨率控制：小程序没有开放 canvas 重编码视频的能力，
 * `wx.compressVideo` 只提供 quality 档位（low/medium/high），能显著降码率但不能指定 720p。
 * 这是平台限制，不是实现取舍；Web 端才有 canvas + MediaRecorder 的完整控制。
 */
const MAX_SECONDS = 60;
/** 低于此体积不压缩：等压缩不如直接传 */
const COMPRESS_THRESHOLD = 6 * 1024 * 1024;

const api = require('./api');

/**
 * 上传视频封面（2026-10-23）。
 *
 * <p>`wx.chooseMedia` 对视频会额外给一个 `thumbTempFilePath`（首帧缩略图）。
 * 之前这个字段被丢掉了，于是接收方只有 video 组件自己解码首帧才能看到画面 ——
 * 弱网/未预加载时就是一块黑色底。把它当作普通图片上传，随消息一起落 `thumb_url`，
 * 接收端气泡立刻有封面，播放器也能用它做 poster。
 *
 * <p>失败返回空串：封面是增强项，绝不能因为它拦住消息发送。
 */
function uploadPoster(file) {
  if (!file || !file.thumbTempFilePath) {
    return Promise.resolve('');
  }
  return api
    .upload(file.thumbTempFilePath)
    .then((data) => (data && data.url) || '')
    .catch(() => '');
}

function getVideoInfo(src) {
  return new Promise((resolve, reject) => {
    wx.getVideoInfo({
      src,
      success: resolve,
      fail: () => reject(new Error('视频无法读取，请换一个文件')),
    });
  });
}

function compressVideo(src) {
  return new Promise((resolve) => {
    // 不支持的机型/版本会走 fail → resolve(null)，由调用方回退原视频
    wx.compressVideo({
      src,
      quality: 'medium',
      success: (res) => resolve(res && res.tempFilePath ? res.tempFilePath : null),
      fail: () => resolve(null),
    });
  });
}

/**
 * 校验 + 必要时压缩。
 * @returns Promise<{ path, width, height, durationMs, compressed }>
 */
function prepareVideo(file) {
  if (!file || !file.tempFilePath) {
    return Promise.reject(new Error('没有选择视频'));
  }
  return getVideoInfo(file.tempFilePath).then((info) => {
    const seconds = Number(info.duration || 0);
    if (seconds > MAX_SECONDS + 0.5) {
      return Promise.reject(new Error('视频不能超过 ' + MAX_SECONDS + ' 秒，请先裁剪后再发送'));
    }
    const base = {
      width: info.width,
      height: info.height,
      durationMs: Math.round(seconds * 1000),
    };
    if (!file.size || file.size <= COMPRESS_THRESHOLD) {
      return Object.assign({ path: file.tempFilePath, compressed: false }, base);
    }
    wx.showLoading({ title: '压缩中…', mask: true });
    return compressVideo(file.tempFilePath).then((path) =>
      Object.assign({ path: path || file.tempFilePath, compressed: Boolean(path) }, base)
    );
  });
}

/**
 * 语音录制（2026-10-24）：RecorderManager 单例包装。
 * 松手即回调临时文件与实测时长；<600ms 视为误触报错；60 秒自动 onStop。
 */
function createVoiceRecorder(callbacks) {
  const rec = wx.getRecorderManager();
  let startedAt = 0;
  rec.onStart(() => {
    startedAt = Date.now();
    if (callbacks.onStart) callbacks.onStart();
  });
  rec.onStop((res) => {
    const durationMs = Date.now() - startedAt;
    if (durationMs < 600) {
      if (callbacks.onError) callbacks.onError('说话时间太短，再按久一点');
      return;
    }
    if (callbacks.onReady) callbacks.onReady(res.tempFilePath, Math.min(durationMs, MAX_SECONDS * 1000));
  });
  rec.onError(() => {
    if (callbacks.onError) callbacks.onError('录音失败，请重试');
  });
  return {
    start() {
      rec.start({ duration: MAX_SECONDS * 1000, format: 'aac', sampleRate: 44100, encodeBitRate: 96000 });
    },
    stop() {
      rec.stop();
    },
  };
}

module.exports = {
  MAX_SECONDS,
  prepareVideo,
  uploadPoster,
  createVoiceRecorder,
};
