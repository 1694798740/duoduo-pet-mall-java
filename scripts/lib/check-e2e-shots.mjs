/**
 * e2e 截图输出契约自检（S8 收尾项）—— 纯静态，不需要 Docker / 不需要编译
 *
 * 为什么需要这条守卫：
 *   22 套 e2e 里曾有 20 套把截图写到 `frontend/docs/screenshots`（**受版本控制**），
 *   其中 9 套还写死了 `D:/智能体项目/duoduo-pet-mall-java/...` 绝对路径。
 *   后果是**每跑一次回归，工作区就多出上百个 modified / untracked 的 PNG**
 *   （内容随真实数据与时间戳变化）—— 于是 `git status` 里再也分不清
 *   「我改了代码」和「我刚跑过回归」。
 *
 *   这次的修法是把默认落点收口到 `scripts/lib/e2e-shots.mjs` 的 `shotsDir()`；
 *   但**「默认落到被 gitignore 的目录」这个结论依赖两件事同时成立**：
 *     (a) 每套脚本都真的走 `shotsDir()`；
 *     (b) `.gitignore` 里真的忽略了那个目录。
 *   缺 (b) 时，(a) 不但不能防污染，反而把污染换了个地方 ——
 *   新增一套 e2e 的人照着抄 `const SHOTS = shotsDir();` 是抄不出 (b) 的。
 *   所以这条守卫必须**同时**断言脚本侧与 `.gitignore` 侧（判据 ①②③④）。
 *
 * 用法：
 *   node scripts/lib/check-e2e-shots.mjs            # 检查当前仓库
 *   node scripts/lib/check-e2e-shots.mjs <根目录>    # 指定根（供反向验证用）
 *
 * 退出码：0 = 全部通过；1 = 有违规（逐条点名）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(here, '..', '..');

const E2E_DIR = path.join(REPO_ROOT, 'scripts', 'e2e');
const SHOTS_LIB = path.join(REPO_ROOT, 'scripts', 'lib', 'e2e-shots.mjs');
const GITIGNORE = path.join(REPO_ROOT, '.gitignore');

/** 受版本控制的截图目录 —— 出现在 e2e 脚本里就是「回归会污染工作区」 */
const POLLUTING_LITERAL = 'frontend/docs/screenshots';
/** 默认落点（必须被 .gitignore 覆盖） */
const DEFAULT_SHOTS_REL = 'scripts/e2e/.shots/';

const problems = [];

function bad(msg) {
  problems.push(msg);
}

// ---------- 收集被检查的文件 ----------
if (!fs.existsSync(E2E_DIR)) {
  console.error(`[check-e2e-shots] 找不到目录：${E2E_DIR}`);
  process.exit(1);
}
const suiteFiles = fs
  .readdirSync(E2E_DIR)
  .filter((f) => f.endsWith('-ui-check.mjs'))
  .sort();

// ★ 判据前置：扫到 0 份直接判违规。
//   「什么都没扫到」与「扫了、结果干净」长得一样 —— 路径写错/改名后本守卫会静默变成永真。
if (suiteFiles.length === 0) {
  console.error(`[check-e2e-shots] ★ e2e 套件数为 0（${E2E_DIR}）——判违规：什么都没检查到不等于检查通过`);
  process.exit(1);
}

