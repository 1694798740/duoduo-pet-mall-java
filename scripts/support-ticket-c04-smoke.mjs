#!/usr/bin/env node
/**
 * C-04 · 客服工单认领 / 转交 / 审计冒烟（2026-10-26）。
 *
 * 守六组事实：
 *  A. 夹具：店主邀请两名店员，买家建客服会话并发送一条消息；
 *  B. 认领：未认领会话可被店员认领；重复认领幂等；并发认领只有一个成功（另一个 409）；
 *  C. 全店可见：店主与店员都能看到同一条会话（处理人不是访问控制）；
 *  D. 转交：当前处理人可转交、备注落库；非处理人转交 403；转给非本店成员 400；
 *     店主可把未认领会话直接指派；
 *  E. 释放与审计：释放回未认领、重复释放幂等；每次成功动作一条 CLAIM/TRANSFER/RELEASE 记录，
 *     记录包含前/后处理人、操作者与备注；跨店查看 403、买家 403、未登录 401；
 *  F. 收尾零残留：会话 / 消息 / 处理记录 / 店员成员 / 临时账号全部清理。
 *
 * 前置：容器在跑；social 已部署 C-04；`incremental/58-support-ticket.sql` 已执行；
 *       卖家 13600003333（店主）、另一店主 13959064118 可用。
 * 用法：NO_PROXY="*" node scripts/support-ticket-c04-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const SOCIAL_DB = 'duoduo_social';
const USER_DB = 'duoduo_user';

let pass = 0;
const failures = [];
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 800)));
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
const tokenOf = async (phone) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD })).body?.data?.accessToken;

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';
const count = (db, query) => Number(sql(db, query)[0] ?? 0);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stamp = String(Date.now()).slice(-7);
const suffix = String(Date.now()).slice(-8);
const staffAPhone = `193${suffix}`;
const staffBPhone = `194${suffix}`;
const buyerPhone = `192${suffix}`;
const staffAName = `C04工单店员A${stamp}`;
const staffBName = `C04工单店员B${stamp}`;
const buyerName = `C04工单买家${stamp}`;

let ownerToken;
let otherToken;
let staffAToken;
let staffBToken;
let buyerToken;
let ownerId;
let staffAId;
let staffBId;
let buyerId;
let sessionId;
let otherUserId;

function cleanupStale() {
  const users = sql(USER_DB, `SELECT id FROM t_user WHERE nickname LIKE 'C04工单%'`);
  for (const uid of users) {
    const sessions = sql(SOCIAL_DB, `SELECT id FROM t_support_session WHERE user_id=${uid} OR seller_id=${uid}`);
    for (const sid of sessions) {
      sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sid}`);
      sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sid}`);
      sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sid}`);
    }
    sql(USER_DB, `DELETE FROM t_merchant_member WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
}

const ticketOf = async (sid, token) =>
  (await call('GET', `/api/seller/support/sessions/${sid}/ticket`, null, token)).body?.data;
const sessionsOf = async (token) =>
  (await call('GET', '/api/seller/support/sessions?filter=ALL&page=1&pageSize=50', null, token)).body?.data?.items ?? [];

async function main() {
  console.log('== 0. 登录与夹具 ==');
  cleanupStale();
  ownerToken = await tokenOf(SELLER);
  otherToken = await tokenOf(OTHER_SELLER);
  check('店主 / 另一店主登录成功', Boolean(ownerToken) && Boolean(otherToken));
  if (!ownerToken || !otherToken) return 1;
  ownerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);
  otherUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${OTHER_SELLER}'`);

  for (const [phone, name] of [[staffAPhone, staffAName], [staffBPhone, staffBName]]) {
    const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: name });
    check(`临时店员 ${name} 注册成功`, reg.status === 200 || reg.status === 201, reg.body);
  }
  const buyerReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
  check('临时买家注册成功', buyerReg.status === 200 || buyerReg.status === 201, buyerReg.body);
  staffAId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${staffAPhone}'`);
  staffBId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${staffBPhone}'`);
  buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
  check('取到三个临时账号 userId', [staffAId, staffBId, buyerId].every(Boolean), { staffAId, staffBId, buyerId });

  const inviteA = await call('POST', '/api/seller/staff', { phone: staffAPhone }, ownerToken);
  const inviteB = await call('POST', '/api/seller/staff', { phone: staffBPhone }, ownerToken);
  check('店主邀请两名店员成功', inviteA.status === 200 && inviteB.status === 200,
    { a: inviteA.body, b: inviteB.body });
  staffAToken = await tokenOf(staffAPhone);
  staffBToken = await tokenOf(staffBPhone);
  buyerToken = await tokenOf(buyerPhone);
  check('店员 / 买家登录成功', [staffAToken, staffBToken, buyerToken].every(Boolean));

  const created = await call('GET', `/api/support/session?sellerId=${ownerId}`, null, buyerToken);
  sessionId = created.body?.data?.id;
  check('买家建客服会话成功', created.status === 200 && Boolean(sessionId), created.body);
  const sent = await call('POST', '/api/support/messages',
    { sellerId: ownerId, content: `C04 工单冒烟 ${stamp} 请帮我查一下订单` }, buyerToken);
  check('买家发送咨询消息成功', sent.status === 200, sent.body);

  console.log('\n== 1. 处理人初始状态与全店可见 ==');
  const ownerSessions = await sessionsOf(ownerToken);
  const staffSessions = await sessionsOf(staffAToken);
  const ownerRow = ownerSessions.find((s) => String(s.id) === String(sessionId));
  const staffRow = staffSessions.find((s) => String(s.id) === String(sessionId));
  check('店主与店员看到同一条会话', Boolean(ownerRow) && Boolean(staffRow));
  check('初始为未认领（assigneeId/Name 为空）',
    ownerRow?.assigneeId == null && ownerRow?.assigneeName == null, ownerRow);
  const ticket0 = await ticketOf(sessionId, staffAToken);
  check('工单初始无处理记录', ticket0?.logs?.length === 0, ticket0);
  check('工单成员名单含店主与两名店员',
    ticket0?.members?.length >= 3 && ticket0.members.some((m) => m.role === 'OWNER'),
    ticket0?.members);

  console.log('\n== 2. 并发认领：只有一个成功 ==');
  const [claimA, claimB] = await Promise.all([
    call('POST', `/api/seller/support/sessions/${sessionId}/ticket/claim`, null, staffAToken),
    call('POST', `/api/seller/support/sessions/${sessionId}/ticket/claim`, null, staffBToken),
  ]);
  const statuses = [claimA.status, claimB.status].sort((a, b) => a - b);
  check('并发认领结果 = 一个 200 + 一个 409',
    statuses[0] === 200 && statuses[1] === 409, { a: claimA.body, b: claimB.body });
  const winnerToken = claimA.status === 200 ? staffAToken : staffBToken;
  const winnerId = claimA.status === 200 ? staffAId : staffBId;
  const winnerName = claimA.status === 200 ? staffAName : staffBName;
  let ticket = await ticketOf(sessionId, winnerToken);
  check('认领成功者是当前处理人', String(ticket?.assigneeId) === String(winnerId)
    && ticket?.assigneeName === winnerName, ticket);
  check('只有一条 CLAIM 处理记录', ticket?.logs?.length === 1
    && ticket.logs[0].action === 'CLAIM', ticket?.logs);
  const again = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/claim`, null, winnerToken);
  check('重复认领幂等且不新增记录',
    again.status === 200 && again.body?.data?.logs?.length === 1, again.body?.data);
  const ownerClaim = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/claim`, null, ownerToken);
  check('店主认领已认领会话 → 409', ownerClaim.status === 409, ownerClaim.body);

  console.log('\n== 3. 转交与权限 ==');
  const loserToken = winnerToken === staffAToken ? staffBToken : staffAToken;
  const loserTransfer = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/transfer`,
    { toUserId: ownerId }, loserToken);
  check('非当前处理人转交 → 403', loserTransfer.status === 403, loserTransfer.body);
  const outsiderTransfer = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/transfer`,
    { toUserId: buyerId }, winnerToken);
  check('转交给非本店成员 → 400', outsiderTransfer.status === 400, outsiderTransfer.body);
  const transferToOwner = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/transfer`,
    { toUserId: ownerId, note: 'C04 冒烟：店长接手' }, winnerToken);
  check('当前处理人转交给店主成功', transferToOwner.status === 200
    && String(transferToOwner.body?.data?.assigneeId) === String(ownerId), transferToOwner.body);
  const transferLog = transferToOwner.body?.data?.logs?.at(-1);
  check('转交记录含 from/to/操作者/备注',
    transferLog?.action === 'TRANSFER'
      && String(transferLog.fromAssigneeId) === String(winnerId)
      && String(transferLog.toAssigneeId) === String(ownerId)
      && transferLog.actorName === winnerName
      && transferLog.note === 'C04 冒烟：店长接手', transferLog);
  const ownerTransferBack = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/transfer`,
    { toUserId: winnerId, note: 'C04 冒烟：归还' }, ownerToken);
  check('店主把工单转回店员成功', ownerTransferBack.status === 200
    && String(ownerTransferBack.body?.data?.assigneeId) === String(winnerId), ownerTransferBack.body);

  console.log('\n== 4. 释放与审计 ==');
  const released = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/release`, null, ownerToken);
  check('店主释放成功（处理人清空）',
    released.status === 200 && released.body?.data?.assigneeId == null, released.body);
  check('释放记录含 from / 操作者 / to=null',
    released.body?.data?.logs?.at(-1)?.action === 'RELEASE'
      && String(released.body.data.logs.at(-1).fromAssigneeId) === String(winnerId)
      && released.body.data.logs.at(-1).toAssigneeId == null, released.body?.data?.logs?.at(-1));
  const releaseAgain = await call('POST', `/api/seller/support/sessions/${sessionId}/ticket/release`, null, ownerToken);
  check('重复释放幂等且不新增记录',
    releaseAgain.status === 200 && releaseAgain.body?.data?.logs?.length === 4,
    releaseAgain.body?.data?.logs?.length);
  const dbLogs = sql(SOCIAL_DB,
    `SELECT CONCAT(action,'|',IFNULL(from_assignee,'-'),'|',IFNULL(to_assignee,'-'),'|',actor_id,'|',IFNULL(note,'-'))
     FROM t_support_ticket_log WHERE session_id=${sessionId} ORDER BY id`);
  check('回库：CLAIM + TRANSFER×2 + RELEASE 共 4 条',
    dbLogs.length === 4 && dbLogs[0].startsWith('CLAIM|') && dbLogs[1].startsWith('TRANSFER|')
      && dbLogs[2].startsWith('TRANSFER|') && dbLogs[3].startsWith('RELEASE|'), dbLogs);

  console.log('\n== 5. 跨店 / 买家 / 未登录 ==');
  const crossStore = await call('GET', `/api/seller/support/sessions/${sessionId}/ticket`, null, otherToken);
  check('别的店主看工单 → 403', crossStore.status === 403, crossStore.body);
  const buyerTicket = await call('GET', `/api/seller/support/sessions/${sessionId}/ticket`, null, buyerToken);
  check('买家访问商家工单 → 403', buyerTicket.status === 403, buyerTicket.body);
  const anonTicket = await call('GET', `/api/seller/support/sessions/${sessionId}/ticket`);
  check('未登录访问工单 → 401', anonTicket.status === 401, anonTicket.body);

  console.log('\n== 6. 收尾零残留 ==');
  sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sessionId}`);
  for (const uid of [staffAId, staffBId, buyerId]) {
    sql(USER_DB, `DELETE FROM t_merchant_member WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  check('会话 / 消息 / 处理记录零残留',
    count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_session WHERE id=${sessionId}`) === 0
      && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`) === 0
      && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_ticket_log WHERE session_id=${sessionId}`) === 0);
  check('店员成员与临时账号零残留',
    count(USER_DB, `SELECT COUNT(*) FROM t_merchant_member WHERE user_id IN (${staffAId},${staffBId})`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id IN (${staffAId},${staffBId},${buyerId})`) === 0);

  return failures.length ? 1 : 0;
}

try {
  const code = await main();
  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：');
    for (const name of failures) console.log('  - ' + name);
  }
  process.exitCode = code;
} catch (err) {
  console.error('冒烟异常：', err);
  process.exitCode = 1;
}
