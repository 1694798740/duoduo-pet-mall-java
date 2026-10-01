/**
 * 小程序「无浏览器」全页面挂载冒烟（真实加载仓库里的每一份页面 js）
 *
 * 运行： node --test scripts/mp-harness/pages-smoke.test.js
 * 前置： 网关在跑（http://127.0.0.1:8080）
 *
 * 覆盖的是什么：小程序没有 DOM，页面真正的「可执行部分」就是 Page() 里那一坨
 * 生命周期与事件方法。这份冒烟把 30 个页面逐个挂载起来跑 onLoad/onShow/onReady，
 * 能挡住三类只在真机上才炸、而静态检查永远看不出来的问题：
 *   1. 页面顶层/生命周期里引用了不存在的模块或方法（require 路径写错、改名漏改）
 *   2. 生命周期里同步抛异常（data 结构变了、取了 undefined 的属性）
 *   3. 卸载时清理逻辑写错（onUnload 里调了不存在的方法）
 *
 * 不覆盖什么：wxml 渲染与样式（那是 WebView 侧的活，本方案不pretend能测）。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMiniProgramRuntime } from './runtime.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const pagesRoot = path.join(repoRoot, 'miniprogram', 'pages');

/** 扫描所有页面（目录/文件同名 js，如 pages/ai/ai.js、pages/index/index.js） */
function discoverPages() {
  const out = [];
  for (const dir of fs.readdirSync(pagesRoot)) {
    const abs = path.join(pagesRoot, dir);
    if (!fs.statSync(abs).isDirectory()) continue;
    const file = path.join(abs, dir + '.js');
    if (fs.existsSync(file)) out.push('pages/' + dir + '/' + dir);
  }
  return out.sort();
}

const PAGES = discoverPages();

/** 汇总所有页面用到的「运行时未显式实现」的 wx API —— 兜底桩不能是静默黑洞 */
const unknownWxApis = new Set();

test('页面扫描结果不为空（防止路径写错后「0 个页面全过」的假绿）', () => {
  assert.ok(PAGES.length >= 20, `应扫到 20+ 个页面，实际 ${PAGES.length}`);
});

for (const pagePath of PAGES) {
  test(`挂载 ${pagePath}：加载 + onLoad/onShow/onReady + onUnload 均不抛错`, async () => {
    const rt = createMiniProgramRuntime({ root: path.join(repoRoot, 'miniprogram') });
    let page;
    assert.doesNotThrow(() => {
      page = rt.mountPage(pagePath, {});
    }, `${pagePath} 加载/挂载阶段抛错`);
    assert.ok(page?.instance, `${pagePath} 应产生页面实例`);

    // 等首屏异步请求回落：这里不断言业务结果（各页面差异大），
    // 只要求「异步回落不把进程打崩」——崩了会被下面的 rejection 监听捕获
    await new Promise((r) => setTimeout(r, 600));
    for (const api of rt.state.unknownWxApis) unknownWxApis.add(api);
    assert.doesNotThrow(() => page.unload(), `${pagePath} 的 onUnload 抛错`);
  });
}

// 异步回落的异常不会在上面的同步断言里冒出来，单独挂一个监听统一收集
const unhandled = [];
process.on('unhandledRejection', (reason) => {
  unhandled.push(String(reason && reason.stack ? reason.stack : reason));
});

test('全部页面挂载过程中没有未处理的 Promise 异常', () => {
  // 只过滤本轮产生的；页面依赖网关，个别请求失败是**预期内**的（会被页面 catch 掉）
  const fatal = unhandled.filter((s) => !/fetch failed|ECONNREFUSED|UNAUTHORIZED/i.test(s));
  assert.equal(fatal.length, 0, `存在未处理异常：\n${fatal.join('\n---\n')}`);
});

test('[审计] 运行时未显式实现的 wx API 清单（兜底桩不静默吞，跑完可看）', () => {
  const list = [...unknownWxApis].sort();
  // 这里刻意**不判定失败**：列出清单是为了让人能核对有没有拼错的 API，
  // 而不是让「基础库又多了个新 API」变成红灯。
  console.log(`[mp-harness] 兜底桩接管了 ${list.length} 个 wx API：${list.join(', ')}`);
  assert.ok(true);
});
