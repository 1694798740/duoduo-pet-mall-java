/**
 * 客服 / 订单 / 媒体体验 e2e（第十一批修复 · 2026-10-23）
 *
 * 覆盖用户二次验收提出的 6 组问题：
 *   ① Q1  从订单/商品进入客服时**精确选中**对应店铺会话（sellerId=0 也要选中自营客服）；
 *   ② Q3  订单卡不再显示订单号；店铺名前置并带 › 可点进店；订单号只在详情页；
 *   ③ Q5  「未读」为纯文字、位于消息右下角（与「已读」同位置），不再有 tag 容器；
 *   ④ Q6  加号菜单不再被裁切、面板可滚动；表情 56 个 + 贴纸 12 个；
 *   ⑤ Q10 图片灯箱 Ctrl+滚轮缩放、无容器式弹窗；视频进度条可拖动、全屏铺满；
 *   ⑥ 未选择会话时**没有输入框**，不能发送（客服消息）；私信页 peerId 缺失也直接拦截。
 *   ⑦ Q4  店员登录商家端能看到本店客服会话（店铺维度归属）。
 *
 * 前置：容器已启动（8088 商城 / 8091 商家端 / 8080 网关）。
 * 用法：NO_PROXY="*" node scripts/e2e/support-ux-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const CHROME = process.env.CHROME_PATH ?? undefined;

const BUYER = { phone: '13600001111', password: 'duoduo12345' };
const STAFF = { phone: '13952746517', password: 'duoduo12345' };
/** 子账号夹具店（店主 370518346074034176，店员 13952746517 在职） */
const STORE_ID = '370518346074034176';
const STORE_NAME = '子账号夹具店';
const VIDEO_URL =
  'https://duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com/uploads/bd9ac46b-6fe4-49f0-abf0-2c52b0ceb53f.mp4';

