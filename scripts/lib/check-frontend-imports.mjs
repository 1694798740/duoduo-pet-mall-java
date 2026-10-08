/**
 * 前端 import 解析校验（E7 起固化 —— 大迁移/重构后的必备一步）
 *
 * <h2>为什么需要它</h2>
 * 改名、搬家、换引用这类改动，**只做"批量改写"是不够的**：
 *  - 写成同目录形式的引用（`./ui`、`./shared`）不会命中「`../` 开头」这类改写规则，
 *    于是**静默留在原处** —— 而目标文件已经搬走了；
 *  - 这类错误**只在构建时才炸**，而且提示是「找不到模块」，
 *    很容易被误判成「依赖没装好 / 包坏了」，排查方向整个跑偏。
 *
 * E7-1 的真实战绩：脚本改写了 300 处引用后，本校验抓出 **11 处**会静默失效的漏改。
 *
 * <h2>做什么</h2>
 * 把每个 `.ts/.tsx` 文件里的 import/export 说明符**逐个解析**（相对路径 + `@duoduo/*` 别名），
 * 验证目标文件真实存在（含 `.ts/.tsx/.css` 与目录 `index.*`）。第三方模块（react 等）跳过。
 *
 * <h2>用法</h2>
 *   node scripts/lib/check-frontend-imports.mjs
 * 退出码：0 = 全部可解析；1 = 存在无法解析的引用（会逐条打印文件与解析结果）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(here, '../../frontend');

/** 检查范围：三个应用 + 两个共用包 */
const TARGETS = [
  path.join(FRONTEND, 'apps/mall/src'),
  path.join(FRONTEND, 'apps/admin/src'),
  path.join(FRONTEND, 'apps/merchant/src'),
  path.join(FRONTEND, 'packages/api-sdk/src'),
  path.join(FRONTEND, 'packages/shared/src'),
];

/** 与各 app 的 vite.config.ts / tsconfig.json 里的 alias 保持一致 */
const ALIASES = {
  '@duoduo/api-sdk': path.join(FRONTEND, 'packages/api-sdk/src'),
  '@duoduo/shared': path.join(FRONTEND, 'packages/shared/src'),
};

const SKIP_SPEC_PREFIX = ['react', 'react-dom', 'react-router-dom', 'echarts', 'node:'];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(ts|tsx)$/.test(e.name)) out.push(p);
  }
  return out;
}

function resolves(p) {
  const cands = [p, `${p}.ts`, `${p}.tsx`, `${p}.css`, path.join(p, 'index.ts'), path.join(p, 'index.tsx')];
  return cands.some((c) => fs.existsSync(c) && fs.statSync(c).isFile());
}

const problems = [];
let checked = 0;

for (const base of TARGETS) {
  if (!fs.existsSync(base)) {
    console.error(`!! 检查范围不存在（目录结构是否又变了？）：${base}`);
    process.exit(1);
  }
  for (const file of walk(base)) {
    const src = fs.readFileSync(file, 'utf8');
    const re = /(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g;
    let m;
    while ((m = re.exec(src))) {
      const spec = m[1];
      const isAlias = spec.startsWith('@duoduo/');
      if (!spec.startsWith('.') && !isAlias) continue;
      if (!isAlias && SKIP_SPEC_PREFIX.some((s) => spec.startsWith(s))) continue;
      checked++;

      let target;
      if (isAlias) {
        const pkgName = spec.startsWith('@duoduo/api-sdk') ? '@duoduo/api-sdk' : '@duoduo/shared';
        target = path.join(ALIASES[pkgName], spec.slice(pkgName.length).replace(/^\//, ''));
      } else {
        target = path.resolve(path.dirname(file), spec);
      }

      if (!resolves(target)) {
        problems.push({
          file: path.relative(FRONTEND, file).replace(/\\/g, '/'),
          spec,
          resolved: path.relative(FRONTEND, target).replace(/\\/g, '/'),
        });
      }
    }
  }
}

console.log(`import 解析校验：共 ${checked} 条说明符（范围：3 个应用 + 2 个共用包）`);
if (problems.length === 0) {
  console.log('✅ 全部可解析');
  process.exit(0);
}
console.log(`❌ ${problems.length} 条无法解析：`);
for (const p of problems) {
  console.log(`   ${p.file}\n     '${p.spec}' → ${p.file.startsWith('apps/') ? '' : ''}${p.resolved}`);
}
process.exit(1);
