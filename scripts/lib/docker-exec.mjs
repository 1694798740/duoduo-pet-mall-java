/**
 * Node 侧执行外部命令（docker / mysql）的**异步**封装
 *
 * 为什么必须异步：本机 Node 的同步派生态（`spawnSync` / `execFileSync`）一律
 * `EBUSY errno -4082`，连 `cmd` 和 `where` 都起不来 —— 但**异步 `spawn` 完全正常**。
 * 所以「脚本能不能建夹具」不取决于能不能调 docker，而取决于用的是哪一套 API。
 * 之前 6 套 e2e 卡在 `execFileSync('docker', ...)` 上，表象是
 * `Error: spawnSync docker EBUSY`，看起来像 docker 不可用，排查方向会整个跑偏。
 *
 * 用法：
 *   import { mysql } from '../lib/docker-exec.mjs';
 *   const rows = await mysql('duoduo_user', "SELECT role FROM t_user WHERE phone='...'");
 */
import { spawn } from 'node:child_process';

/** docker 所在的 PATH：本机 Docker Desktop 的 CLI 不在默认 PATH 里 */
const DOCKER_PATH = 'C:\\Program Files\\Docker\\Docker\\resources\\bin;' + (process.env.PATH ?? '');
/** 避免 docker 去读带 credsStore 的配置（读不到会报 credentials 错误） */
const DOCKER_CONFIG = process.env.DOCKER_CONFIG ?? 'D:\\duoduo-infra\\docker-nocreds';

/**
 * 跑一条命令，等它结束。
 * @returns {Promise<{code:number, out:string, err:string}>}
 */
export function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, PATH: DOCKER_PATH, DOCKER_CONFIG },
      ...opts,
    });
    let out = '';
    let err = '';
    p.stdout?.on('data', (d) => (out += d));
    p.stderr?.on('data', (d) => (err += d));
    p.on('error', reject);
    p.on('close', (code) => resolve({ code: code ?? -1, out, err }));
  });
}

/**
 * 在 duoduo-mysql 容器里执行一条 SQL，返回去掉首尾空白的结果文本。
 * 失败会抛错并带上 stderr —— 静默返回空串会让「UPDATE 没生效」伪装成「查不到」。
 */
export async function mysql(db, query) {
  const r = await run(
    'docker',
    ['exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
      '--default-character-set=utf8mb4', '-D', db, '-e', query],
  );
  if (r.code !== 0) {
    throw new Error(`docker exec mysql 失败（exit=${r.code}）：${r.err.slice(0, 300)}`);
  }
  return r.out.trim();
}
