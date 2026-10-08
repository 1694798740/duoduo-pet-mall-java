/**
 * E8 · 入口边界冒烟（entry scope smoke）
 *
 * <h2>验证什么</h2>
 * 「三平台独立部署」在**令牌层**的收窄是否真的生效：
 * <ul>
 *   <li>商城（8088）登录的令牌 ent=mall → 调后台管理面 /api/admin/** 被**网关 403**（不是 401）；</li>
 *   <li>后台（8090）登录的令牌 ent=admin → /api/admin/** 放行、/api/seller/** 被拒；</li>
 *   <li>商家（8091）登录的令牌 ent=merchant → /api/seller/** 放行、/api/admin/** 被拒；</li>
 *   <li>**刷新继承**：商城登录 → 刷新一次 → 新令牌仍是 mall（刷新不许把入口洗掉）；</li>
 *   <li>**向后兼容**：直连网关登录（不带入口头，即小程序与命令行夹具的形态）
 *       → 令牌不声明入口 → **不受入口限制**（运营账号调 /api/admin/** 仍 200）。</li>
 * </ul>
 *
 * <h2>为什么用运营账号做跨端访问</h2>
 * 入口 403 发生在**网关**（角色校验之前）。若用普通账号验证，
 * "网关入口拒绝"与"服务端角色拒绝"都是 403，分不清是谁拒的；
 * 用 OPERATOR 账号则角色校验必然放行 —— 剩下唯一的 403 来源就是入口边界，判据干净。
 *
 * <h2>定位提醒</h2>
 * 入口边界是纵深防御（收窄令牌适用范围），不是角色校验的替代品；
 * 直连网关可伪造入口头 —— 这些边界写在 duoduo-common 的 ClientEntry 类注释里。
 *
 * 前置：三站点容器 + 网关在跑；MySQL 在跑（夹具提权走库）。
 * 用法：node scripts/e8-entry-scope-smoke.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mysql } from './lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 三个站点走各自的 nginx（入口头由 nginx 注入 —— 这正是被测行为的一部分）。
 *  ★ Node 的解析器不认 `*.localhost`（浏览器按 RFC 6761 才认），服务端 fetch 一律用
 *    localhost:端口 —— 打到的是同一份 nginx 配置（server_name 含 localhost），入口注入不受影响。 */
const MALL = process.env.UI_BASE ?? 'http://localhost:8088';
const ADMIN = process.env.ADMIN_UI_BASE ?? 'http://localhost:8090';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://localhost:8091';

