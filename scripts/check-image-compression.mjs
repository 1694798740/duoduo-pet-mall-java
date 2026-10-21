#!/usr/bin/env node
/**
 * 验证「上传返回的图片 URL 已带压缩参数，且该 URL 真的返回 WebP」。
 *
 * 端到端走网关，不碰内部端口：注册临时账号 → 上传本地商品图 → 拿 URL
 * → 原样 GET 一次（带 query）→ 断言 Content-Type 与体积。
 */
import fs from 'node:fs';
import path from 'node:path';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const PWD = 'duoduo12345';
const IMG = path.resolve('miniprogram/images/products/p02.jpg');

let pass = 0;
let fail = 0;
const check = (name, ok, detail) => {
  if (ok) { pass += 1; console.log(`  [通过] ${name}`); }
  else { fail += 1; console.log(`  [失败] ${name} -> ${detail}`); }
};

const jar = new Map();
const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');

async function call(method, p, body) {
  const headers = { 'content-type': 'application/json' };
  if (jar.size) headers.cookie = cookie();
  const res = await fetch(GW + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  for (const c of res.headers.getSetCookie?.() ?? []) {
    const pair = c.split(';')[0];
    const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const raw = await res.text();
  let json = null;
  try { json = JSON.parse(raw); } catch { /* 保留 raw 便于报错 */ }
  return { status: res.status, data: json?.data, raw };
}

const phone = `139${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '压缩验证' });
check('临时账号注册成功', reg.status === 200, `${reg.status} ${reg.raw.slice(0, 160)}`);

const bytes = fs.readFileSync(IMG);
const form = new FormData();
form.append('file', new Blob([bytes], { type: 'image/jpeg' }), 'p02.jpg');
const upRes = await fetch(`${GW}/api/uploads`, {
  method: 'POST',
  headers: jar.size ? { cookie: cookie() } : {},
  body: form,
});
const upRaw = await upRes.text();
let upJson = null;
try { upJson = JSON.parse(upRaw); } catch { /* 保留 raw */ }
check('上传成功', upRes.status === 200 && !!upJson?.data?.url, `${upRes.status} ${upRaw.slice(0, 200)}`);

const url = upJson?.data?.url ?? '';
console.log(`  返回 URL: ${url}`);
check('返回 URL 带 imageMogr2 参数', url.includes('imageMogr2/'), url);

if (url.includes('imageMogr2/')) {
  const got = await fetch(url);
  const type = got.headers.get('content-type') ?? '';
  const body = Buffer.from(await got.arrayBuffer());
  check('带参数访问返回 200', got.status === 200, got.status);
  check('Content-Type 为 image/webp', type.includes('webp'), type);
  check('体积小于原图', body.length < bytes.length, `原图 ${bytes.length} / 返回 ${body.length}`);
  console.log(`  原图 ${bytes.length} 字节 -> 返回 ${body.length} 字节（${((body.length - bytes.length) * 100 / bytes.length).toFixed(1)}%）`);
}

// GIF 必须原样直出：format/webp 会把动图压成一张静图，用户上传的表情包会突然不动。
// 用一张最小的 1x1 GIF 走同一条上传链路，只断言「URL 末尾没有 imageMogr2」。
const MIN_GIF = Buffer.from(
  '47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b',
  'hex',
);
const gifForm = new FormData();
gifForm.append('file', new Blob([MIN_GIF], { type: 'image/gif' }), 'x.gif');
const gifRes = await fetch(`${GW}/api/uploads`, {
  method: 'POST',
  headers: jar.size ? { cookie: cookie() } : {},
  body: gifForm,
});
const gifRaw = await gifRes.text();
let gifJson = null;
try { gifJson = JSON.parse(gifRaw); } catch { /* 保留 raw */ }
const gifUrl = gifJson?.data?.url ?? '';
check('GIF 上传成功', gifRes.status === 200 && !!gifUrl, `${gifRes.status} ${gifRaw.slice(0, 160)}`);
check('GIF 的 URL 不带处理参数（保住动效）', !!gifUrl && !gifUrl.includes('imageMogr2'), gifUrl);

console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail === 0 ? 0 : 1);
