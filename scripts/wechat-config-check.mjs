/**
 * 微信小程序登录配置自检（不发真实消息、不依赖容器）。
 *
 * 为什么需要它：2026-10-20 的事故根因是「AppID 与 AppSecret 不匹配」，
 * 但小程序侧只看到 401，很容易误判成登录态问题。这条命令 3 秒给出结论：
 *   node scripts/wechat-config-check.mjs
 *
 * 判定口径：
 *   · 用 AppID + AppSecret 调 /cgi-bin/token（微信官方「换 access_token」接口）——
 *     配对正确返回 access_token；不匹配返回 40125 invalid appsecret；
 *     AppID 错返回 40013；服务器 IP 不在白名单返回 40164。
 *   · 同时提示 mock 通道是否开启（本地/CI 可用 mock:<openid> 走通流程）。
 *
 * 退出码：0 配对正常；1 不匹配/未配置；2 网络或未知响应。
 * 安全：只打印 AppID 末 4 位与密钥长度，绝不打印密钥本身。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.join(here, '..', '.env');

function readEnv(file) {
  if (!fs.existsSync(file)) return {};
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (!m) continue;
    let v = m[2].trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    out[m[1]] = v;
  }
  return out;
}

const env = readEnv(envPath);
const appid = (env.WECHAT_APPID ?? '').trim();
const secret = (env.WECHAT_SECRET ?? '').trim();
const mock = (env.DUODUO_WECHAT_MOCK_ENABLED ?? '').trim();

const mask = (v) => `${v.slice(0, 6)}…${v.slice(-4)}`;

console.log('微信登录配置自检');
console.log(`  AppID : ${appid ? `${mask(appid)}（长度 ${appid.length}）` : '（未配置）'}`);
console.log(`  Secret: ${secret ? `长度 ${secret.length}` : '（未配置）'}`);
console.log(`  mock 通道: ${mock === '' ? '（未显式设置，compose 默认 true）' : mock}`);

if (!appid || !secret) {
  console.error('\n结果：未配置 —— 真实微信登录不可用（服务端会返回 503）。');
  process.exit(1);
}

try {
  const url = `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${encodeURIComponent(appid)}&secret=${encodeURIComponent(secret)}`;
  const res = await fetch(url);
  const body = await res.json();
  if (body.access_token) {
    console.log(`\n结果：✅ 配对正常（已取得 access_token，长度 ${String(body.access_token).length}）`);
    process.exit(0);
  }
  const errcode = body.errcode;
  const errmsg = body.errmsg ?? '';
  console.error(`\n结果：❌ 配对失败 errcode=${errcode} errmsg=${errmsg}`);
  if (errcode === 40125 || /appsecret/i.test(errmsg)) {
    console.error('  → AppSecret 与 AppID 不匹配。到微信公众平台 → 开发管理 → 开发设置 → 重置 AppSecret，');
    console.error('    把新值写进 .env 的 WECHAT_SECRET，然后 docker compose up -d duoduo-user-app。');
  } else if (errcode === 40013) {
    console.error('  → AppID 无效：检查 .env 的 WECHAT_APPID 是否为该小程序 AppID。');
  } else if (errcode === 40164) {
    console.error('  → 服务器公网 IP 不在微信白名单（本地开发通常不涉及；部署到服务器后需在后台添加）。');
  }
  process.exit(1);
} catch (e) {
  console.error(`\n结果：无法连接微信接口（${e.message}），请检查网络/代理。`);
  process.exit(2);
}
