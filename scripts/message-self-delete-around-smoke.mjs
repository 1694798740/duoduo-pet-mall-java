/**
 * 单向删除 + aroundId 定位 冒烟（2026-10-24，第 92 轮）。
 *
 * 守九件事：
 *  客服侧：① 买家单向删除 2 条 → 买家会话不见、客服仍可见；
 *          ② 重复删除幂等；
 *          ③ 客服删买家一条 → 客服不见、买家仍可见（双向各管各的视角）；
 *          ④ aroundId 窗口：买家与客服两侧都能返回包含目标消息的一页（正序）。
 *  私信侧：⑤ A 删自己发的 → A 不见、B 仍可见；
 *          ⑥ B 删 A 发的（对方视角）→ B 不见、A 仍可见；
 *          ⑦ 幂等；⑧ aroundId 窗口包含目标消息。
 *
 * 用法：NO_PROXY="*" node scripts/message-self-delete-around-smoke.mjs
 */
const API = 'http://localhost:8080';
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

const ids = (data) => (data?.items || []).map((m) => String(m.id));
const has = (data, id) => ids(data).includes(String(id));

async function main() {
  const stamp = String(Date.now()).slice(-8);

  console.log('== 0. 客服会话准备 ==');
  const buyerPhone = '197' + stamp;
  await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '单向删除冒烟' });
  const buyerToken = await login(buyerPhone);
  const csToken = await login(CS);
  check('买家与官方客服登录成功', Boolean(buyerToken && csToken));
  if (!buyerToken || !csToken) return 1;

  await call('POST', '/api/support/messages', { content: '起点 ' + stamp }, buyerToken);
  const sessionId = (await call('GET', '/api/support/session', null, buyerToken)).body?.data?.id;
  check('客服会话已建立', Boolean(sessionId));
  if (!sessionId) return 1;

  // 铺 8 条：偶数买家发、奇数客服发
  const sent = [];
  for (let i = 0; i < 8; i++) {
    const r = (await call('POST',
      i % 2 === 0 ? '/api/support/messages' : `/api/seller/support/sessions/${sessionId}/messages`,
      { content: `冒烟消息 ${i + 1} ${stamp}` },
      i % 2 === 0 ? buyerToken : csToken)).body?.data;
    sent.push(r);
  }
  check('8 条消息全部发出', sent.every((s) => s?.id), sent.map((s) => s?.id));

  console.log('\n== 1. 客服侧：买家单向删除 ==');
  const delIds = [sent[2].id, sent[3].id];
  const delRes = await call('POST', '/api/support/messages/delete', { messageIds: delIds }, buyerToken);
  check('买家批量删除 2 条成功', delRes.status === 200, delRes);

  const buyerThread = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  check('买家视角不再含这 2 条', !has(buyerThread, delIds[0]) && !has(buyerThread, delIds[1]), ids(buyerThread));
  check('买家视角其余消息仍在（含客服发的）', has(buyerThread, sent[0].id) && has(buyerThread, sent[1].id), ids(buyerThread));

  const csThread1 = (await call('GET', `/api/seller/support/sessions/${sessionId}/messages?page=1&pageSize=50`, null, csToken)).body?.data;
  check('客服视角仍能看到这 2 条（单向）', has(csThread1, delIds[0]) && has(csThread1, delIds[1]), ids(csThread1));

  const delAgain = await call('POST', '/api/support/messages/delete', { messageIds: delIds }, buyerToken);
  check('重复删除幂等（仍成功且计数 0）', delAgain.status === 200 && delAgain.body?.data === 0, delAgain.body);

  console.log('\n== 2. 客服侧：客服删买家的消息 ==');
  const delRes2 = await call('POST', `/api/seller/support/sessions/${sessionId}/messages/delete`,
    { messageIds: [sent[4].id] }, csToken);
  check('客服删除买家消息成功', delRes2.status === 200, delRes2);
  const csThread2 = (await call('GET', `/api/seller/support/sessions/${sessionId}/messages?page=1&pageSize=50`, null, csToken)).body?.data;
  const buyerThread2 = (await call('GET', '/api/support/messages?page=1&pageSize=50', null, buyerToken)).body?.data;
  check('客服视角不见该条', !has(csThread2, sent[4].id), ids(csThread2));
  check('买家视角仍可见该条（对方删除不影响我）', has(buyerThread2, sent[4].id), ids(buyerThread2));

  console.log('\n== 3. aroundId 定位（客服会话） ==');
  const target = sent[5].id;
  const aroundBuyer = (await call('GET', `/api/support/messages?page=1&pageSize=6&aroundId=${target}`, null, buyerToken)).body?.data;
  check('买家 aroundId 窗口包含目标消息', has(aroundBuyer, target), ids(aroundBuyer));
  const times = (aroundBuyer?.items || []).map((m) => m.createdAt || '');
  check('窗口内消息时间正序', times.every((t, i) => i === 0 || t >= times[i - 1]), times);
  const aroundCs = (await call('GET', `/api/seller/support/sessions/${sessionId}/messages?page=1&pageSize=6&aroundId=${target}`, null, csToken)).body?.data;
  check('客服 aroundId 窗口包含目标消息', has(aroundCs, target), ids(aroundCs));
  // 目标若已被对端删除，本人窗口里不该出现（可见性过滤对 aroundId 同样生效）
  const aroundHidden = (await call('GET', `/api/support/messages?page=1&pageSize=6&aroundId=${sent[2].id}`, null, buyerToken)).body?.data;
  check('买家窗口不漏出自己已删的消息', !has(aroundHidden, sent[2].id) && !has(aroundHidden, sent[3].id), ids(aroundHidden));

  console.log('\n== 4. 私信侧：单向删除 ==');
  const aPhone = '197' + String(Date.now() + 21).slice(-8);
  const bPhone = '197' + String(Date.now() + 42).slice(-8);
  await call('POST', '/api/auth/register', { phone: aPhone, password: PWD, nickname: '私信A' });
  await call('POST', '/api/auth/register', { phone: bPhone, password: PWD, nickname: '私信B' });
  const aToken = await login(aPhone);
  const bToken = await login(bPhone);
  check('私信双方登录成功', Boolean(aToken && bToken));
  if (!aToken || !bToken) return 1;

  const bId = (await call('GET', '/api/users/me', null, bToken)).body?.data?.id;
  const m1 = (await call('POST', '/api/socials/messages', { receiverId: bId, content: '私信第 1 条 ' + stamp }, aToken)).body?.data;
  check('A 发私信成功', Boolean(m1?.id), m1);
  if (!m1?.id) return 1;
  const aId = (await call('GET', '/api/users/me', null, aToken)).body?.data?.id;
  const m2r = (await call('POST', '/api/socials/messages', { receiverId: aId, content: '私信第 2 条（B 回）' }, bToken)).body?.data;
  check('B 回复成功', Boolean(m2r?.id), m2r);

  console.log('\n== 5. aroundId 定位（私信，趁删除前） ==');
  const aroundA = (await call('GET', `/api/socials/messages/conversations/${bId}?page=1&pageSize=6&aroundId=${m2r.id}`, null, aToken)).body?.data;
  check('A aroundId 窗口包含目标消息', has(aroundA, m2r.id), ids(aroundA));
  const aroundB2 = (await call('GET', `/api/socials/messages/conversations/${aId}?page=1&pageSize=6&aroundId=${m2r.id}`, null, bToken)).body?.data;
  check('B aroundId 窗口包含目标消息', has(aroundB2, m2r.id), ids(aroundB2));
  const timesS = (aroundA?.items || []).map((m) => m.createdAt || '');
  check('私信窗口内消息时间正序', timesS.every((t, i) => i === 0 || t >= timesS[i - 1]), timesS);

  console.log('\n== 6. 私信单向删除 ==');
  // A 删自己发的 m1
  const sd1 = await call('POST', '/api/socials/messages/delete', { messageIds: [m1.id] }, aToken);
  check('A 删除自己发的私信成功', sd1.status === 200, sd1);
  const aView = (await call('GET', `/api/socials/messages/conversations/${bId}?page=1&pageSize=20`, null, aToken)).body?.data;
  const bView = (await call('GET', `/api/socials/messages/conversations/${aId}?page=1&pageSize=20`, null, bToken)).body?.data;
  check('A 视角不见 m1', !has(aView, m1.id), ids(aView));
  check('B 视角仍可见 m1（单向）', has(bView, m1.id), ids(bView));

  // B 删 A 发的 m1（删对方的消息 = 只藏自己视角，A 早已自删故看 A 的 m2）
  const sd2 = await call('POST', '/api/socials/messages/delete', { messageIds: [m1.id] }, bToken);
  check('B 删除（A 发的）成功', sd2.status === 200, sd2);
  const aView2 = (await call('GET', `/api/socials/messages/conversations/${bId}?page=1&pageSize=20`, null, aToken)).body?.data;
  check('B 的删除不影响 A：A 仍可见自己未删的 m2', has(aView2, m2r.id), ids(aView2));

  // B 删自己发的 m2 → A 仍可见 m2（「对方删除不影响我」）
  const sd3 = await call('POST', '/api/socials/messages/delete', { messageIds: [m2r.id] }, bToken);
  check('B 删除自己发的 m2 成功', sd3.status === 200, sd3);
  const aView3 = (await call('GET', `/api/socials/messages/conversations/${bId}?page=1&pageSize=20`, null, aToken)).body?.data;
  check('A 视角仍可见 m2（B 自删不影响 A）', has(aView3, m2r.id), ids(aView3));
  const bView3 = (await call('GET', `/api/socials/messages/conversations/${aId}?page=1&pageSize=20`, null, bToken)).body?.data;
  check('B 视角两条都已不见', !has(bView3, m1.id) && !has(bView3, m2r.id), ids(bView3));

  // A 也删 m2 → 幂等再删计数 0
  const sd4a = await call('POST', '/api/socials/messages/delete', { messageIds: [m2r.id] }, aToken);
  const sd4b = await call('POST', '/api/socials/messages/delete', { messageIds: [m2r.id] }, aToken);
  check('A 删 m2 成功且重复删除幂等（计数 0）',
    sd4a.status === 200 && sd4b.status === 200 && sd4b.body?.data === 0,
    [sd4a.body, sd4b.body]);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();
