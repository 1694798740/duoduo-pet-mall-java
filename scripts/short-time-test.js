#!/usr/bin/env node
/**
 * shortTime 时区专项单测（第五十三轮热修新增）
 *
 * 背景：小程序 13 个页面的时间展示都走 api.shortTime。旧实现把后端下发的 UTC 标记
 * （`Z`）主动删掉再裸切片，于是时间被当作本地时间展示 —— 北京时间用户看到的比真实
 * 时间少 8 小时（12:33 发出显示 04:33）。
 *
 * 本测把 shortTime 从 api.js 抽出来单独执行（不依赖 wx 运行时），断言：
 *   1) 带 Z 的串按 UTC 解析 → 转**设备本地时区**（期望值按进程时区现算，故任意时区都能跑）
 *   2) 不带时区标记的串按后端契约视为 UTC（与带 Z 的结果一致）
 *   3) 带 +08:00 偏移的串与之等价（同一绝对时刻 → 同一展示）
 *   4) 纳秒精度（后端下发 9 位小数秒）不炸、不影响分钟
 *   5) 纯日期 / 非法串 / 空值原样返回，绝不显示 undefined 或 NaN
 *   6) ★ 反回归：非 UTC 进程时区下，输出**不得等于**裸切片的 UTC 挂钟时间
 *
 * 用法：node scripts/short-time-test.js            （任意时区均可跑）
 *      TZ=Asia/Shanghai node scripts/short-time-test.js（另跑北京特例断言）
 * 退出码：0 = 全过
 */
const fs = require('fs');
const path = require('path');

const API = path.join(__dirname, '..', 'miniprogram', 'utils', 'api.js');
const src = fs.readFileSync(API, 'utf8');

// 抽取 shortTime 函数源码（从函数头到其闭合大括号），在隔离作用域里编译执行
const start = src.indexOf('function shortTime(iso) {');
if (start < 0) {
  console.error('✗ 未在 api.js 里找到 shortTime');
  process.exit(1);
}
let depth = 0;
let end = -1;
for (let i = src.indexOf('{', start); i < src.length; i++) {
  if (src[i] === '{') depth++;
  else if (src[i] === '}') {
    depth--;
    if (depth === 0) {
      end = i + 1;
      break;
    }
  }
}
// eslint-disable-next-line no-new-func
const shortTime = new Function(src.slice(start, end) + '; return shortTime;')();

let pass = 0;
let fail = 0;
function check(name, actual, expected) {
  if (actual === expected) {
    pass++;
    console.log(`  [PASS] ${name} → ${actual}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${name} → 实际=${actual} 期望=${expected}`);
  }
}

const tz = process.env.TZ || Intl.DateTimeFormat().resolvedOptions().timeZone || '(unknown)';
const p2 = (n) => ('0' + n).slice(-2);
/** 用日期对象直接渲染某时刻在**当前进程时区**下的 MM-DD HH:mm（与被测函数独立实现） */
function localText(epochMs) {
  const d = new Date(epochMs);
  return p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes());
}

// 固定用例时刻：北京时间 2026-09-29 12:33 = UTC 04:33
const EPOCH = Date.UTC(2026, 8, 29, 4, 33, 46);

console.log(`=== shortTime 时区单测（进程时区 = ${tz}）===`);

// 1~4 与 6：所有写法都必须落到同一绝对时刻，并按进程本地时区展示
check('带 Z（UTC 秒）→ 本地', shortTime('2026-09-29T04:33:46Z'), localText(EPOCH));
check('带 Z（纳秒精度）→ 本地', shortTime('2026-09-29T04:33:46.974436137Z'), localText(EPOCH));
check('不带时区标记（后端契约即 UTC）→ 本地', shortTime('2026-09-29T04:33:46'), localText(EPOCH));
check('带 +08:00 偏移（同一绝对时刻）→ 本地', shortTime('2026-09-29T12:33:46+08:00'), localText(EPOCH));
check('带 -05:00 偏移（同一绝对时刻）→ 本地', shortTime('2026-09-28T23:33:46-05:00'), localText(EPOCH));
check(
  '跨日换算（UTC 前一日 20:00）',
  shortTime('2026-09-28T20:00:00Z'),
  localText(Date.UTC(2026, 8, 28, 20, 0, 0))
);

// 5：边界与兜底
check('纯日期原样返回', shortTime('2026-09-30'), '2026-09-30');
check('空值返回空串', shortTime(''), '');
check('null 返回空串', shortTime(null), '');
check('非法串原样返回', shortTime('not-a-time'), 'not-a-time');

// 6：反回归 —— 非 UTC 时区下绝不能退化成裸切片（旧实现输出 '09-29 04:33'）
const naive = '09-29 04:33';
const got = shortTime('2026-09-29T04:33:46Z');
const utcOffsetMin = -new Date(EPOCH).getTimezoneOffset();
if (utcOffsetMin === 0) {
  console.log(`  [SKIP] 反回归断言：进程时区即为 UTC（${tz}），裸切片与正确实现结果相同，无法区分`);
} else {
  check(`反回归（时区偏移 ${utcOffsetMin / 60}h，输出不得为裸切片值）`, got === naive ? '裸切片值' : '已按本地时区换算', '已按本地时区换算');
}

// 北京特例：仅在 TZ=Asia/Shanghai 下断言具体值（用户报障的原始场景）
if (utcOffsetMin === 480) {
  check('北京特例：12:33 发出 → 显示 12:33（不是 04:33）', shortTime('2026-09-29T04:33:46Z'), '09-29 12:33');
} else {
  console.log('  [SKIP] 北京特例断言：当前进程时区非 Asia/Shanghai');
}

console.log(`\n结论：${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
