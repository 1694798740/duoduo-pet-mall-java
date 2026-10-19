/**
 * 商品详情链接必须用 SPU id，禁止拿 SKU id 冒充（2026-10-19 线上 bug 固化）
 *
 * <h2>为什么需要它</h2>
 * 商品详情页的路由是 `/product/:spuId`，后端接口是 `GET /api/products/{spuId}`；
 * 而秒杀活动、购物车行、订单行里天然只有 `skuId`。两者都是「一串数字」，
 * TypeScript 里也都是 `string`，**类型系统完全抓不到混用**。
 *
 * 真实事故：秒杀列表的标题链接写成 `/product/${sku.skuId}`，
 * 于是 `GET /api/products/2001` → 404 `{"code":"NOT_FOUND","message":"商品不存在"}`
 * —— 列表里商品明明看得到（标题、图、价都正常，因为那是按 skuId 查的快照），
 * 点开却说「商品不存在」，排查方向很容易被带成「商品被删了 / 活动数据脏了」。
 *
 * <h2>判据</h2>
 * 扫描所有 `.ts/.tsx` 里的 `/product/${…}` 模板串：花括号内出现 `skuId`
 * 且**没有** `spuId` 即判违规。合法的写法只有 `spuId`（或 `card.id` 这类确知是 SPU 的值）。
 *
 * <h2>为什么扫描数为 0 也判红</h2>
 * 「一个文件都没扫到」与「扫了、结果干净」在输出上完全一样。
 * 目录改名 / 正则写错都会让守卫静默变成摆设 —— 这里宁可报红。
 *
 * 用法：node scripts/lib/check-product-link-spu.mjs
 * 退出码：0 = 干净；1 = 有违规或扫描范围异常。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(here, '../../frontend');

const TARGETS = [
  path.join(FRONTEND, 'apps/mall/src'),
  path.join(FRONTEND, 'apps/admin/src'),
  path.join(FRONTEND, 'apps/merchant/src'),
  path.join(FRONTEND, 'packages/api-sdk/src'),
  path.join(FRONTEND, 'packages/shared/src'),
];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(ts|tsx)$/.test(e.name)) out.push(p);
  }
  return out;
}

const problems = [];
let filesScanned = 0;
let linksChecked = 0;

// 只匹配模板串里的常量前缀 /product/ ，后接 ${...}
const LINK_RE = /`\/product\/\$\{([^}]*)\}`/g;

for (const base of TARGETS) {
  if (!fs.existsSync(base)) {
    console.error(`!! 扫描范围不存在（目录结构变了？）：${base}`);
    process.exit(1);
  }
  for (const file of walk(base)) {
    filesScanned++;
    const src = fs.readFileSync(file, 'utf8');
    const lines = src.split(/\r?\n/);
    lines.forEach((line, i) => {
      LINK_RE.lastIndex = 0;
      let m;
      while ((m = LINK_RE.exec(line))) {
        linksChecked++;
        const expr = m[1];
        // 合法：表达式里出现 spuId / card.id / item.id（确知是 SPU 的字段）
        const looksSpu = /\bspuId\b/.test(expr);
        const usesSku = /\bskuId\b/.test(expr);
        if (usesSku && !looksSpu) {
          problems.push({
            file: path.relative(FRONTEND, file).replace(/\\/g, '/'),
            line: i + 1,
            expr: expr.trim(),
          });
        }
      }
    });
  }
}

console.log(
  `商品详情链接守卫：扫描 ${filesScanned} 个文件、${linksChecked} 处 /product/ 模板链接（范围：3 应用 + 2 包）`,
);

if (filesScanned === 0 || linksChecked === 0) {
  console.error('❌ 扫描数为 0 —— 「什么都没扫到」与「扫了且干净」无法区分，按违规处理');
  process.exit(1);
}

if (problems.length === 0) {
  console.log('✅ 全部按 SPU 寻址');
  process.exit(0);
}

console.log(`❌ ${problems.length} 处用 skuId 拼商品详情链接（应为 spuId）：`);
for (const p of problems) {
  console.log(`   ${p.file}:${p.line}  →  \${${p.expr}}`);
}
process.exit(1);
