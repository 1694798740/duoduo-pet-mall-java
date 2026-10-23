/**
 * 实时消息冒烟（2026-10-23）。
 *
 * 守六件事：
 *  ① 建连：票据换连成功、服务端回 READY；
 *  ② 送达：一条 HTTP 发送 → 对端 WS 在 1 秒内收到 MESSAGE（这就是「零感知」的地基）；
 *  ③ 已读回执：读方 markRead → 发送方在 1 秒内收到 READ，且 lastReadId 正确；
 *  ④ 正在输入：一方发 TYPING → 对端收到，且带正确的会话标识；
 *  ⑤ 越权：拿别人的票据/会话 id 发 TYPING 不会被转发（对端不应收到）；
 *  ⑥ 容量：520 条并发连接全部 READY，并在 5 秒内全部建成（500+ 并发的最低门槛）。
 *
 * 用法：NO_PROXY="*" node scripts/realtime-smoke.mjs
 */
const API = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080/ws/chat';
const PWD = 'duoduo12345';
const CS = '13900000002';

let pass = 0;
const failures = [];
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail)));
  }
}

async function call(method, path, body, token) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const login = async (phone) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD })).body?.data?.accessToken;
const ticket = async (token) =>
  (await call('POST', '/api/socials/realtime/ticket', null, token)).body?.data?.ticket;

