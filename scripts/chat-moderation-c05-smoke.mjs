#!/usr/bin/env node
/**
 * C-05 · 聊天内容质检 / 敏感词拦截冒烟（2026-10-26）。
 *
 * 守四组事实：
 *  A. 正常聊天不受影响：普通发货咨询、订单号里的数字串、裸词「微信」「二维码」均放行；
 *  B. 拦截：站外链接 / 诈骗资金词 / 运营配置敏感词 → 422，且消息不落库；
 *  C. 联系方式：手机号默认只告警不拦截，消息正常落库；
 *  D. 覆盖范围：客服买家消息、客服回复、私信都过质检；收尾零残留。
 *
 * 前置：容器在跑；social 已部署 C-05；`CHAT_MODERATION_ENABLED` 未关闭；
 *       卖家 13600003333 可用。
 * 用法：NO_PROXY="*" node scripts/chat-moderation-c05-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
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
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 700)));
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

const stamp = String(Date.now()).slice(-7);
const suffix = String(Date.now()).slice(-8);
const buyerPhone = `189${suffix}`;
const peerPhone = `188${suffix}`;
const buyerName = `C05聊天买家${stamp}`;
const peerName = `C05聊天对象${stamp}`;

const ownerToken = await tokenOf(SELLER);
check('卖家登录成功', Boolean(ownerToken));
if (!ownerToken) process.exit(1);
const ownerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
await call('POST', '/api/auth/register', { phone: peerPhone, password: PWD, nickname: peerName });
const buyerToken = await tokenOf(buyerPhone);
const peerToken = await tokenOf(peerPhone);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const peerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${peerPhone}'`);
check('临时买家 / 聊天对象注册并登录', Boolean(buyerToken) && Boolean(peerToken) && Boolean(buyerId) && Boolean(peerId));

const session = await call('GET', `/api/support/session?sellerId=${ownerId}`, null, buyerToken);
const sessionId = session.body?.data?.id;
check('客服会话就绪', Boolean(sessionId), session.body);
const sendSupport = (content) => call('POST', '/api/support/messages', { sellerId: ownerId, content }, buyerToken);
const replySupport = (content) => call('POST', `/api/seller/support/sessions/${sessionId}/messages`, { content }, ownerToken);
const sendPrivate = (receiverId, content) => call('POST', '/api/socials/messages', { receiverId, content }, buyerToken);

console.log('\n== A. 正常沟通放行 ==');
const normal1 = await sendSupport('请问我的猫粮什么时候发货？订单 202610260001888888');
check('普通发货咨询 + 订单号放行', normal1.status === 200, normal1.body);
const normal2 = await sendSupport('用微信支付很方便，二维码在哪里？');
check('裸词「微信」「二维码」不误伤', normal2.status === 200, normal2.body);

console.log('\n== B. 链接 / 诈骗词 / 敏感词拦截 ==');
const link = await sendSupport('加这个 https://evil.example.com 领优惠');
check('站外链接 → 422', link.status === 422 && String(link.body?.message).includes('站外链接'), link.body);
const fraud = await sendSupport('先给我转账 500 元，我再发货');
check('诈骗资金词 → 422', fraud.status === 422 && String(fraud.body?.message).includes('资金'), fraud.body);
const sensitive = await sendSupport('接代练任务轻松赚钱');
check('配置敏感词 → 422', sensitive.status === 422 && String(sensitive.body?.message).includes('敏感词'), sensitive.body);

console.log('\n== C. 联系方式默认只告警 ==');
const phone = await sendSupport('我电话 13800138000，方便联系');
check('手机号默认放行（仅告警）', phone.status === 200, phone.body);

console.log('\n== D. 客服回复与私信同样过质检 ==');
const replyNormal = await replySupport('好的，今天下午安排发货');
check('客服正常回复放行', replyNormal.status === 200, replyNormal.body);
const replyLink = await replySupport('去 www.evil.com 下单更便宜');
check('客服回复站外链接 → 422', replyLink.status === 422, replyLink.body);

const privateLink = await sendPrivate(peerId, '看 https://evil.example.com');
check('私信站外链接 → 422', privateLink.status === 422, privateLink.body);
const privateNormal = await sendPrivate(peerId, '你好，猫粮怎么保存比较好？');
check('私信正常内容放行', privateNormal.status === 200, privateNormal.body);

const supportCount = count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`);
check('被拦截的客服消息未落库（正常 3 条：2 买家 + 1 客服 + 1 手机号 = 4）',
  supportCount === 4, supportCount);
const privateCount = count(SOCIAL_DB,
  `SELECT COUNT(*) FROM t_social_message WHERE (sender_id=${buyerId} AND receiver_id=${peerId})
   OR (sender_id=${peerId} AND receiver_id=${buyerId})`);
check('被拦截的私信未落库（正常 1 条）', privateCount === 1, privateCount);

console.log('\n== E. 收尾零残留 ==');
sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sessionId}`);
sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sessionId}`);
sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sessionId}`);
sql(SOCIAL_DB,
  `DELETE FROM t_social_message WHERE (sender_id=${buyerId} AND receiver_id=${peerId})
   OR (sender_id=${peerId} AND receiver_id=${buyerId})`);
for (const uid of [buyerId, peerId]) {
  sql(USER_DB, `DELETE FROM t_merchant_member WHERE user_id=${uid}`);
  sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
  sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
  sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
}
check('客服与私信夹具零残留',
  count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`) === 0
    && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_session WHERE id=${sessionId}`) === 0
    && count(SOCIAL_DB,
      `SELECT COUNT(*) FROM t_social_message WHERE (sender_id=${buyerId} AND receiver_id=${peerId})
       OR (sender_id=${peerId} AND receiver_id=${buyerId})`) === 0
    && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id IN (${buyerId},${peerId})`) === 0);

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}
