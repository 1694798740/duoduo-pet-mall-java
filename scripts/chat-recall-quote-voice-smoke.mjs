/**
 * 撤回 / 引用 / 语音 冒烟（2026-10-24）。
 *
 * 守九件事：
 *  撤回：① 买家撤回自己 2 分钟内的消息成功，内容被抹掉并留痕；
 *        ② 客服（对端）在 WS 上收到 RECALL 信号；
 *        ③ 非发送方撤回被拒（403/业务错误）；
 *        ④ 撤回幂等（重复撤回返回成功）。
 *  引用：⑤ 发送带 replyToId → 会话里该消息带服务端拼的 quoted 摘要；
 *        ⑥ 引用别家会话的消息被拒。
 *  语音：⑦ 上传 aac 音频成功；
 *        ⑧ 发 VOICE（durationMs 合法）→ 会话里带 mediaUrl 与时长；
 *        ⑨ durationMs 超过 60 秒被拒。
 *
 * 用法：NO_PROXY="*" node scripts/chat-recall-quote-voice-smoke.mjs
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
    const hit = [...conn.events].reverse().find((e) => e.type === type);
    if (hit) return hit;
    await sleep(20);
  }
  return null;
}

/** 上传一个 aac 音频（内容随意，端点只校验扩展名 + MIME） */
async function uploadVoice(token) {
  const form = new FormData();
  form.append('file', new Blob([Buffer.alloc(256, 7)], { type: 'audio/aac' }), 'voice-smoke.aac');
  const res = await fetch(API + '/api/uploads', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: form,
  });
  const body = await res.json().catch(() => null);
  return { status: res.status, body, url: body?.data?.url || body?.data?.publicUrl || body?.data?.key || null };
}

