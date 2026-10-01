/**
 * 内部凭据「JS 侧实现 vs PowerShell 侧实现」对拍
 *
 * 为什么需要：scripts/lib/internal-token.ps1 是冒烟脚本用的实现，
 * scripts/lib/internal-token.mjs 是前端 e2e（.mjs，跑在 Node 里）用的实现。
 * 同一个算法两份实现，改了一处忘另一处 = 内部调用全站 403，且报错只说「凭据不对」，
 * 看不出是**哪一侧**算错了。这里用固定输入比对两侧输出，漂移即红。
 *
 * 黄金向量由 PowerShell 侧实现生成（固定 ts / nonce，与机器和时刻无关）：
 *   . scripts/lib/internal-token.ps1
 *   $key = Get-InternalHmac -Key 'duoduo-local-dev-internal-token' -Data 'dd1|key|duoduo-smoke'
 *   $sig = Get-InternalHmac -Key $key -Data "dd1`nduoduo-smoke`n<ts>`n<nonce>`n<METHOD>"
 * 若算法变更，需同时改 ps1、mjs，并按上面步骤重新生成下面的向量。
 *
 * 运行： node --test scripts/lib/internal-token-parity.test.js
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { internalHmac, newInternalToken } from './internal-token.mjs';

const ROOT = 'duoduo-local-dev-internal-token';
const CALLER = 'duoduo-smoke';

// PowerShell 侧（Get-InternalHmac）对同一组输入的产出
const PS_KEY = 'lErpAF0Xv0bobdGFMgEZe_i_38oZPWW_aNEr6ufmduI';
const PS_TOKENS = {
  GET: 'dd1.duoduo-smoke.1700000000.nonce-aaaa-bbbb-cccc.sdKGBPGbdNSLplrqPnLcmEhymhRNPfCtfccXiLAAOIc',
  POST: 'dd1.duoduo-smoke.1700000001.nonce-dddd-eeee-ffff.IUebB9zcQVH_SPokROIOqOuX9zdUP0whtC2gHrr4LeI',
  PUT: 'dd1.duoduo-smoke.1700000002.nonce-1111-2222-3333.m7331XoiWmxA80wx-Fe902I62RuF0XlMA44xmhnl-MA',
};

test('派生密钥与 PowerShell 侧一致（Base64Url 无填充同口径）', () => {
  assert.equal(internalHmac(ROOT, `dd1|key|${CALLER}`), PS_KEY);
});

test('三种方法的签名与 PowerShell 侧逐字一致', () => {
  const cases = [
    ['GET', 1700000000, 'nonce-aaaa-bbbb-cccc'],
    ['POST', 1700000001, 'nonce-dddd-eeee-ffff'],
    ['PUT', 1700000002, 'nonce-1111-2222-3333'],
  ];
  for (const [method, ts, nonce] of cases) {
    assert.equal(
      newInternalToken(method, { ts, nonce }),
      PS_TOKENS[method],
      `${method} 的凭据与 PowerShell 侧不一致（算法漂移）`
    );
  }
});

test('方法大小写不敏感：两侧都按大写参与签名', () => {
  assert.equal(
    newInternalToken('get', { ts: 1700000000, nonce: 'nonce-aaaa-bbbb-cccc' }),
    PS_TOKENS.GET
  );
});

test('默认路径每次签出的 nonce 都不同（nonce 一次性，复用会被判重放）', () => {
  const a = newInternalToken('GET');
  const b = newInternalToken('GET');
  assert.notEqual(a.split('.')[3], b.split('.')[3]);
  assert.equal(a.split('.').length, 5, '头格式应为 dd1.caller.ts.nonce.sig 五段');
});
