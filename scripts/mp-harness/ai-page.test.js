/**
 * 小程序 AI 页 · 无浏览器自动化测试（真实加载 pages/ai/ai.js）
 *
 * 运行： node --test scripts/mp-harness/ai-page.test.js
 * 前置： 网关在跑（http://127.0.0.1:8080）；AI 服务形态不限（mock / llm 都能过）。
 *
 * 与既有 node 单测的区别：既有的 `r59-ai-suggestions-test.js` 断言的是「把页面逻辑复制出来的一份」，
 * 这份直接跑仓库里那份页面代码 —— 页面改了而测试没改，这里会红。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMiniProgramRuntime } from './runtime.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');

function newRuntime() {
  return createMiniProgramRuntime({ root: path.join(repoRoot, 'miniprogram') });
}

/** 等页面里的异步（wx.request → setData）跑完 */
const settle = (ms = 1500) => new Promise((r) => setTimeout(r, ms));

test('页面能被真实加载并挂载（onLoad 不抛错）', () => {
  const rt = newRuntime();
  const page = rt.mountPage('pages/ai/ai', {});
  assert.ok(page.instance, '页面实例应存在');
  assert.ok(Array.isArray(page.data.messages), 'messages 应为数组');
});

test('首屏渲染快捷问题 chips（服务端下发 + 页面接住）', async () => {
  const rt = newRuntime();
  const page = rt.mountPage('pages/ai/ai', {});
  await settle();
  const quick = page.data.quickQuestions ?? [];
  const wroteQuick = page.setDataCalls.some((c) => 'quickQuestions' in c);
  assert.ok(wroteQuick, '页面应至少 setData 一次 quickQuestions（后端下发 ≠ 前端接了）');
  assert.ok(Array.isArray(quick), 'quickQuestions 应为数组');
});

test('发一条消息会走真实接口并把回复写进 messages', async () => {
  const rt = newRuntime();
  const page = rt.mountPage('pages/ai/ai', {});
  await settle(800);
  // 直接驱动页面的发送逻辑（等价于用户在输入框点发送）
  const sendable = ['send', 'onSend', 'handleSend'].find((k) => typeof page.instance[k] === 'function');
  assert.ok(sendable, '页面应有发送方法（send/onSend/handleSend）');
  if (typeof page.instance.onInput === 'function') {
    page.instance.onInput({ detail: { value: '推荐一款幼猫粮' } });
  } else {
    page.instance.setData({ input: '推荐一款幼猫粮' });
  }
  await page.instance[sendable]();
  await settle(2500);
  const texts = (page.data.messages ?? []).map((m) => m.content ?? '').join('\n');
  assert.ok((page.data.messages ?? []).length >= 2, `应至少有一问一答，实际 ${page.data.messages?.length} 条`);
  assert.ok(texts.includes('幼猫粮'), '应能看到自己发的那句话');
  const replied = (page.data.messages ?? []).some((m) => m.role === 'ai' || m.role === 'assistant' || m.recs);
  assert.ok(replied, '应有 AI 侧的回复（或推荐卡片）');
  // 反向验证：请求必须真的打到网关。否则「页面静默吞掉异常」也会让上面几项全绿
  assert.ok(
    rt.state.requests.some((r) => r.url.includes('/api/duoduo/chat')),
    `应真实发出一次对话请求，实际：${JSON.stringify(rt.state.requests.map((r) => r.url))}`
  );
});

test('换话题时旧的追问 chips 必须一起清掉', async () => {
  const rt = newRuntime();
  const page = rt.mountPage('pages/ai/ai', {});
  await settle(800);
  // followUps 挂在**每条消息**上（页面 data 里没有这个字段）—— 按页面真实结构造状态，
  // 写在页面级 data.followUps 上会永远「通过」，因为页面根本不读它
  page.instance.setData({
    messages: [
      { id: 'old-1', role: 'assistant', content: '这几款比较合适～', followUps: ['再便宜点的有吗', '有没有大包装'] },
    ],
  });
  assert.ok(
    (page.data.messages ?? []).some((m) => (m.followUps ?? []).length > 0),
    '前置：应存在带追问 chips 的历史消息'
  );
  const sendable = ['send', 'onSend', 'handleSend'].find((k) => typeof page.instance[k] === 'function');
  await page.instance[sendable]('我想买猫砂');
  await settle(2500);
  const old = (page.data.messages ?? []).find((m) => m.id === 'old-1');
  assert.ok(old, '原消息应仍在消息流里（清的是 chips，不是消息）');
  assert.equal(
    (old.followUps ?? []).length,
    0,
    `换话题后旧消息不该还挂着上一轮的追问 chips，实际：${JSON.stringify(old.followUps)}`
  );
});
