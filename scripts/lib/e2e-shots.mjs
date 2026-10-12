/**
 * e2e 截图输出目录 —— **唯一出口**（S8 收尾项）
 *
 * 为什么需要它（这是一个真实发生过的坑）：
 *   22 套 e2e 里有 20 套写了 `const SHOTS = '<硬编码绝对路径>/frontend/docs/screenshots'`。
 *   于是**每跑一次回归，工作区就多出上百个 modified / untracked 的 PNG** ——
 *   想看清「这次到底改了什么代码」得先在一堆截图噪音里挑。
 *   更糟的是那些 PNG 还会**内容变化**（e2e 用的是真实数据与真实时间戳），
 *   于是 `git status` 里永远分不清「我改了代码」还是「我刚跑过回归」。
 *
 * 现在的口径：
 *   - **默认**写到 `<仓库>/scripts/e2e/.shots/`（已 gitignore）—— 回归不再污染工作区；
 *   - 需要**归档配图**（真的要更新 `frontend/docs/screenshots/` 里的文档用图）时，
 *     显式指定：`SHOTS=frontend/docs/screenshots pwsh ... run-ui-checks.ps1`（相对仓库根或绝对路径均可）；
 *   - 目录不存在**自动创建**（此前 8 套脚本没自己 mkdir，全靠仓库里那个目录恰好存在）。
 *
 * 用法：
 *   import { shotsDir, REPO_ROOT } from '../lib/e2e-shots.mjs';
 *   const SHOTS = shotsDir();
 *   await page.screenshot({ path: path.join(SHOTS, 'home.png') });
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

/** 仓库根（本文件在 `<仓库>/scripts/lib/`，故上溯两级） */
export const REPO_ROOT = path.resolve(here, '..', '..');

/** 默认输出目录（相对仓库根） */
const DEFAULT_REL = path.join('scripts', 'e2e', '.shots');

/**
 * 取截图目录（并确保存在）。
 *
 * @param {string} [sub] 可选子目录（如套件名），便于多套件共用时分类。
 * @returns {string} 绝对路径
 */
export function shotsDir(sub = '') {
  const base = process.env.SHOTS
    ? path.resolve(REPO_ROOT, process.env.SHOTS)
    : path.join(REPO_ROOT, DEFAULT_REL);
  const dir = sub ? path.join(base, sub) : base;
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** 相对仓库根的展示路径（日志里用，比绝对路径好读） */
export function shotsLabel(dir = '') {
  const rel = path.relative(REPO_ROOT, dir || shotsDir());
  return rel.split(path.sep).join('/');
}
