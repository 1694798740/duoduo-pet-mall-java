/**
 * 从仓库根 `.env` 生成小程序端的高德 Key 配置（`miniprogram/config/amap.js`）。
 *
 * 为什么要生成而不是直接写死在代码里：
 *   小程序包（.wxapkg）可以被解包，Key 必然会随包下发 —— 这无法改变。
 *   但可以做到「**不进 Git 历史、只存在一个地方、可一键轮换**」：
 *     · 真值只写在 `.env`（已被 .gitignore 排除）；
 *     · 代码里只 `require('../../config/amap')`，不出现字面量；
 *     · 换 Key 只需改 `.env` 再跑一次本脚本。
 *
 * 用法：
 *   node scripts/miniprogram-amap-key.mjs           # 生成/覆盖 config/amap.js
 *   node scripts/miniprogram-amap-key.mjs --check   # 校验（CI / 上传前）
 *
 * 退出码：0 成功；1 缺少 AMAP_MINI_KEY 或 KEY 形态明显不对；2 传入未知参数。
 * 安全：脚本**不打印 Key**，只打印长度与前后 4 位。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..');
const envPath = path.join(repoRoot, '.env');
const outPath = path.join(repoRoot, 'miniprogram', 'config', 'amap.js');

const args = process.argv.slice(2);
const unknown = args.filter((a) => a !== '--check');
if (unknown.length) {
  console.error(`未知参数：${unknown.join(' ')}（只支持 --check）`);
  process.exit(2);
}
const checkOnly = args.includes('--check');

/** 解析 .env（不引 dotenv：只需要一个键，避免为一个字段加依赖） */
function readEnv(file) {
  if (!fs.existsSync(file)) return {};
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (!m) continue;
    let v = m[2].trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1);
    }
    out[m[1]] = v;
  }
  return out;
}

const env = readEnv(envPath);
const key = (env.AMAP_MINI_KEY ?? '').trim();

/** 高德 Key 是 32 位十六进制；形态明显不对时早失败，别等到小程序里才报错 */
const KEY_RE = /^[0-9a-f]{32}$/i;
if (!KEY_RE.test(key)) {
  console.error(
    key
      ? `AMAP_MINI_KEY 形态不对（应为 32 位十六进制，当前长度 ${key.length}）`
      : '.env 里没有 AMAP_MINI_KEY（或为空）。请到高德控制台复制「微信小程序」类型的 Key。',
  );
  process.exit(1);
}

const mask = (v) => `${v.slice(0, 4)}…${v.slice(-4)}（长度 ${v.length}）`;

if (checkOnly) {
  if (!fs.existsSync(outPath)) {
    console.error('config/amap.js 尚未生成，请先运行：node scripts/miniprogram-amap-key.mjs');
    process.exit(1);
  }
  const content = fs.readFileSync(outPath, 'utf8');
  if (!content.includes(key)) {
    console.error('config/amap.js 与 .env 的 AMAP_MINI_KEY 不一致，请重新生成');
    process.exit(1);
  }
  console.log(`OK：小程序 Key 已就位 ${mask(key)}`);
  process.exit(0);
}

const content = `/**
 * ★ 本文件由 scripts/miniprogram-amap-key.mjs 自动生成，**不要手改、不进 Git**。
 * 真值来源：仓库根 .env 的 AMAP_MINI_KEY（「微信小程序」类型）。
 * 换 Key：改 .env → 重新运行生成脚本；校验：node scripts/miniprogram-amap-key.mjs --check
 */
module.exports = {
  key: '${key}',
};
`;
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, content, 'utf8');
console.log(`已生成 miniprogram/config/amap.js：${mask(key)}`);