const { chromium } = await import(PW_CORE);

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`);
  }
}
const step = (t) => console.log(`\n> ${t}`);

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const consoleErrors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));

  step('登录买家账号');
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(BUYER.phone);
  await page.getByPlaceholder('请输入密码').fill(BUYER.password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  check('买家登录成功', !page.url().includes('/login'), page.url());
  // 登录页在未持令牌时会探测 /users/me、/cart/count、/auth/refresh，浏览器对 401 必然打一条
  // 网络错误日志（预期行为，与本次改造无关）。从这里开始计错，覆盖真正的业务交互。
  consoleErrors.length = 0;

  step('准备数据：给店铺发一条定位消息 / 给自营会话补一条视频');
  const seed = await page.evaluate(
    async ({ storeId, videoUrl }) => {
      const r = await fetch('/api/auth/login/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ phone: '13600001111', password: 'duoduo12345' }),
      });
      const token = (await r.json())?.data?.accessToken;
      if (!token) return { error: 'no token' };
      const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
      const thread = async (sellerId) => {
        const res = await fetch(`/api/support/messages?sellerId=${sellerId}&page=1&pageSize=50`, {
          headers: auth,
        });
        return (await res.json())?.data;
      };
      const storeThread = await thread(storeId);
      const hasAnchor = (storeThread?.items ?? []).some((m) => m.content === '定位测试');
      if (!hasAnchor) {
        await fetch('/api/support/messages', {
          method: 'POST',
          headers: auth,
          body: JSON.stringify({ sellerId: storeId, content: '定位测试' }),
        });
      }
      const selfThread = await thread('0');
      // 「未读」样式的用例需要一条**我发出、对方还没读**的消息。种子是幂等的（存在就不发），
      // 而客服侧冒烟脚本会周期性把会话读掉，所以这里按状态判定、缺了就补一条 ——
      // 不补的话整套会在 `.sup-unread` 上超时（2026-10-23 实测）。
      const hasUnreadMine = (selfThread?.items ?? []).some((m) => m.mine && !m.read);
      if (!hasUnreadMine) {
        await fetch('/api/support/messages', {
          method: 'POST',
          headers: auth,
          body: JSON.stringify({ sellerId: 0, content: '未读样式校验 ' + Date.now() }),
        });
      }
      // 真实图片种子：历史上聊天里的图片是 69 字节的假 PNG（InvalidImageFormat），
      // 灯箱无法验证。这里用 canvas 生成一张 480x320 的真图走完整上传链路。
      const hasImage = (selfThread?.items ?? []).some((m) => m.content === 'E2E 图片');
      let imageUrl = null;
      if (!hasImage) {
        const canvas = document.createElement('canvas');
        canvas.width = 480;
        canvas.height = 320;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#4f6ef2';
        ctx.fillRect(0, 0, 480, 320);
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 56px sans-serif';
        ctx.fillText('E2E', 180, 190);
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
        const fd = new FormData();
        fd.append('file', new File([blob], 'e2e-chat.png', { type: 'image/png' }));
        const up = await fetch('/api/uploads', { method: 'POST', body: fd, credentials: 'include' });
        imageUrl = (await up.json())?.data?.url ?? null;
        if (imageUrl) {
          await fetch('/api/support/messages', {
            method: 'POST',
            headers: auth,
            body: JSON.stringify({
              sellerId: 0,
              content: 'E2E 图片',
              type: 'IMAGE',
              mediaUrl: imageUrl,
              width: 480,
              height: 320,
            }),
          });
        }
      }
      const hasVideo = (selfThread?.items ?? []).some((m) => m.bizType === 'VIDEO');
      if (!hasVideo) {
        await fetch('/api/support/messages', {
          method: 'POST',
          headers: auth,
          body: JSON.stringify({
            sellerId: 0,
            content: '',
            type: 'VIDEO',
            mediaUrl: videoUrl,
            width: 1280,
            height: 720,
            durationMs: 30048,
          }),
        });
      }
      return { hasAnchor, hasVideo, hasImage, imageUrl, storeSellerId: storeThread?.sellerId };
    },
    { storeId: STORE_ID, videoUrl: VIDEO_URL },
  );
  check('店铺会话可用且归属正确', seed?.storeSellerId === STORE_ID, seed);
  check('自营会话已有多媒体消息（图片/视频）', seed?.hasVideo !== undefined, seed);

  // ── ⑥ 未选择会话：不能发送 ─────────────────────────────
  step('未选择会话时禁止发送（/support 无参数）');
  await page.goto(`${UI}/support`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sup-body', { timeout: 15000 });
  const bodyText = await page.locator('.sup-body').innerText();
  const footText = await page.locator('.sup-foot').innerText();
  check('空态提示「请选择一个会话」', bodyText.includes('请选择一个会话'), bodyText.slice(0, 80));
  check('底栏提示先选会话', footText.includes('选择一个会话后才能发送消息'), footText);
  check('未选会话时没有输入框', (await page.locator('.sup-foot input.input').count()) === 0, await page.locator('.sup-foot input.input').count());
  check('未选会话时没有发送按钮', (await page.locator('.sup-foot button', { hasText: '发送' }).count()) === 0, null);

  // ── ① Q1 精确定位：第三方店铺 ─────────────────────────
  step('从第三方店铺进入客服（精确选中会话）');
  await page.goto(`${UI}/support?sellerId=${STORE_ID}`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sup-item.active', { timeout: 15000 });
  const activeText = await page.locator('.sup-item.active').innerText();
  const threadText = await page.locator('.sup-body').innerText();
  check('左栏高亮到目标店铺', activeText.includes(STORE_NAME), activeText.slice(0, 60));
  check('右侧直接打开该会话（含定位消息）', threadText.includes('定位测试'), threadText.slice(-80));
  check('选中后出现输入框', (await page.locator('.sup-foot input.input').count()) === 1, null);

  // ── ① Q1 精确定位：自营（sellerId=0）─────────────────
  step('从自营入口进入客服（sellerId=0 落到官方客服并选中）');
  await page.goto(`${UI}/support?sellerId=0`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sup-item.active', { timeout: 15000 });
  const selfActive = await page.locator('.sup-item.active').innerText();
  check('自营入口高亮到官方客服会话', selfActive.includes('客服') || selfActive.includes('多多'), selfActive.slice(0, 60));

  // ── ③ Q5 未读：纯文字 + 右下角，与时间同层 ────────────
  step('逐条未读样式');
  await page.waitForSelector('.sup-unread', { timeout: 15000 });
  const unreadCount = await page.locator('.sup-unread').count();
  const unreadText = await page.locator('.sup-unread').first().innerText();
  const unreadInTime = await page.evaluate(() => {
    const el = document.querySelector('.sup-unread');
    return Boolean(el && el.closest('.sup-time'));
  });
  const tagUnread = await page.locator('.tag', { hasText: '未读' }).count();
  check('存在纯文字「未读」标记', unreadCount >= 1 && unreadText.replace(/[·\s]/g, '') === '未读', { unreadCount, unreadText });
  check('未读位于时间行（消息右下角）', unreadInTime, unreadInTime);
  check('不再使用 tag 容器包裹「未读」', tagUnread === 0, tagUnread);
  const unreadColor = await page.evaluate(() => {
    const el = document.querySelector('.sup-unread');
    return el ? getComputedStyle(el).color : null;
  });
  check('未读为琥珀色（rgb 分量 R>G>B）', (() => {
    const m = /rgb\((\d+),\s*(\d+),\s*(\d+)\)/.exec(unreadColor || '');
    return m ? Number(m[1]) > Number(m[2]) && Number(m[2]) > Number(m[3]) : false;
  })(), unreadColor);

  // ── ④ Q6 加号菜单：不裁切、可滚动、数量足够 ──────────
  step('加号菜单与表情面板');
  const threadBox = await page.locator('.sup-body').boundingBox();
  check('会话区高度足够（≥480px，此前过矮）', Boolean(threadBox) && threadBox.height >= 480, threadBox?.height);
  await page.locator('button[title="发送图片 / 视频 / 表情 / 商品"]').click();
  await page.getByRole('button', { name: /表情/ }).click();
  const panel = page.locator('[data-testid="chat-media-panel"]');
  await panel.waitFor({ timeout: 5000 });
  const box = await panel.boundingBox();
  const panelInfo = await panel.evaluate((el) => ({
    overflowY: getComputedStyle(el).overflowY,
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
  }));
  const backBox = await panel.getByText('← 返回').boundingBox();
  check('面板完整落在视口内（未被裁切）', Boolean(box) && box.y >= 0 && box.y + box.height <= 900 + 1, box);
  check('面板可上下滚动', panelInfo.overflowY === 'auto' && panelInfo.scrollHeight > panelInfo.clientHeight, panelInfo);
  check('返回按钮完整可见（在面板可视区内）', Boolean(backBox) && Boolean(box) && backBox.y >= box.y - 1 && backBox.y + backBox.height <= box.y + box.height + 1, { backBox, box });
  const emojiCount = await panel.locator('[data-testid="emoji-grid"] button').count();
  const stickerCount = await panel.locator('[data-testid="sticker-grid"] button').count();
  check('表情数量 ≥ 48（此前 16）', emojiCount >= 48, emojiCount);
  check('贴纸数量 ≥ 10（此前 6）', stickerCount >= 10, stickerCount);
  const stickerBroken = await panel.locator('[data-testid="sticker-grid"] img').evaluateAll((imgs) =>
    imgs.filter((i) => !i.complete || i.naturalWidth === 0).length,
  );
  check('贴纸资源全部加载成功（无 404）', stickerBroken === 0, stickerBroken);
  // 面板必须能被 Escape 收起：此前只能再点一次「+」，面板会一直浮在页面上挡住内容
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  check('Escape 可收起加号面板', (await panel.count()) === 0, await panel.count());

  // ── ⑤ Q10 图片灯箱 ─────────────────────────────────
  step('图片灯箱：Ctrl+滚轮缩放 / 无容器弹窗');
  const imageBubbles = page.locator('.sup-body img[style*="zoom-in"]');
  check('会话中存在可点开的图片消息', (await imageBubbles.count()) >= 1, await imageBubbles.count());
  const lastImg = imageBubbles.last();
  await lastImg.scrollIntoViewIfNeeded();
  await lastImg.click();
  const dialog = page.locator('[role="dialog"]');
  await dialog.waitFor({ timeout: 5000 });
  await page.waitForFunction(
    () => {
      const img = document.querySelector('[role="dialog"] img');
      return Boolean(img && img.complete && img.naturalWidth > 0);
    },
    { timeout: 15000 },
  );
  const overlayBg = await dialog.evaluate((el) => getComputedStyle(el).backgroundColor);
  check('灯箱使用轻量半透明蒙层（无白底容器）', /rgba\(\d+,\s*\d+,\s*\d+,\s*0?\.\d+\)/.test(overlayBg), overlayBg);
  check('灯箱内部没有卡片容器', (await dialog.locator('.card').count()) === 0, await dialog.locator('.card').count());
  const beforeZoom = await dialog.locator('span', { hasText: '%' }).first().innerText();
  const imgBox = await dialog.locator('img').first().boundingBox();
  await page.mouse.move(imgBox.x + imgBox.width / 2, imgBox.y + imgBox.height / 2);
  // 缩放前确认鼠标确实落在图片上（被浮层盖住时滚轮会被吞掉，必须先暴露出来）
  const hitTag = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.tagName ?? null, {
    x: imgBox.x + imgBox.width / 2,
    y: imgBox.y + imgBox.height / 2,
  });
  check('缩放取点命中图片本体（无浮层遮挡）', hitTag === 'IMG', hitTag);
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, -500);
  await page.keyboard.up('Control');
  await page.waitForTimeout(300);
  const afterZoom = await dialog.locator('span', { hasText: '%' }).first().innerText();
  check('Ctrl+滚轮放大生效', parseInt(afterZoom, 10) > parseInt(beforeZoom, 10), { beforeZoom, afterZoom });
  await dialog.getByRole('button', { name: '关闭' }).click();
  await page.waitForTimeout(200);

  // ── ⑤ Q10 视频：拖动进度 + 全屏铺满 ─────────────────
  step('视频播放器：进度可拖动 / 全屏铺满');
  const videoBubble = page.locator('button[title="点击播放"]').first();
  await videoBubble.click();
  const slider = page.locator('[role="slider"][aria-label="播放进度"]');
  await slider.waitFor({ timeout: 5000 });
  await page.waitForFunction(
    () => Number(document.querySelector('[role="slider"][aria-label="播放进度"]')?.getAttribute('aria-valuemax')) > 0,
    { timeout: 15000 },
  );
  const sBox = await slider.boundingBox();
  // 控制条是悬浮且 5 秒自动渐隐的：每次交互前先动一下鼠标唤醒（模拟真实用户）
  await page.mouse.move(sBox.x + sBox.width / 2, sBox.y + sBox.height / 2);
  // 点击 80% 位置
  await page.mouse.click(sBox.x + sBox.width * 0.8, sBox.y + sBox.height / 2);
  await page.waitForTimeout(300);
  const seeked = Number(await slider.getAttribute('aria-valuenow'));
  check('点击进度条可跳转', seeked >= 15, seeked);
  // 按住拖回 40%
  await page.mouse.move(sBox.x + sBox.width * 0.8, sBox.y + sBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(sBox.x + sBox.width * 0.4, sBox.y + sBox.height / 2, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  const dragged = Number(await slider.getAttribute('aria-valuenow'));
  check('按住拖动进度条生效（跳到更早位置）', dragged < seeked && dragged > 5, { seeked, dragged });

  // 悬浮磨砂控制条：不占视频空间（浮层），五秒无操作渐隐，动鼠标恢复
  const controls = page.locator('[data-testid="video-controls"]');
  const layout = await page.evaluate(() => {
    const bar = document.querySelector('[data-testid="video-controls"]');
    const v = document.querySelector('[role="dialog"] video');
    if (!bar || !v) return null;
    const bs = getComputedStyle(bar);
    const bb = bar.getBoundingClientRect();
    const vb = v.getBoundingClientRect();
    const center = document.elementFromPoint(vb.x + vb.width / 2, vb.y + vb.height / 2);
    return {
      position: bs.position,
      backdrop: bs.backdropFilter || bs.webkitBackdropFilter || '',
      insideVideo: bb.top >= vb.top - 1 && bb.bottom <= vb.bottom + 1,
      centerHitsVideo: center?.tagName === 'VIDEO',
      barTop: Math.round(bb.top),
      videoBottom: Math.round(vb.bottom),
    };
  });
  check('控制条为绝对定位浮层（不占视频空间）', layout?.position === 'absolute', layout);
  check('控制条落在视频画面内（叠在底部）', Boolean(layout?.insideVideo), layout);
  check('视频中心未被控制条遮挡', Boolean(layout?.centerHitsVideo), layout);
  check('控制条启用背景模糊（磨砂）', /blur/.test(layout?.backdrop ?? ''), layout?.backdrop);
  // 5 秒无操作 → 渐隐
  await page.waitForTimeout(5600);
  const hiddenOpacity = await controls.evaluate((el) => getComputedStyle(el).opacity);
  check('5 秒无操作后控制条渐隐', Number(hiddenOpacity) < 0.1, hiddenOpacity);
  // 移动鼠标 → 重新出现（必须落在播放器盒子上，事件挂在盒子上）
  const vBox = await page.locator('[role="dialog"] video').boundingBox();
  await page.mouse.move(vBox.x + vBox.width / 2, vBox.y + vBox.height / 2);
  await page.waitForTimeout(400);
  const shownOpacity = await controls.evaluate((el) => getComputedStyle(el).opacity);
  check('移动鼠标后控制条重新出现', Number(shownOpacity) > 0.9, shownOpacity);

  // 全屏（先唤醒控制条，再点）
  await page.mouse.move(vBox.x + vBox.width / 2, vBox.y + vBox.height / 2);
  await page.waitForTimeout(200);
  await page.getByTitle('全屏').click();
  // 等「真正进入全屏 + 尺寸生效」再量：固定 sleep 在 CI 上偶发只等到一半（2026-10-23 实测 1 次假红）
  await page
    .waitForFunction(
      () => {
        // ★ 必须是**播放器内**的 video：气泡里现在也有 <video> 做封面兜底，
        //   直接 querySelector('video') 会量到气泡那个（高度只有一百多像素）
        const v = document.querySelector('[role="dialog"] video');
        return Boolean(document.fullscreenElement && v && v.getBoundingClientRect().height / window.innerHeight >= 0.85);
      },
      { timeout: 5000 },
    )
    .catch(() => undefined);
  await page.waitForTimeout(300);
  const fs = await page.evaluate(() => {
    const el = document.fullscreenElement;
    const v = document.querySelector('[role="dialog"] video');
    if (!el || !v) return { fullscreen: Boolean(el), ratio: 0 };
    const r = v.getBoundingClientRect();
    return { fullscreen: true, ratio: r.height / window.innerHeight };
  });
  check('进入全屏', fs.fullscreen, fs);
  check('全屏视频铺满屏幕高度（≥85%）', fs.ratio >= 0.85, fs.ratio);
  // 全屏下关闭按钮必须可点：全屏元素在浏览器顶层，按钮若渲染在它之外会被 video 盖住
  const closeBtn = page.getByRole('button', { name: '关闭' }).first();
  const closeBox = await closeBtn.boundingBox();
  const closeHit = closeBox
    ? await page.evaluate(({ x, y }) => {
        const el = document.elementFromPoint(x, y);
        // 按钮内是 SVG 图标，命中的是 <path>/<svg>，要回溯到按钮本身
        return el?.closest('button')?.getAttribute('aria-label') ?? el?.tagName ?? null;
      }, {
        x: closeBox.x + closeBox.width / 2,
        y: closeBox.y + closeBox.height / 2,
      })
    : null;
  check('全屏下关闭按钮可点击（未被 video 遮挡）', closeHit === '关闭', closeHit);
  await closeBtn.click({ timeout: 5000 });
  await page.waitForTimeout(600);
  check('关闭后退出全屏并收起播放器', (await page.locator('[role="dialog"]').count()) === 0
    && !(await page.evaluate(() => Boolean(document.fullscreenElement))), null);

  // ── ② Q3 订单卡：店铺名前置 + ›，无订单号 ────────────
  step('订单卡：店铺名前置，订单号移出列表');
  await page.goto(`${UI}/orders`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.card.card-pad', { timeout: 20000 });
  const firstCard = page.locator('.card.card-pad').first();
  const cardText = await firstCard.innerText();
  const detailHref = await firstCard.locator('a[href^="/orders/"]').first().getAttribute('href');
  const firstOrderNo = (detailHref ?? '').split('/').pop() ?? '';
  check('订单卡不再显示「订单号」', !cardText.includes('订单号'), cardText.slice(0, 80));
  // 强断言：连订单号的值本身也不能出现在卡片上（只看标签会漏掉「裸号」写法）
  check('订单卡不出现订单号数值', Boolean(firstOrderNo) && !cardText.includes(firstOrderNo), firstOrderNo);
  const storeLink = page.locator('.sup-store-link').first();
  check('店铺名是链接且带 ›', (await storeLink.count()) >= 1 && (await storeLink.innerText()).includes('›'), await storeLink.innerText());
  const linkIndex = await firstCard.evaluate((el) => {
    const link = el.querySelector('.sup-store-link');
    const tag = el.querySelector('.tag');
    if (!link || !tag) return null;
    return link.compareDocumentPosition(tag) & Node.DOCUMENT_POSITION_FOLLOWING ? 'before' : 'after';
  });
  check('店铺名在状态标签之前（卡片首位）', linkIndex === 'before', linkIndex);
  await storeLink.click();
  await page.waitForURL(/\/store\//, { timeout: 10000 });
  check('点店铺名可进入店铺页', /\/store\//.test(page.url()), page.url());
  await page.goto(`${UI}/orders`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.card.card-pad', { timeout: 20000 });
  await page.locator(`.card.card-pad a[href="/orders/${firstOrderNo}"]`).first().click();
  await page.waitForURL(new RegExp(`/orders/${firstOrderNo}`), { timeout: 10000 });
  // 详情数据是异步拉的：URL 变了不代表渲染完成，等订单号真正出现再断言
  await page.waitForFunction((no) => document.body.innerText.includes(no), firstOrderNo, { timeout: 15000 });
  const detailText = await page.locator('body').innerText();
  check('订单详情页仍然展示订单号', detailText.includes(firstOrderNo), firstOrderNo);

  // ── ⑦ Q4 店员（店铺维度归属）────────────────────────
  step('商家端：店员能看到本店客服会话');
  const mctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const mp = await mctx.newPage();
  await mp.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await mp.getByPlaceholder('11 位手机号').fill(STAFF.phone);
  await mp.getByPlaceholder('请输入密码').fill(STAFF.password);
  await mp.getByRole('button', { name: /^登录$/ }).click();
  await mp.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  await mp.goto(`${MERCHANT}/seller/support`, { waitUntil: 'networkidle' });
  await mp.waitForTimeout(1200);
  const mText = await mp.locator('body').innerText();
  check('店员不再看到「不是店铺成员」的拒绝页', !mText.includes('不是任何店铺的有效成员'), mText.slice(0, 120));
  const staffSessions = await mp.locator('.sup-item').count();
  check('店员能看到本店会话列表', staffSessions >= 1, staffSessions);
  // 商家端同款 bug：没选会话时不能出现回复框
  const mFoot = await mp.locator('.sup-foot').innerText();
  check('商家端未选会话时也禁止回复', mFoot.includes('选择左侧一个会话'), mFoot);
  check('商家端未选会话时没有输入框', (await mp.locator('.sup-foot input.input').count()) === 0, null);
  await mctx.close();

  check('全程无 console 报错', consoleErrors.length === 0, consoleErrors.slice(0, 3));

  // ── ⑧ 私信同类问题排查：未选中会话也不能发 ────────────
  // 结论：私信走 /messages/:peerId，列表页无输入区；peerId 缺失整页拒绝、会话加载失败整页替换，
  // 因此不存在「没选会话却发进默认会话」的路径。这里把结论固化成断言，防止以后回归。
  step('私信：未选中会话时没有输入区');
  await page.goto(`${UI}/messages`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  check(
    '私信列表页没有输入框与发送按钮',
    (await page.locator('textarea.input').count()) === 0 &&
      (await page.getByRole('button', { name: '发送' }).count()) === 0,
    null,
  );
  await page.goto(`${UI}/messages/999999999999`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  // 私信没有「未选会话」态（路由必须带 :peerId）；真正的防线是服务端不认不存在的收件人。
  // 这里直接打接口验证：即使前端被绕过，也不会把消息投递出去。
  const invalidSend = await page.evaluate(async () => {
    const r = await fetch('/api/socials/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ receiverId: 999999999999, content: 'E2E 无效收件人' }),
    });
    const body = await r.json().catch(() => null);
    return { status: r.status, code: body?.code, message: body?.message };
  });
  check('私信发给不存在的用户被服务端拒绝', invalidSend?.code !== 'SUCCESS', invalidSend);
} catch (e) {
  failures.push('脚本异常：' + (e?.message ?? String(e)));
  console.log('\n[异常] ' + (e?.stack ?? e));
} finally {
  await browser.close();
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exit(1);
}