const PWD = 'duoduo12345';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`);
  }
}
const step = (t) => console.log(`\n> ${t}`);

/** 经 HTTP 调用并收集 Set-Cookie 到 jar */
async function call(method, url, { body, jar, headers } = {}) {
  const h = { ...(headers ?? {}) };
  if (body !== undefined) h['content-type'] = 'application/json';
  if (jar && jar.size) h.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch(url, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (jar) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const pair = c.split(';')[0];
      const i = pair.indexOf('=');
      if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
  }
  const raw = await res.text();
  let json = null;
  try { json = JSON.parse(raw); } catch { /* 网关 4xx 响应体也是 JSON，但兜底保留 raw */ }
  return { status: res.status, json, raw };
}

const randPhone = () => '137' + String(Math.floor(1e7 + Math.random() * 8e7));

// ---------------- 夹具：一个 OPERATOR 账号（随机手机号 + 库提权） ----------------
step('夹具：运营账号（随机手机号 + 库提权，收尾降权）');
const phone = randPhone();
const jarDirect = new Map(); // 直连网关登录用（无入口头）
const reg = await call('POST', `${GW}/api/auth/register`,
  { body: { phone, password: PWD, nickname: '入口边界-夹具' }, jar: jarDirect });
check('夹具账号注册成功', reg.status === 200, reg.status);

let promoted = false;
for (let i = 0; i < 5 && !promoted; i++) {
  await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
  promoted = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim() === 'OPERATOR';
}
check('夹具提权为 OPERATOR（否则正向用例全是服务端 403，判据被污染）', promoted, promoted);

// ---------------- ① 正向：三站点各自登录，本端专属路径放行 ----------------
step('① 后台站点（ent=admin）登录：/api/admin/** 放行');
const jarAdmin = new Map();
const adminLogin = await call('POST', `${ADMIN}/api/auth/login`,
  { body: { phone, password: PWD }, jar: jarAdmin });
check('后台站点登录成功', adminLogin.status === 200, adminLogin.status);
const adminUsers = await call('GET', `${ADMIN}/api/admin/users?page=1&pageSize=1`, { jar: jarAdmin });
check('★ ent=admin 调 /api/admin/users → 200（本端专属路径放行）',
  adminUsers.status === 200, { status: adminUsers.status, code: adminUsers.json?.code });

step('② 商家站点（ent=merchant）登录：/api/seller/** 放行、/api/admin/** 被拒');
const jarMerchant = new Map();
const merchantLogin = await call('POST', `${MERCHANT}/api/auth/login`,
  { body: { phone, password: PWD }, jar: jarMerchant });
check('商家站点登录成功', merchantLogin.status === 200, merchantLogin.status);
const sellerSummary = await call('GET', `${MERCHANT}/api/seller/summary`, { jar: jarMerchant });
check('★ ent=merchant 调 /api/seller/summary → 200',
  sellerSummary.status === 200, { status: sellerSummary.status, code: sellerSummary.json?.code });
const merchantToAdmin = await call('GET', `${MERCHANT}/api/admin/users?page=1`, { jar: jarMerchant });
check('★ ent=merchant 调 /api/admin/** → 403（网关入口拒绝）',
  merchantToAdmin.status === 403, { status: merchantToAdmin.status, code: merchantToAdmin.json?.code });

// ---------------- ③ 反向：商城令牌跨端被拒（E8 验收主断言） ----------------
step('③ 商城站点（ent=mall）登录：本端正常、跨端 403');
const jarMall = new Map();
const mallLogin = await call('POST', `${MALL}/api/auth/login`,
  { body: { phone, password: PWD }, jar: jarMall });
check('商城站点登录成功', mallLogin.status === 200, mallLogin.status);
const mallMe = await call('GET', `${MALL}/api/users/me`, { jar: jarMall });
check('ent=mall 调本端业务接口 /api/users/me → 200（正常功能不受影响）',
  mallMe.status === 200, { status: mallMe.status });
const mallToAdmin = await call('GET', `${MALL}/api/admin/users?page=1`, { jar: jarMall });
check('★★ 验收主断言：ent=mall（且角色是 OPERATOR）调 /api/admin/** → 403',
  mallToAdmin.status === 403, { status: mallToAdmin.status, code: mallToAdmin.json?.code });
const mallToSeller = await call('GET', `${MALL}/api/seller/summary`, { jar: jarMall });
check('★ ent=mall 调 /api/seller/** → 403',
  mallToSeller.status === 403, { status: mallToSeller.status });

// ---------------- ④ 刷新继承：刷新一次不许把入口洗掉 ----------------
step('④ 刷新继承：商城令牌刷新后仍是 mall');
const refreshed = await call('POST', `${MALL}/api/auth/refresh`, { jar: jarMall });
check('刷新成功', refreshed.status === 200, refreshed.status);
const afterRefresh = await call('GET', `${MALL}/api/admin/users?page=1`, { jar: jarMall });
check('★ 刷新后的令牌调 /api/admin/** → 仍 403（入口没被洗掉）',
  afterRefresh.status === 403, { status: afterRefresh.status });

// ---------------- ⑤ 向后兼容：直连网关（小程序/夹具形态）不受入口限制 ----------------
step('⑤ 直连网关登录（无入口头）：令牌不声明入口、不受限');
const directLogin = await call('POST', `${GW}/api/auth/login`,
  { body: { phone, password: PWD }, jar: jarDirect });
check('直连网关登录成功', directLogin.status === 200, directLogin.status);
const directAdmin = await call('GET', `${GW}/api/admin/users?page=1`, { jar: jarDirect });
check('★ 无 ent 声明的运营令牌调 /api/admin/** → 200（老令牌/小程序不被误伤）',
  directAdmin.status === 200, { status: directAdmin.status, code: directAdmin.json?.code });

// ---------------- ⑥ 401/403 语义分界 ----------------
step('⑥ 语义分界：无效令牌仍是 401，入口不匹配才是 403');
const bad = await call('GET', `${MALL}/api/admin/users?page=1`,
  { headers: { cookie: 'dd_at=not-a-real-token' } });
check('无效令牌 → 401（前端据此走刷新/登录，不与入口拒绝混淆）',
  bad.status === 401, { status: bad.status });

// ---------------- 收尾：夹具降权 ----------------
step('收尾：夹具账号降权（不留多余后台入口）');
await mysql('duoduo_user', `UPDATE t_user SET role='USER' WHERE phone='${phone}';`);
const roleAfter = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
check('★ 收尾：夹具已降权回 USER', roleAfter === 'USER', roleAfter);

console.log(`\n=== E8 入口边界冒烟：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(failures.length ? 1 : 0);
