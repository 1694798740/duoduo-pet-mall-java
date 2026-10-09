/**
 * E17 · 签发一枚「老令牌」（没有 pv 声明的访问令牌），用于兼容窗口的端到端验证。
 *
 * 为什么需要一个 Node 脚本来造令牌：后端所有签发路径（登录 / 刷新 / 重签）现在**都会**写上
 * 权限版本，所以"不带 pv 的令牌"在生产代码里已经造不出来了。但它是真实存在的一类令牌 ——
 * E17 上线那一刻，线上所有在线用户手上拿的都是它。如果网关把它们一律判成"版本过期"，
 * 上线当天就是全站掉线（这正是本机制里最危险的一条失败路径）。
 *
 * 于是这里按 JWT 规范**独立实现**一次签名：不依赖 jjwt，也就不可能"照着实现抄错"。
 * 顺带还证明了令牌格式是对外可复现的，而不是只有 Java 侧能造。
 *
 * 用法：
 *   node scripts/lib/legacy-jwt.mjs --alg-from <本部署签发的任一真实令牌> --sub <userId> [--role USER] [--phone 139...] [--ttl 900]
 *
 * 输出：一行令牌（其余诊断信息走 stderr，方便直接 `$(...)` 取用）。
 */
import crypto from 'node:crypto';

const DEFAULT_SECRET = 'duoduo-local-dev-secret-please-change-in-production-32b';

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    if (!key.startsWith('--')) throw new Error(`无法识别的参数：${key}`);
    out[key.slice(2)] = argv[i + 1];
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
if (!args.sub) {
  console.error('缺少 --sub <userId>');
  process.exit(2);
}

/**
 * 签名算法**从一枚真实令牌的头部读出来**，不写死。
 *
 * 为什么不写死 HS256：jjwt 的 `signWith(key)` 会按密钥长度自动挑最强的算法 ——
 * 本项目默认密钥是 54 字节（432 bit），落在 HS384 的区间里。写死 HS256 会得到一个
 * "header 是 HS256、但服务端按 HS384 验签"的令牌 → 一律验签失败（401）。
 * 那种失败看起来像"网关拒绝老令牌"，会把兼容性问题误报成兼容性问题以外的结论。
 */
function algFromToken(token) {
  const header = JSON.parse(Buffer.from(String(token).split('.')[0], 'base64url').toString('utf8'));
  const alg = String(header.alg || '').toUpperCase();
  if (!/^HS(256|384|512)$/.test(alg)) throw new Error(`不支持的签名算法：${header.alg}`);
  return alg;
}

const b64url = (obj) => Buffer.from(JSON.stringify(obj), 'utf8').toString('base64url');

const alg = args['alg-from'] ? algFromToken(args['alg-from']) : 'HS256';
const secret = process.env.DUODUO_JWT_SECRET || DEFAULT_SECRET;

const now = Math.floor(Date.now() / 1000);
const ttl = Number(args.ttl || 900);
const claims = {
  sub: String(args.sub),
  type: 'access',
  phone: args.phone || '',
  role: (args.role || 'USER').toUpperCase(),
  rn: true,
  iat: now,
  exp: now + ttl,
};
/*
 * pv 是**可选**的，而且这个可选性正是本脚本的用法：
 *   · 不传 --pv → 造出"老令牌"（没有版本声明）→ 网关不校验，放行；
 *   · 传 --pv   → 造出"带版本的令牌"（可以故意给一个落后/超前的值）→ 网关按版本判。
 * 同一份代码、同一个密钥、只差一个声明，于是"401 与放行"的差异只可能来自 pv ——
 * 比另找一枚旧令牌来当对照干净得多（否则差异可能来自签名、过期时间、角色等别的东西）。
 */
if (args.pv !== undefined) {
  claims.pv = Number(args.pv);
}
// 需要时也不写 ent —— 老令牌同样可能没有入口声明，不带 ent 才更贴近真实的老令牌。

const signingInput = `${b64url({ alg, typ: 'JWT' })}.${b64url(claims)}`;
const signature = crypto
  .createHmac(`sha${alg.slice(2)}`, Buffer.from(secret, 'utf8'))
  .update(signingInput)
  .digest('base64url');

console.error(`[legacy-jwt] alg=${alg} sub=${claims.sub} role=${claims.role} pv=${args.pv === undefined ? '无（老令牌）' : claims.pv}`);
console.log(`${signingInput}.${signature}`);
