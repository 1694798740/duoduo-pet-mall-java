/**
 * 服务间调用凭据（JS 侧实现，与 scripts/lib/internal-token.ps1 同算法）
 *
 * 为什么要有这份 JS 版：前端 e2e 脚本是 .mjs，跑在 Node 里，没法 dot-source PowerShell。
 * 而它调用的又都是**内部接口**（/internal/**），走的是 R58 起的 HMAC 签名凭据 ——
 * 继续发明文旧令牌只会拿到 403，表现为「夹具自检失败」，看不出是凭据问题。
 *
 * ⚠️ 两份实现必须保持一致：算法改了要同时改。为此提供了
 *    `scripts/lib/internal-token-parity.test.js` —— 用**固定输入**比对两侧输出，漂移即红。
 *
 * 头格式： dd1.<caller>.<tsSeconds>.<nonce>.<签名>
 *   签名     = Base64Url(HMAC-SHA256(密钥(caller), "dd1\ncaller\nts\nnonce\nMETHOD"))
 *   密钥     = Base64Url(HMAC-SHA256(根密钥, "dd1|key|<caller 小写>"))
 * 只签方法不签路径（R59 修正，理由见 ps1 版文件头）。nonce 一次性，复用判重放。
 */
import crypto from 'node:crypto';

const DEFAULT_ROOT = 'duoduo-local-dev-internal-token';

/** HMAC-SHA256 → Base64Url 无填充（与 Java 的 Base64.getUrlEncoder().withoutPadding() 同口径） */
export function internalHmac(key, data) {
  return crypto
    .createHmac('sha256', Buffer.from(String(key), 'utf8'))
    .update(Buffer.from(String(data), 'utf8'))
    .digest('base64')
    .replace(/=+$/, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

/**
 * 签发一次内部调用凭据。
 *
 * @param {string} method HTTP 方法（参与签名，必须与真实请求一致）
 * @param {object} [opts]
 * @param {string} [opts.caller] 调用方标识，默认 duoduo-smoke
 * @param {string} [opts.root]   根密钥，默认与服务端本地默认值一致
 * @param {number} [opts.ts]     时间戳（秒）—— 仅供对拍测试固定输入，生产调用不要传
 * @param {string} [opts.nonce]  随机串 —— 同上，仅供对拍
 * @returns {string} 可直接放进 X-Internal-Token 的值
 */
export function newInternalToken(method, opts = {}) {
  const caller = opts.caller ?? 'duoduo-smoke';
  const root = opts.root ?? process.env.INTERNAL_TOKEN ?? DEFAULT_ROOT;
  const ts = opts.ts ?? Math.floor(Date.now() / 1000);
  const nonce = opts.nonce ?? crypto.randomBytes(16).toString('hex');
  const key = internalHmac(root, `dd1|key|${caller.toLowerCase()}`);
  const sig = internalHmac(key, `dd1\n${caller}\n${ts}\n${nonce}\n${String(method).toUpperCase()}`);
  return `dd1.${caller}.${ts}.${nonce}.${sig}`;
}

/** 便捷写法：生成一条 `{ 'X-Internal-Token': ... }` 头 */
export function internalHeaders(method, opts = {}) {
  return { 'X-Internal-Token': newInternalToken(method, opts) };
}