const shotsLibSrc = fs.existsSync(SHOTS_LIB) ? fs.readFileSync(SHOTS_LIB, 'utf8') : null;
if (shotsLibSrc === null) {
  bad(`① 缺少剧本目录收口模块：scripts/lib/e2e-shots.mjs`);
} else if (!/export\s+function\s+shotsDir\s*\(/.test(shotsLibSrc)) {
  bad('① scripts/lib/e2e-shots.mjs 里没有导出 shotsDir()');
}

// ---------- 逐套断言 ----------
/** 会真正落盘截图的套件数（判据 ② 的「分母」——必须 > 0，否则这条判据在空转） */
let shootingSuites = 0;

for (const f of suiteFiles) {
  const src = fs.readFileSync(path.join(E2E_DIR, f), 'utf8');

  // ② 凡**会落盘截图**的套件，必须走收口：恰好一次 `const SHOTS = shotsDir();`
  //    注意：`home` / `product-detail` 两套不产出截图，因此不该被要求引入 SHOTS。
  //    判据挂在「有没有 screenshot( 调用」上 —— 这是「写盘」这个事实的直接证据。
  const takesShots = /screenshot\s*\(/.test(src);
  const declCount = (src.match(/const SHOTS = shotsDir\(\);/g) ?? []).length;
  if (takesShots) {
    shootingSuites++;
    if (declCount !== 1) {
      bad(`② ${f}：会落盘截图（出现 screenshot( 调用）但 \`const SHOTS = shotsDir();\` 出现 ${declCount} 次（应恰好 1 次）`);
    }
  }

  // ③ 不得把截图写到受版本控制的目录（字面量，含注释里的「示例」也拒绝 —— 抄一次就污染）
  if (src.includes(POLLUTING_LITERAL)) {
    const lines = src.split('\n').flatMap((l, i) => (l.includes(POLLUTING_LITERAL) ? [i + 1] : []));
    bad(`③ ${f}：仍出现 \`${POLLUTING_LITERAL}\`（第 ${lines.join(' / ')} 行）——回归会污染工作区`);
  }

  // ④ 不得写死绝对盘符路径（换机器 / 换检出目录即失效，且失效方式是「静默写去别处」）
  const absHits = src
    .split('\n')
    .flatMap((l, i) => (/['"`][A-Za-z]:[\\/]/.test(l) ? [`${i + 1}: ${l.trim()}`] : []));
  if (absHits.length > 0) {
    bad(`④ ${f}：出现写死盘符的绝对路径 ——\n      ${absHits.join('\n      ')}`);
  }
}

// ---------- 判据 ② 的「空转自证」 ----------
// 「会落盘截图的套件数」一旦变成 0（比如 screenshot 调用被重构掉），判据 ② 就退化成永真。
// 这类「检查还在、但已经不检查任何东西」的哨兵必须自己报出来。
if (shootingSuites === 0) {
  bad('② 没有任何套件出现 screenshot( 调用 —— 判据 ② 在空转，请检查本守卫的匹配条件是否失效');
}

// ---------- .gitignore 侧（判据 ⑤，与脚本侧同等重要） ----------
if (!fs.existsSync(GITIGNORE)) {
  bad('⑤ 缺少 .gitignore —— 默认落点无法被忽略');
} else {
  const ignored = fs
    .readFileSync(GITIGNORE, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .some((l) => l === DEFAULT_SHOTS_REL || l === DEFAULT_SHOTS_REL.replace(/\/$/, ''));
  if (!ignored) {
    bad(`⑤ .gitignore 未忽略 \`${DEFAULT_SHOTS_REL}\` —— 脚本侧改了默认落点，但那个目录仍会被 git 跟踪`);
  }
}

// ---------- 报告 ----------
console.log('[check-e2e-shots] e2e 截图输出契约自检');
console.log(`  仓库根：${REPO_ROOT}`);
console.log(`  被检查的 e2e 套件数：${suiteFiles.length}（其中会落盘截图的：${shootingSuites}）`);
console.log(`  判据：① shotsDir() 收口模块存在  ② 凡落盘截图的套件都走 shotsDir()（且分母 > 0）`);
console.log(`        ③ 无受控目录字面量          ④ 无写死盘符路径  ⑤ .gitignore 真的忽略了默认落点`);
if (problems.length === 0) {
  console.log('  [OK]   五项全通过 —— 跑回归不会再把仓库弄脏');
  console.log('结论：通过');
  process.exit(0);
}
for (const p of problems) console.log(`  [FAIL] ${p}`);
console.log(`结论：不通过（${problems.length} 处）`);
process.exit(1);