async function main() {
  console.log('== 0. 准备账号与会话 ==');
  const stamp = String(Date.now()).slice(-8);
  const buyerPhone = '197' + stamp;
  await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '撤引语冒烟' });
  const buyerToken = await login(buyerPhone);
  const csToken = await login(CS);
  check('买家与官方客服登录成功', Boolean(buyerToken && csToken));
  if (!buyerToken || !csToken) return 1;

  await call('POST', '/api/support/messages', { content: '撤引语冒烟起点' }, buyerToken);
  const session = (await call('GET', '/api/support/session', null, buyerToken)).body?.data;
  const sessionId = session?.id;
  check('客服会话已建立', Boolean(sessionId), session);
  if (!sessionId) return 1;

  const buyerConn = await connect(await ticket(buyerToken));
  const csConn = await connect(await ticket(csToken));
  check('双方 WS 建连 READY', buyerConn.events.some((e) => e.type === 'READY') && csConn.events.some((e) => e.type === 'READY'));

  console.log('\n== 1. 撤回（买家侧） ==');
  const sent = (await call('POST', '/api/support/messages', { content: '撤回目标消息 ' + stamp }, buyerToken)).body?.data;
  check('买家发送目标消息成功', Boolean(sent?.id), sent);
  const recall = await call('POST', `/api/support/messages/${sent.id}/recall`, null, buyerToken);
  check('买家撤回成功（2 分钟窗口内）', recall.status === 200, recall);
  check('撤回后内容被抹成占位符', recall.body?.data?.content === '[消息已撤回]' && !recall.body?.data?.mediaUrl, recall.body?.data);

  const recallEvent = await waitEvent(csConn, 'RECALL');
  check('客服在 WS 收到 RECALL 信号', Boolean(recallEvent), recallEvent);
  check('RECALL 带正确的会话与消息 id',
    String(recallEvent?.sessionId) === String(sessionId) && String(recallEvent?.messageId) === String(sent.id),
    recallEvent);

  const again = await call('POST', `/api/support/messages/${sent.id}/recall`, null, buyerToken);
  check('重复撤回幂等（仍返回成功）', again.status === 200, again);

  const threadAfter = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  const recalledInThread = threadAfter?.items?.find((m) => String(m.id) === String(sent.id));
  check('会话里该消息已留痕为已撤回', Boolean(recalledInThread?.recalledAt) && recalledInThread?.content === '[消息已撤回]', recalledInThread);

  console.log('\n== 2. 撤回越权 ==');
  const other = (await call('POST', '/api/support/messages', { content: '客服发送消息 ' + stamp }, buyerToken)).body?.data; // 占位
  // 客服发一条，买家尝试撤客服的消息 → 必须被拒
  const csSend = (await call('POST', `/api/seller/support/sessions/${sessionId}/messages`, { content: '客服发的，别乱撤' }, csToken)).body?.data;
  check('客服发送成功', Boolean(csSend?.id), csSend);
  const badRecall = await call('POST', `/api/support/messages/${csSend.id}/recall`, null, buyerToken);
  check('买家撤客服的消息被拒', badRecall.status === 403 || (badRecall.body?.code && badRecall.body.code !== 0), badRecall);
  const outsiderPhone = '197' + String(Date.now() + 11).slice(-8);
  await call('POST', '/api/auth/register', { phone: outsiderPhone, password: PWD, nickname: '越权撤回' });
  const outsiderToken = await login(outsiderPhone);
  const badRecall2 = await call('POST', `/api/support/messages/${csSend.id}/recall`, null, outsiderToken);
  check('无关用户撤回被拒', badRecall2.status === 403 || (badRecall2.body?.code && badRecall2.body.code !== 0), badRecall2);

  console.log('\n== 3. 引用 ==');
  const anchor = (await call('POST', '/api/support/messages', { content: '这是被引用的原文，请看这句话很关键' }, buyerToken)).body?.data;
  const quoted = (await call('POST', '/api/support/messages',
    { content: '就这条回复您', replyToId: anchor.id }, buyerToken)).body?.data;
  check('带 replyToId 发送成功', Boolean(quoted?.id), quoted);
  check('新消息带 replyToId', String(quoted?.replyToId) === String(anchor.id), quoted?.replyToId);
  // 摘要在读取路径（thread）拼装，发送响应里 quoted 恒为 null —— 断言取会话视图
  const qThread = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  const qView = qThread?.items?.find((m) => String(m.id) === String(quoted.id));
  check('服务端拼出引用摘要（会话视图）', qView?.quoted?.excerpt?.includes('被引用的原文') === true, qView?.quoted);

  const mediaAnchor = (await call('POST', '/api/support/messages',
    { content: '[图片]', type: 'IMAGE', mediaUrl: 'https://example.com/pic.jpg' }, buyerToken)).body?.data;
  const quoteMedia = (await call('POST', '/api/support/messages',
    { content: '收到图片了', replyToId: mediaAnchor.id }, buyerToken)).body?.data;
  const qThread2 = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  const qMediaView = qThread2?.items?.find((m) => String(m.id) === String(quoteMedia.id));
  check('引用媒体消息摘要为 [图片] 占位（会话视图）', qMediaView?.quoted?.excerpt === '[图片]', qMediaView?.quoted);

  // 客服侧引用买家消息
  const csQuoted = (await call('POST', `/api/seller/support/sessions/${sessionId}/messages`,
    { content: '客服已看到', replyToId: anchor.id }, csToken)).body?.data;
  const csThreadQ = (await call('GET', `/api/seller/support/sessions/${sessionId}/messages?page=1&pageSize=50`, null, csToken)).body?.data;
  const csQView = csThreadQ?.items?.find((m) => String(m.id) === String(csQuoted.id));
  check('客服引用买家消息成功且带摘要（会话视图）', Boolean(csQView?.quoted?.excerpt), csQView?.quoted);

  // 跨会话引用： outsider 自己建会话，拿 buyer 会话里的消息 id 当 replyToId
  await call('POST', '/api/support/messages', { content: '局外会话' }, outsiderToken);
  const cross = await call('POST', '/api/support/messages', { content: '想引用别人的', replyToId: anchor.id }, outsiderToken);
  check('跨会话引用被拒', cross.status === 400 || (cross.body?.code && cross.body.code !== 0), cross);

  console.log('\n== 4. 语音 ==');
  const up = await uploadVoice(buyerToken);
  check('aac 音频上传成功', up.status === 200 && Boolean(up.url), up.body);
  if (!up.url) {
    buyerConn.ws.close();
    csConn.ws.close();
    await sleep(300);
    console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
    return 1;
  }

  const voice = (await call('POST', '/api/support/messages',
    { content: '[语音]', type: 'VOICE', mediaUrl: up.url, durationMs: 3200 }, buyerToken)).body?.data;
  check('发 VOICE 成功且带 mediaUrl', Boolean(voice?.id) && Boolean(voice?.mediaUrl), voice);

  const badVoice = await call('POST', '/api/support/messages',
    { content: '[语音]', type: 'VOICE', mediaUrl: up.url, durationMs: 61000 }, buyerToken);
  check('durationMs 超 60 秒被拒', badVoice.status === 400 || (badVoice.body?.code && badVoice.body.code !== 0), badVoice);

  const noDur = await call('POST', '/api/support/messages',
    { content: '[语音]', type: 'VOICE', mediaUrl: up.url }, buyerToken);
  check('VOICE 缺 durationMs 被拒', noDur.status === 400 || (noDur.body?.code && noDur.body.code !== 0), noDur);

  const thread = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  const voiceInThread = thread?.items?.find((m) => String(m.id) === String(voice.id));
  check('会话里 VOICE 消息带时长', voiceInThread?.durationMs === 3200, voiceInThread?.durationMs);

  // 客服视角也看得到
  const csThread = (await call('GET', `/api/seller/support/sessions/${sessionId}/messages?page=1&pageSize=50`, null, csToken)).body?.data;
  const csSeesVoice = csThread?.items?.find((m) => String(m.id) === String(voice.id));
  check('客服侧能看到同一条 VOICE', Boolean(csSeesVoice?.mediaUrl), csSeesVoice?.mediaUrl);

  buyerConn.ws.close();
  csConn.ws.close();
  await sleep(300);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();
