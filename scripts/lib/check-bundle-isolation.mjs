/**
 * 前端产物隔离校验（E7-2 起固化）
 *
 * <h2>要证明什么</h2>
 * 「三平台独立部署」在**代码层**到底成没成：商城产物里**不该有**后台与商家的任何东西，
 * 后台/商家产物里也不该有商城的 C 端逻辑。这件事光看目录结构证明不了 ——
 * 必须**打开构建产物**，沿着入口的引用链把可达文件全部摊开来看。
 *
 * <h2>两条断言（都从产物出发，不看源码）</h2>
 * ① **可达性（闭包）**：从 entry HTML 出发跟随 `<script src>`、`<link rel=modulepreload>`、
 *    以及 chunk 之间的静态 `import "./x.js"` 与动态 `import("./x.js")`，得到**闭包**。
 *    dist 下**不允许存在闭包外的 .js** —— 那意味着有代码打进了产物却没有任何入口引用它
 *    （典型的"摘不干净"残留，或配置写错导致多打了入口）。
 * ② **特征串**：在闭包内搜索只属于**其他端**的字符串（接口路径、页面文案、专有库名）。
 *    用字符串字面量而不是标识符名 —— 生产构建会 minify，**标识符会改名，字符串会保留**。
 *
 * <h2>为什么这条断言值钱</h2>
 * E4–E6 期间后台/商家曾作为同一应用的另外两个入口存在；那时「商城不含后台代码」
 * 只能靠人工看 chunk 名与文件大小推断。有了本校验，结论是**可复现、可进 CI** 的。
 *
 * <h2>用法</h2>
 *   node scripts/lib/check-bundle-isolation.mjs
 * 退出码：0 = 全部通过；1 = 存在越界（会打印具体文件与命中的串）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(here, '../../frontend');

/**
 * 每个应用：入口 HTML + **不得出现**的特征串。
 * 只列「只属于其他端」的串，避免误报（例如商城顶栏本来就有指向后台/商家的**入口链接**，
 * 那属于商城的正常内容）。
 */
const APPS = {
  mall: {
    entry: 'apps/mall/dist/index.html',
    forbidden: [
      { s: '/api/admin/', why: '平台后台管理接口路径' },
      { s: '/api/seller/', why: '商家接口路径' },
      { s: '平台管理后台', why: '后台站点的页面标题' },
      { s: '商家工作台', why: '商家站点的页面标题' },
      { s: '举报处理', why: '后台举报处理台文案' },
      { s: '运营概览', why: '后台运营概览文案' },
      { s: 'echarts', why: '图表库（只有后台/商家用）' },
    ],
  },
  admin: {
    entry: 'apps/admin/dist/admin.html',
    forbidden: [
      { s: '/api/seckill/activities', why: 'C 端秒杀接口（属商城）' },
      { s: '限时秒杀', why: '商城秒杀页文案' },
      { s: '跳蚤市场', why: '商城闲置模块文案' },
    ],
  },
  merchant: {
    entry: 'apps/merchant/dist/merchant.html',
    forbidden: [
      { s: '/api/seckill/activities', why: 'C 端秒杀接口（属商城）' },
      { s: '限时秒杀', why: '商城秒杀页文案' },
      { s: '平台管理后台', why: '后台站点的页面标题' },
    ],
  },
};

/** 从 HTML 里取所有本地资源引用 */
function refsFromHtml(html) {
  const out = [];
  const re = /(?:src|href)\s*=\s*["']([^"']+)["']/g;
  let m;
  while ((m = re.exec(html))) {
    const v = m[1];
    if (v.startsWith('/') && !v.startsWith('//')) out.push(v.replace(/^\//, ''));
  }
  return out;
}

/** 从 JS chunk 里取所有本地依赖（静态 + 动态 import） */
function refsFromJs(js) {
  const out = [];
  const re = /(?:from|import)\s*\(?\s*["']\.\/([^"']+)["']/g;
  let m;
  while ((m = re.exec(js))) out.push(m[1]);
  return out;
}

function collectClosure(distDir, entryRel) {
  const entryAbs = path.join(FRONTEND, entryRel);
  const entryHtml = fs.readFileSync(entryAbs, 'utf8');
  const seen = new Set();
  const queue = refsFromHtml(entryHtml).map((r) => r.replace(/^assets\//, 'assets/'));

  while (queue.length) {
    const rel = queue.shift();
    if (seen.has(rel)) continue;
    const abs = path.join(distDir, rel);
    if (!fs.existsSync(abs)) continue;
    seen.add(rel);
    if (rel.endsWith('.js') || rel.endsWith('.mjs')) {
      for (const dep of refsFromJs(fs.readFileSync(abs, 'utf8'))) {
        // chunk 内部的相对引用是「同级 assets 目录」下的文件名
        queue.push(path.posix.join(path.posix.dirname(rel), dep));
      }
    }
  }
  return seen;
}

let failures = 0;
console.log('产物隔离校验（从 entry HTML 出发的引用闭包）\n');

for (const [app, cfg] of Object.entries(APPS)) {
  const distDir = path.join(FRONTEND, 'apps', app, 'dist');
  if (!fs.existsSync(distDir)) {
    console.error(`!! ${app}: dist 不存在（先构建）—— ${distDir}`);
    failures++;
    continue;
  }

  const closure = collectClosure(distDir, cfg.entry);

  // ① 无孤儿 chunk：dist 下所有 .js 都应在闭包内
  const allJs = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(js|mjs)$/.test(e.name)) allJs.push(path.relative(distDir, p).replace(/\\/g, '/'));
    }
  })(distDir);
  const orphans = allJs.filter((f) => !closure.has(f));

  // ② 特征串：闭包内不得出现其他端的串
  const hits = [];
  for (const rel of closure) {
    if (!/\.(js|mjs|html|css)$/.test(rel)) continue;
    const text = fs.readFileSync(path.join(distDir, rel), 'utf8');
    for (const f of cfg.forbidden) {
      if (text.includes(f.s)) hits.push({ file: rel, ...f });
    }
  }

  const okOrphan = orphans.length === 0;
  const okForbidden = hits.length === 0;
  const mark = okOrphan && okForbidden ? '✅' : '❌';
  console.log(`${mark} ${app}：闭包 ${closure.size} 个文件 / dist 内 .js ${allJs.length} 个`);
  if (!okOrphan) {
    console.log(`   ❌ 存在闭包外的 .js（无入口引用，属残留或误打包）：${orphans.join(', ')}`);
    failures++;
  }
  if (!okForbidden) {
    for (const h of hits) console.log(`   ❌ ${h.file} 含其他端的特征串「${h.s}」（${h.why}）`);
    failures++;
  }
}

console.log();
if (failures === 0) {
  console.log('✅ 产物隔离校验通过：三端产物互不包含对方的代码');
  process.exit(0);
}
console.log(`❌ 产物隔离校验失败（${failures} 项）`);
process.exit(1);
