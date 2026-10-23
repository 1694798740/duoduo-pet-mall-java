// 表情与贴纸（2026-10-23 · 与 Web 端 chat-media.tsx 同一套清单）
//
// 为什么要和小程序放一起而不是只留 Web：客服/私信是同一段对话，
// 用户在 Web 上能发的表情，在小程序里找不到，就会以为"功能没了"。
// 清单保持 56 个常用表情 + 12 个贴纸，与 Web 完全一致（改一处要同步另一处）。
const { STATIC_BASE } = require('../config');

const EMOJIS = [
  '😀', '😄', '😁', '😊', '🥰', '😍', '😘', '😜',
  '🤪', '😎', '🤗', '🤔', '😐', '😴', '🥱', '😪',
  '😂', '🤣', '😅', '😇', '🙃', '😉', '😌', '😢',
  '🥹', '😭', '😤', '😡', '🤬', '😱', '😨', '😰',
  '👍', '👎', '👏', '🙏', '🤝', '💪', '✌️', '👌',
  '🎉', '🎁', '🔥', '💯', '❤️', '💔', '⭐', '🌈',
  '🐶', '🐱', '🐟', '🦴', '🐾', '🐹', '🐰', '🐦',
];

const STICKER_NAMES = [
  'ok', 'thanks', 'wait', 'cry', 'love', 'angry',
  'hi', 'sorry', 'busy', 'question', 'nice', 'bye',
];

/** 静态站点未配置时返回空数组：只提供 emoji，不显示贴纸页签 */
const STICKERS = STATIC_BASE
  ? STICKER_NAMES.map((n) => `${STATIC_BASE.replace(/\/+$/, '')}/stickers/${n}.svg`)
  : [];

module.exports = { EMOJIS, STICKERS };
