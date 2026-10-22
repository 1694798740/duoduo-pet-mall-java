/**
 * 支付二维码矩阵自检（A3 欠账回归）。
 *
 * 背景：2026-10-18 之前是自研编码器，生成的码「三个定位图案不在位、时序图案错位」，
 * 扫码无反应。现在矩阵交给 `qrcode` 库，本脚本把「标准结构」钉成断言，
 * 防止哪天有人又把手写实现换回来。
 *
 * 断言：
 *   1. 三个定位图案（左上/右上/左下）符合 7×7 规范（外框深、内 3×3 深、间隔白）；
 *   2. 时序图案（第 6 行/第 6 列）黑白交替；
 *   3. 定位图案之间至少留 1 个模块的白色分隔带；
 *   4. 不同内容生成不同矩阵（避免"永远返回同一张图"的假绿）。
 */
import { createRequire } from 'node:module';

// 依赖装在 frontend 工作区；从仓库根跑脚本时按 cwd 解析，避免 ERR_MODULE_NOT_FOUND。
// 用法：cd frontend && node ../scripts/check-qrcode.mjs
const require = createRequire(process.cwd() + '/package.json');
const QR = require('qrcode');

let pass = 0;
const failures = [];
const check = (name, ok, detail = '') => {
  if (ok) {
    pass += 1;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}${detail ? '  -> ' + detail : ''}`);
  }
};

const qr = QR.create('https://pay.example.com/cashier?orderNo=202610220001&amount=15800', {
  errorCorrectionLevel: 'M',
});
const n = qr.modules.size;
const bits = qr.modules.data;
const dark = (r, c) => bits[r * n + c] === 1;

// 1) 三个定位图案：7×7 外框 + 中心 3×3
const finderOk = (r0, c0) => {
  for (let r = 0; r < 7; r += 1) {
    for (let c = 0; c < 7; c += 1) {
      const border = r === 0 || r === 6 || c === 0 || c === 6;
      const center = r >= 2 && r <= 4 && c >= 2 && c <= 4;
      const expect = border || center;
      if (dark(r0 + r, c0 + c) !== expect) return false;
    }
  }
  return true;
};
check('左上定位图案符合规范', finderOk(0, 0));
check('右上定位图案符合规范', finderOk(0, n - 7));
check('左下定位图案符合规范', finderOk(n - 7, 0));

// 2) 时序图案：第 6 行与第 6 列（跳过定位区域）黑白交替
let timingOk = true;
for (let i = 8; i < n - 8; i += 1) {
  const expect = i % 2 === 0;
  if (dark(6, i) !== expect || dark(i, 6) !== expect) {
    timingOk = false;
    break;
  }
}
check('时序图案交替正确', timingOk);

// 3) 分隔带：定位图案内侧一圈必须为白
let separatorOk = true;
for (let i = 0; i < 8; i += 1) {
  if (dark(7, i) || dark(i, 7)) separatorOk = false; // 左上
  if (dark(7, n - 1 - i) || dark(i, n - 8)) separatorOk = false; // 右上
  if (dark(n - 8, i) || dark(n - 1 - i, 7)) separatorOk = false; // 左下
}
check('定位图案分隔带为白', separatorOk);

// 4) 不同内容 → 不同矩阵
const other = QR.create('https://pay.example.com/cashier?orderNo=202610220002&amount=100', {
  errorCorrectionLevel: 'M',
});
check('不同内容生成不同矩阵', Buffer.compare(Buffer.from(bits), Buffer.from(other.modules.data)) !== 0);

console.log(`\n=== 二维码矩阵自检：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