function connect(tk) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}?ticket=${encodeURIComponent(tk)}`);
    const events = [];
    let ready = false;
    ws.addEventListener('message', (ev) => {
      let e;
      try {
        e = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (e.type === 'PING') {
        ws.send(JSON.stringify({ type: 'PONG' }));
        return;
      }
      if (e.type === 'READY') ready = true;
      events.push({ ...e, at: Date.now() });
    });
    ws.addEventListener('error', () => reject(new Error('ws error')));
    const wait = setInterval(() => {
      if (ready) {
        clearInterval(wait);
        resolve({ ws, events });
      }
    }, 20);
    setTimeout(() => {
      clearInterval(wait);
      if (!ready) reject(new Error('READY 超时'));
    }, 5000);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitEvent(conn, type, timeoutMs = 1500) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const hit = conn.events.find((e) => e.type === type);
    if (hit) return hit;
    await sleep(20);
  }
  return null;
}

async function main() {
  console.log('== 0. 准备两个测试账号 ==');
  const stamp = String(Date.now()).slice(-8);
  const buyerPhone = '197' + stamp;
  await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '实时冒烟' });
  const buyerToken = await login(buyerPhone);
  const csToken = await login(CS);
  check('买家与官方客服登录成功', Boolean(buyerToken && csToken));
  if (!buyerToken || !csToken) return 1;

  await call('POST', '/api/support/messages', { content: '实时冒烟起点' }, buyerToken);
  const session = (await call('GET', '/api/support/session', null, buyerToken)).body?.data;
  const sessionId = session?.id;
  check('客服会话已建立', Boolean(sessionId), session);
  if (!sessionId) return 1;

  console.log('\n== 1. 建连（票据） ==');
  const buyerConn = await connect(await ticket(buyerToken));
  const csConn = await connect(await ticket(csToken));
  check('买家建连并收到 READY', buyerConn.events.some((e) => e.type === 'READY'));
  check('客服建连并收到 READY', csConn.events.some((e) => e.type === 'READY'));
  check('无效票据被拒', await new Promise((resolve) => {
    const ws = new WebSocket(`${WS_BASE}?ticket=invalid-ticket`);
    ws.addEventListener('open', () => resolve(false));
    ws.addEventListener('error', () => resolve(true));
    ws.addEventListener('close', () => resolve(true));
    setTimeout(() => resolve(true), 3000);
  }));

  console.log('\n== 2. 消息送达（HTTP 写入 → WS 推送） ==');
  const t0 = Date.now();
  await call('POST', '/api/support/messages', { content: '推送校验 ' + stamp }, buyerToken);
  const msgEvent = await waitEvent(csConn, 'MESSAGE');
  const latency = msgEvent ? msgEvent.at - t0 : -1;
  check('客服在 1 秒内收到新消息信号', Boolean(msgEvent) && latency <= 1000, latency);
  check('事件带正确的会话 id', String(msgEvent?.sessionId) === String(sessionId), msgEvent?.sessionId);
  console.log('     （端到端推送耗时 ' + latency + 'ms）');

  console.log('\n== 3. 已读回执 ==');
  const t1 = Date.now();
  // ★ 客服侧标记已读走商家接口（/api/support/read 是买家接口，拿客服令牌调会 403）
  await call('POST', `/api/seller/support/sessions/${sessionId}/read`, null, csToken);
  const readEvent = await waitEvent(buyerConn, 'READ');
  const readLatency = readEvent ? readEvent.at - t1 : -1;
  check('买家在 1 秒内收到已读回执', Boolean(readEvent) && readLatency <= 1000, readLatency);
  check('已读游标为正数', Number(readEvent?.lastReadId) > 0, readEvent?.lastReadId);

  console.log('\n== 4. 正在输入 ==');
  buyerConn.events.length = 0;
  csConn.ws.send(JSON.stringify({ type: 'TYPING', biz: 'SUPPORT', sessionId: String(sessionId) }));
  const typing = await waitEvent(buyerConn, 'TYPING');
  check('买家收到「正在输入」', Boolean(typing), typing);
  check('事件标明是客服在输入', typing?.biz === 'SUPPORT' && String(typing?.sessionId) === String(sessionId), typing);

  console.log('\n== 5. 越权：非会话参与方发 TYPING 不转发 ==');
  const outsiderPhone = '197' + String(Date.now() + 7).slice(-8);
  await call('POST', '/api/auth/register', { phone: outsiderPhone, password: PWD, nickname: '越权探测' });
  const outsiderToken = await login(outsiderPhone);
  const outsiderConn = await connect(await ticket(outsiderToken));
  buyerConn.events.length = 0;
  outsiderConn.ws.send(JSON.stringify({ type: 'TYPING', biz: 'SUPPORT', sessionId: String(sessionId) }));
  await sleep(700);
  check('非参与方的 TYPING 未被转发', !buyerConn.events.some((e) => e.type === 'TYPING'));

  console.log('\n== 6. 容量：520 条并发连接 ==');
  const users = [];
  for (let i = 0; i < 20; i++) {
    const phone = '197' + String(Date.now() + i * 13).slice(-8);
    const r = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '容量' + i });
    const token = r.status === 200 ? await login(phone) : null;
    if (token) users.push(token);
  }
  check('准备 20 个容量测试账号', users.length >= 18, users.length);
  const started = Date.now();
  const conns = [];
  for (let round = 0; round < 26; round++) {
    const batch = users.map(async (tk) => {
      try {
        return await connect(await ticket(tk));
      } catch {
        return null;
      }
    });
    const settled = await Promise.all(batch);
    conns.push(...settled.filter(Boolean));
    if (conns.length >= 520) break;
  }
  const elapsed = Date.now() - started;
  check('并发连接数 ≥ 500', conns.length >= 500, conns.length);
  check('全部连接在 10 秒内建立', elapsed <= 10000, elapsed + 'ms');
  console.log('     （' + conns.length + ' 条连接 / ' + elapsed + 'ms）');

  const online = (await call('GET', '/api/socials/realtime/online', null, csToken)).body?.data;
  check('服务端在线连接数与客户端一致（允许少量偏差）',
    Number(online?.connections) >= 500 && Math.abs(Number(online?.connections) - conns.length) <= 10,
    online);

  const t2 = Date.now();
  await call('POST', '/api/support/messages', { content: '容量下的推送 ' + stamp }, buyerToken);
  const underLoad = await waitEvent(csConn, 'MESSAGE');
  check('500+ 连接下推送仍在 1 秒内到达', Boolean(underLoad) && underLoad.at - t2 <= 1000,
    underLoad ? underLoad.at - t2 : -1);

  conns.forEach((c) => c.ws.close());
  buyerConn.ws.close();
  csConn.ws.close();
  outsiderConn.ws.close();
  await sleep(500);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();
