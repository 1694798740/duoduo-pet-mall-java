/**
 * `check-e2e-shots.mjs` 的**反向验证**（证明守卫真在判，不是永真）
 *
 * 判据要能被证伪，否则「通过」没有信息量。本脚本把守卫逐条判据**故意改坏**，
 * 要求它每一次都 exit 1 且**点名到具体文件/行号/判据编号**；再还原复跑必须回绿。
 *
 * 沙箱纪律（夹具三纪律）：现场建 / 跑完自毁 / 建不起或删不掉都显式报红 ——
 * 沙箱是 `[系统临时目录]/tmp-344-shots-reverse`，全程只读仓库、只写沙箱。
 *
 * 用法：node scripts/lib/check-e2e-shots-reverse.mjs
 * 预期：`SUMMARY: 9 cases, 0 failed`，退出码 0。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(here, '..', '..');
const GUARD = path.join(REPO, 'scripts', 'lib', 'check-e2e-shots.mjs');
const SANDBOX = path.join(os.tmpdir(), 'tmp-344-shots-reverse');

// ★ 必须用**异步** spawn：本机 Node 的同步派生态（spawnSync / execFileSync）
//   一律 `EBUSY errno -4082`，表现为 status=null 且**无任何输出** ——
//   看起来像「守卫根本没跑」，排查方向会整个跑偏（首版就栽在这里）。
const { run } = await import(new URL('./docker-exec.mjs', import.meta.url).href);

let cases = 0;
let failed = 0;
function judge(name, ok, detail) {
  cases++;
  if (ok) console.log(`  [OK]   ${name}`);
  else {
    failed++;
    console.log(`  [FAIL] ${name}${detail ? `  ${detail}` : ''}`);
  }
}

/** 现场建沙箱：只拷守卫要用到的三类文件 */
function buildSandbox() {
  try {
    fs.rmSync(SANDBOX, { recursive: true, force: true });
    fs.mkdirSync(path.join(SANDBOX, 'scripts/e2e'), { recursive: true });
    fs.mkdirSync(path.join(SANDBOX, 'scripts/lib'), { recursive: true });
    for (const f of fs.readdirSync(path.join(REPO, 'scripts/e2e'))) {
      if (f.endsWith('-ui-check.mjs')) {
        fs.copyFileSync(path.join(REPO, 'scripts/e2e', f), path.join(SANDBOX, 'scripts/e2e', f));
      }
    }
    for (const f of ['e2e-shots.mjs', 'docker-exec.mjs']) {
      fs.copyFileSync(path.join(REPO, 'scripts/lib', f), path.join(SANDBOX, 'scripts/lib', f));
    }
    fs.copyFileSync(path.join(REPO, '.gitignore'), path.join(SANDBOX, '.gitignore'));
  } catch (e) {
    // 建不起就显式报红，不做「跳过」——跳过等于没验
    judge(`沙箱可建（${SANDBOX}）`, false, String(e));
    throw e;
  }
}

async function runGuard() {
  const r = await run(process.execPath, [GUARD, SANDBOX]);
  return { code: r.code, out: `${r.out}${r.err}` };
}

const firstSuite = () =>
  fs.readdirSync(path.join(SANDBOX, 'scripts/e2e')).filter((f) => f.endsWith('-ui-check.mjs')).sort()[0];
const patch = (rel, fn) => {
  const p = path.join(SANDBOX, rel);
  fs.writeFileSync(p, fn(fs.readFileSync(p, 'utf8')), 'utf8');
};

// ---------- 0. 基线：沙箱未注入必须通过 ----------
buildSandbox();
{
  const { code, out } = await runGuard();
  judge('基线（沙箱未注入）→ exit 0', code === 0, `exit=${code}\n${out}`);
}

// ---------- 1. 删掉收口模块 → 判据 ① ----------
buildSandbox();
fs.rmSync(path.join(SANDBOX, 'scripts/lib/e2e-shots.mjs'));
{
  const { code, out } = await runGuard();
  judge('注入① 删掉 e2e-shots.mjs → exit 1 且点名缺少收口模块', code === 1 && out.includes('缺少剧本目录收口模块'), `exit=${code}`);
}

// ---------- 2. 某套退回硬编码受控目录 → 判据 ②③ ----------
buildSandbox();
{
  const f = firstSuite();
  patch(`scripts/e2e/${f}`, (s) => s.replace('const SHOTS = shotsDir();', "const SHOTS = 'D:/x/frontend/docs/screenshots';"));
  const { code, out } = await runGuard();
  judge(
    `注入② ${f} 退回硬编码受控目录 → exit 1 且点名 ②③`,
    code === 1 && out.includes('②') && out.includes('frontend/docs/screenshots'),
    `exit=${code}`,
  );
}

// ---------- 3. 加一行写死盘符的绝对路径 → 判据 ④ ----------
buildSandbox();
{
  const f = firstSuite();
  patch(`scripts/e2e/${f}`, (s) => `${s}\nconst OUT = 'D:\\\\somewhere\\\\x.png';\n`);
  const { code, out } = await runGuard();
  judge(`注入③ ${f} 加绝对盘符路径 → exit 1 且点名 ④`, code === 1 && out.includes('④'), `exit=${code}`);
}

// ---------- 4. .gitignore 去掉默认落点 → 判据 ⑤（最容易被漏的一条） ----------
buildSandbox();
patch('.gitignore', (s) => s.replace('scripts/e2e/.shots/', ''));
{
  const { code, out } = await runGuard();
  judge('注入④ .gitignore 去掉 scripts/e2e/.shots/ → exit 1 且点名 ⑤', code === 1 && out.includes('⑤'), `exit=${code}`);
}

// ---------- 5. 清掉全部 screenshot( 调用 → 判据 ② 的空转自证 ----------
buildSandbox();
{
  for (const f of fs.readdirSync(path.join(SANDBOX, 'scripts/e2e'))) {
    if (f.endsWith('-ui-check.mjs')) patch(`scripts/e2e/${f}`, (s) => s.replace(/screenshot\s*\(/g, 'shot_(/*disabled*/'));
  }
  const { code, out } = await runGuard();
  judge('注入⑤ 清掉全部 screenshot( 调用 → exit 1 且点名 ② 在空转', code === 1 && out.includes('空转'), `exit=${code}`);
}

// ---------- 6. 套件数为 0（目录改名/清空）→ 「什么都没扫到」≠ 通过 ----------
buildSandbox();
for (const f of fs.readdirSync(path.join(SANDBOX, 'scripts/e2e'))) {
  if (f.endsWith('-ui-check.mjs')) fs.rmSync(path.join(SANDBOX, 'scripts/e2e', f));
}
{
  const { code, out } = await runGuard();
  judge('注入⑥ 清空套件目录 → exit 1（什么都没扫到 ≠ 通过）', code === 1 && out.includes('套件数为 0'), `exit=${code}`);
}

// ---------- 7. 还原后复跑必须回绿 ----------
buildSandbox();
{
  const { code } = await runGuard();
  judge('还原后复跑 → exit 0', code === 0, `exit=${code}`);
}

// ---------- 8. 沙箱自毁 ----------
try {
  fs.rmSync(SANDBOX, { recursive: true, force: true });
  judge('沙箱已自毁', !fs.existsSync(SANDBOX), '自毁失败 —— 会在临时目录留下垃圾');
} catch (e) {
  judge('沙箱已自毁', false, String(e));
}

console.log(`\nSUMMARY: ${cases} cases, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
