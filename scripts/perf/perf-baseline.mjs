/**
 * 压测基线（I-09 · 2026-10-26）—— 零依赖、可重复、带合格线断言。
 *
 * <h2>为什么要写一个新的，而不是复用 A12 的 JMeter 计划</h2>
 * A12 测的是「下单-支付-发货-确认」整条链路在阶梯加压下的容量拐点（100 线程 × 300s），
 * 目的是找上限；本轮要回答的是另一个问题：**改了这些功能之后，本地基线还合不合格**。
 * 那个问题需要的是「短、快、有阈值、能进回归」，所以这里用 Node 内置 fetch 写一个
 * 不依赖任何外部工具（JMeter/k6 本机都没有）的轻量压测。
 *
 * <h2>合格线（按本机单机 Docker 环境定的，不看理想模型）</h2>
 * <ul>
 *   <li><b>读接口</b>（商品列表 / 商品详情 / 我的订单）：P95 ≤ 300ms、P99 ≤ 800ms、错误率 ≤ 0.5%；</li>
 *   <li><b>写前链路</b>（结算预览：跨 trade + product + marketing IP 内部调用，但不落库）：
 *       P95 ≤ 800ms、P99 ≤ 2000ms、错误率 ≤ 0.5%；</li>
 *   <li><b>任何 5xx 一律不合格</b>（5xx 是系统错误，不能混进「错误率 0.5%」里被平均掉）。</li>
 * </ul>
 * 依据：A12 实测本地读路径稳定 10-22ms、下单 P95 在 100 线程下 1029ms。
 * 读路径给 300ms 是留 10 倍余量；预览按 A12「下单 P95 的 1/2」定 800ms，
 * 既不会因为一次 GC 就红，也能拦住「新增一次跨服务调用把预览拖慢一倍」这类退化。
 *
 * <h2>用法</h2>
 *   node scripts/perf/perf-baseline.mjs
 *   BASE=http://localhost:8080 CONCURRENCY=30 DURATION=20000 node scripts/perf/perf-baseline.mjs
 *
 * 退出码：0 = 全部达标；1 = 有场景越线（CI 可直接用）。
 */

const BASE = process.env.BASE ?? 'http://127.0.0.1:8080';
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 20);
const DURATION_MS = Number(process.env.DURATION ?? 15000);
const WARMUP_MS = Number(process.env.WARMUP ?? 2000);
const PHONE = process.env.PERF_PHONE ?? '13600003333';
const PASSWORD = process.env.PERF_PASSWORD ?? 'duoduo12345';

const THRESHOLDS = {
  read: { p95: 300, p99: 800, errorRate: 0.005 },
  write: { p95: 800, p99: 2000, errorRate: 0.005 },
};

let cookie = '';
let productId = null;

