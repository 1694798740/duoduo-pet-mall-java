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

module.exports = {
  MAX_SECONDS,
  prepareVideo,
};