async function call(path, options = {}) {
  const started = performance.now();
  let status = 0;
  try {
    const res = await fetch(`${BASE}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        // ★ 只有明确需要登录的场景才带 Cookie：商家登录态自带 X-Tenant-Id，
        //   而商品详情会按租户过滤 —— 带上它去读平台自营商品会得到 404，
        //   压出来的错误率是假象（本轮实测踩到：详情 100% 404，误判成接口退化）。
        ...(options.withAuth && cookie ? { Cookie: cookie } : {}),
        ...(options.headers ?? {}),
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    status = res.status;
    // 必须读完响应体：只读 header 就返回会让连接一直挂着，压出来的是连接复用问题
    await res.arrayBuffer();
    return { status, ms: performance.now() - started };
  } catch (err) {
    return { status: status || -1, ms: performance.now() - started, error: String(err && err.message) };
  }
}

function percentile(sorted, p) {
  if (sorted.length === 0) {
    return 0;
  }
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

async function scenario(name, kind, path, options) {
  const latencies = [];
  let errors = 0;
  let serverErrors = 0;
  let count = 0;
  const statuses = {};
  const deadline = Date.now() + WARMUP_MS + DURATION_MS;
  const measureAfter = Date.now() + WARMUP_MS;

  const worker = async () => {
    while (Date.now() < deadline) {
      const r = await call(path, options);
      statuses[r.status] = (statuses[r.status] ?? 0) + 1;
      const measuring = Date.now() >= measureAfter;
      if (r.status < 200 || r.status >= 300) {
        errors++;
        if (r.status >= 500 || r.status === -1) {
          serverErrors++;
        }
      }
      if (measuring) {
        count++;
        latencies.push(r.ms);
      }
    }
  };

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  const sorted = latencies.sort((a, b) => a - b);
  const errorRate = count === 0 ? 1 : errors / Math.max(1, count + errors);
  const limit = THRESHOLDS[kind];
  const p50 = percentile(sorted, 50);
  const p95 = percentile(sorted, 95);
  const p99 = percentile(sorted, 99);
  const ok =
    serverErrors === 0 && errorRate <= limit.errorRate && p95 <= limit.p95 && p99 <= limit.p99;

  return {
    name,
    kind,
    count,
    rps: (count / (DURATION_MS / 1000)).toFixed(1),
    p50: p50.toFixed(0),
    p95: p95.toFixed(0),
    p99: p99.toFixed(0),
    errorRate: (errorRate * 100).toFixed(2) + '%',
    serverErrors,
    statuses,
    ok,
    limit,
  };
}

async function login() {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: PHONE, password: PASSWORD }),
  });
  const raw = res.headers.get('set-cookie') ?? '';
  // 只取 dd_at：其余 cookie（刷新令牌）在压测里用不到，多带反而让每次请求多一层校验
  const match = raw.match(/dd_at=[^;]+/);
  if (res.status !== 200 || !match) {
    throw new Error(`压测账号登录失败（${res.status}）：${await res.text()}`);
  }
  cookie = match[0];
}

async function pickProductId() {
  const res = await fetch(`${BASE}/api/products?page=1&pageSize=1`);
  const body = await res.json();
  const rows = body?.data?.items ?? body?.data?.list ?? body?.data?.records ?? [];
  if (!rows.length) {
    throw new Error('商品列表为空，无法压测商品详情（先确保种子商品存在）');
  }
  productId = String(rows[0].id);
}

const main = async () => {
  console.log(`压测目标 ${BASE} ｜ 并发 ${CONCURRENCY} ｜ 每场景 ${DURATION_MS / 1000}s（另有 ${WARMUP_MS / 1000}s 预热不计入）`);
  await login();
  await pickProductId();
  console.log(`登录账号 ${PHONE} ｜ 详情样本商品 ${productId}`);
  console.log('');

  const results = [];
  results.push(await scenario('商品列表（公开读）', 'read', '/api/products?page=1&pageSize=20', { withAuth: false }));
  results.push(await scenario('商品详情（公开读）', 'read', `/api/products/${productId}`, { withAuth: false }));
  results.push(
    await scenario('我的订单（登录读）', 'read', '/api/orders?page=1&pageSize=10', { withAuth: true }),
  );
  results.push(
    await scenario('结算预览（跨域写前链路）', 'write', '/api/orders/preview', {
      method: 'POST',
      body: { buySkuId: 2001, buyQuantity: 1 },
      withAuth: true,
    }),
  );

  console.log('场景                         类型  请求数   RPS    P50    P95    P99   错误率   5xx  状态码分布        判定');
  for (const r of results) {
    const dist = Object.entries(r.statuses)
      .map(([k, v]) => `${k}×${v}`)
      .join(' ');
    console.log(
      `${r.name.padEnd(26)} ${r.kind.padEnd(6)} ${String(r.count).padStart(6)} ${r.rps.padStart(6)} ` +
        `${r.p50.padStart(5)}ms ${r.p95.padStart(5)}ms ${r.p99.padStart(5)}ms ${r.errorRate.padStart(6)} ` +
        `${String(r.serverErrors).padStart(4)}  ${dist.padEnd(16)} ${r.ok ? 'PASS' : 'FAIL'}`,
    );
  }

  const failed = results.filter((r) => !r.ok);
  if (failed.length) {
    console.log('');
    console.log('未达标场景（合格线：读 P95≤300/P99≤800，写 P95≤800/P99≤2000，错误率≤0.5%，5xx=0）：');
    for (const r of failed) {
      console.log(
        `  - ${r.name}：P95 ${r.p95}ms / P99 ${r.p99}ms / 错误率 ${r.errorRate} / 5xx ${r.serverErrors}`,
      );
    }
    process.exit(1);
  }
  console.log('');
  console.log('全部达标（本地基线合格）。');
};

main().catch((err) => {
  console.error('压测执行失败：', err.message ?? err);
  process.exit(1);
});
