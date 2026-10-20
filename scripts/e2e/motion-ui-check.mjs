/**
 * 动效与渲染性能回归（2026-10-19 动效统一）
 *
 * 为什么要单独一套：动效是**最容易悄悄失效**的一类改动 ——
 * 样式表被后面的规则覆盖、动画被 `animation-fill-mode` 钉死、降级守卫漏掉某个
 * 媒体查询，页面看起来都"能点能跳"，只有手感变了。而手感没法靠截图对出来，
 * 只能把「计算样式」钉成断言。
 *
 * 覆盖：
 *   1. 动效令牌：:root 里必须有 时长 / 缓动 / 组合过渡 三组令牌；
 *   2. 关键元素真的引用了令牌（按钮 / 输入框 / 商品卡）；
 *   3. 全站不再出现 `transition: all`（layout 属性进过渡 = 每帧重排）；
 *   4. 微交互实测：卡片悬浮抬升、图片缓推；
 *   5. 渲染性能：列表图片带 loading="lazy"、卡片带 containment、表格行有过渡；
 *   6. 路由切换：内容容器带 .route-enter，且填充模式是 backwards；
 *   7. 按压反馈：筛选 chip 按住时 transform 生效；
 *   8. 降级守卫：系统开了「减少动态效果」后，过渡时长被压到 5ms 内、位移被归零。
 *
 * 两个踩过的坑（改这套脚本时别再踩）：
 *   · **别拿 `<a class="btn">` 测按压**：鼠标按下再抬起就是一次点击，
 *     商城顶栏第一个 .btn 是「登录 / 注册」，按下去整页就跳走了，
 *     后面的图片 / containment 断言会在登录页上跑，全部假红。
 *     按压只测 `<button>`（搜索页的筛选 chip）。
 *   · **时长别比字符串**：Chrome 把 `0.08s` 序列化成 `.08s`，写死字符串必跨版本假红，
 *     一律 parseFloat 后比数值。
 *
 * 用法（必须显式给基址，默认 5173 是 dev server，打到它整套都会失败）：
 *   $env:UI_BASE = "http://localhost:8088"
 *   node scripts/e2e/motion-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const CHROME = process.env.CHROME_PATH ?? undefined;

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
const num = (v) => Number.parseFloat(String(v ?? ''));

/** 读一个元素的计算样式 */
const css = (page, selector, prop) =>
  page.evaluate(
    ([sel, p]) => {
      const el = document.querySelector(sel);
      return el ? getComputedStyle(el).getPropertyValue(p).trim() : null;
    },
    [selector, prop],
  );

/** 在已挂载样式表里按选择器找规则（含媒体查询内部） */
const findRules = (page, selectorRe, prop) =>
  page.evaluate(
    ([re, p]) => {
      const out = [];
      const matcher = new RegExp(re);
      for (const sheet of document.styleSheets) {
        let rules;
        try {
          rules = sheet.cssRules;
        } catch {
          continue; // 跨源样式表读不到；本仓同源，正常不会命中
        }
        const walk = (list) => {
          for (const r of list) {
            if (r.cssRules) walk(r.cssRules);
            if (r.selectorText && matcher.test(r.selectorText.trim())) {
              out.push(String(r.style[p] ?? ''));
            }
          }
        };
        walk(rules);
      }
      return out;
    },
    [selectorRe, prop],
  );

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

try {
  const normal = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await normal.goto(UI, { waitUntil: 'networkidle' });

  // ---------------- 1. 动效令牌 ----------------
  step('1 · 动效令牌：时长 / 缓动 / 组合过渡 三组都存在');
  const tokens = await normal.evaluate(() => {
    const s = getComputedStyle(document.documentElement);
    const names = [
      '--dur-instant', '--dur-fast', '--dur-base', '--dur-slow', '--dur-enter',
      '--ease-out', '--ease-in-out', '--ease-spring',
      '--t-colors', '--t-outline', '--t-chip', '--t-field',
      '--t-lift', '--t-button', '--t-fade', '--t-progress', '--t-press',
    ];
    return Object.fromEntries(names.map((n) => [n, s.getPropertyValue(n).trim()]));
  });
  const missing = Object.entries(tokens).filter(([, v]) => !v).map(([k]) => k);
  check('17 个动效令牌全部有值（缺一个说明有人绕开令牌自己写了时长）', missing.length === 0, missing);
  check('时长收成 5 档且值正确（80 / 150 / 200 / 400 / 240 ms）',
    num(tokens['--dur-instant']) === 0.08 && num(tokens['--dur-fast']) === 0.15 &&
    num(tokens['--dur-base']) === 0.2 && num(tokens['--dur-slow']) === 0.4 &&
    num(tokens['--dur-enter']) === 0.24,
    [tokens['--dur-instant'], tokens['--dur-fast'], tokens['--dur-base'], tokens['--dur-slow'], tokens['--dur-enter']]);
  check('缓动是三条曲线（默认减速 / 对称 / 轻微过冲），不是清一色 ease',
    [tokens['--ease-out'], tokens['--ease-in-out'], tokens['--ease-spring']].every((v) => /cubic-bezier/.test(v)),
    [tokens['--ease-out'], tokens['--ease-in-out'], tokens['--ease-spring']]);

  // ---------------- 2. 关键元素引用令牌 ----------------
  step('2 · 关键元素的过渡必须是令牌展开值，不是裸时长');
  const btnTransition = await css(normal, '.btn', 'transition');
  check('按钮 .btn 的过渡含 background 与 transform',
    Boolean(btnTransition) && /background/.test(btnTransition) && /transform/.test(btnTransition),
    btnTransition);
  check('★ 按钮不再用 `transition: all`（那会把 width/height 也拖进过渡，每帧重排）',
    Boolean(btnTransition) && !/(^|[\s,])all([\s,]|$)/.test(btnTransition), btnTransition);

  const cardTransition = await css(normal, '.goods-card', 'transition');
  check('商品卡过渡含 transform 与 box-shadow（悬浮抬升要有）',
    Boolean(cardTransition) && /transform/.test(cardTransition) && /box-shadow/.test(cardTransition),
    cardTransition);

  const inputTransition = await css(normal, '.input', 'transition');
  check('输入框过渡含 border-color 与 box-shadow（聚焦有光晕，不是硬切）',
    Boolean(inputTransition) && /border-color/.test(inputTransition) && /box-shadow/.test(inputTransition),
    inputTransition);

  // ---------------- 3. 全站不得再有 transition: all ----------------
  step('3 · 样式表里不得残留 `transition: all`');
  const allCount = await normal.evaluate(() => {
    let n = 0;
    for (const sheet of document.styleSheets) {
      let rules;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      const walk = (list) => {
        for (const r of list) {
          if (r.cssRules) walk(r.cssRules);
          const t = r.style?.transitionProperty || r.style?.transition;
          if (t && /(^|[\s,])all([\s,]|$)/.test(String(t))) n++;
        }
      };
      walk(rules);
    }
    return n;
  });
  check('★ 已挂载样式表里 `transition: all` 数量为 0', allCount === 0, allCount);

  // ---------------- 4. 卡片悬浮 / 图片缓推 ----------------
  step('4 · 微交互实测：卡片悬浮抬升 + 图片缓推');
  const card = normal.locator('.goods-card').first();
  check('首页渲染出了商品卡（否则悬浮断言无处可测）', (await card.count()) > 0, await card.count());
  if ((await card.count()) > 0) {
    const before = await card.evaluate((el) => getComputedStyle(el).transform);
    await card.hover();
    await normal.waitForTimeout(320); // 等过渡走完（--dur-base = 0.2s）
    const after = await card.evaluate((el) => getComputedStyle(el).transform);
    check('★ 悬浮商品卡后 transform 真的变了（不是只在 CSS 里写了 hover）',
      before !== after && after !== 'none', { before, after });

    const imgScale = await normal.evaluate(() => {
      const img = document.querySelector('.goods-card:hover .goods-media img');
      return img ? getComputedStyle(img).transform : null;
    });
    check('★ 卡片图片在悬浮时放大（图片缓推）', Boolean(imgScale) && imgScale !== 'none', imgScale);
  }

  // ---------------- 5. 渲染性能（仍在首页，趁商品卡还在） ----------------
  step('5 · 渲染性能：图片懒加载 / 卡片 containment / 表格行过渡');
  const imgStats = await normal.evaluate(() => {
    const imgs = [...document.querySelectorAll('img')];
    return {
      total: imgs.length,
      lazy: imgs.filter((i) => i.getAttribute('loading') === 'lazy').length,
      async: imgs.filter((i) => i.getAttribute('decoding') === 'async').length,
    };
  });
  check('首页确实渲染出图片（否则懒加载断言是空的、等于没测）', imgStats.total > 0, imgStats);
  check('★ 列表图片带 loading="lazy"（首屏外的图不再抢带宽）',
    imgStats.lazy > 0 && imgStats.lazy > imgStats.total / 2, imgStats);
  check('★ 图片带 decoding="async"（解码不阻塞主线程）',
    imgStats.async > 0 && imgStats.async > imgStats.total / 2, imgStats);

  const cardContain = await css(normal, '.goods-card', 'contain');
  check('商品卡带 containment（grid 里几十张卡时限制重排范围）',
    Boolean(cardContain) && /layout/.test(cardContain), cardContain);

  const rowRules = await findRules(normal, '^\\.table tbody tr$', 'transition');
  // 断言要兼容两种写法：直接写 background（旧）或引用组合令牌 var(--t-colors)（现用）。
  // 只匹配 background 会把「已经令牌化」的正确实现判成红。
  check('★ 表格行有背景色过渡（鼠标扫过不是一排硬切色块）',
    rowRules.some((t) => /background/.test(t) || /var\(--t-/.test(t)), rowRules);

  // ---------------- 6. 路由切换动画 ----------------
  step('6 · 路由切换：内容容器带 .route-enter');
  const routeInfo = await normal.evaluate(() => {
    const el = document.querySelector('.route-enter');
    if (!el) return null;
    const s = getComputedStyle(el);
    return { anim: s.animationName, dur: s.animationDuration, fill: s.animationFillMode };
  });
  check('★ 页面内容容器存在且带 route-in 动画', Boolean(routeInfo) && routeInfo.anim === 'route-in', routeInfo);
  check('★ 入场动画填充模式是 backwards（用 both 会把 transform 钉死，悬浮从此失效）',
    Boolean(routeInfo) && routeInfo.fill === 'backwards', routeInfo?.fill);

  // ---------------- 7. 按压反馈（只测 <button>，避免点击跳走） ----------------
  step('7 · 按压反馈：筛选 chip 按住时 transform 生效');
  await normal.goto(`${UI}/search`, { waitUntil: 'networkidle' });
  await normal.waitForTimeout(400);
  const chip = normal.locator('button.tag').first();
  const chipCount = await chip.count();
  check('搜索页有可点击的筛选 chip（button.tag）', chipCount > 0, chipCount);
  if (chipCount > 0) {
    const box = await chip.boundingBox();
    if (box) {
      await normal.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await normal.mouse.down();
      await normal.waitForTimeout(160);
      const activeT = await chip.evaluate((el) => getComputedStyle(el).transform);
      await normal.mouse.up();
      check('★ 按住 chip 时 transform 生效（按压回弹，不是硬切）', activeT !== 'none', activeT);
    }
  }
  await normal.close();

  // ---------------- 8. 降级守卫 ----------------
  step('8 · 降级守卫：开启「减少动态效果」后动画必须让位');
  const reduced = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  await reduced.goto(UI, { waitUntil: 'networkidle' });
  const rBtn = await css(reduced, '.btn', 'transition-duration');
  // 计算值形如 ".00001s, .00001s, …"（每个过渡属性一份），取最大值与阈值比。
  const maxDur = (rBtn ?? '')
    .split(',')
    .map((s) => num(s))
    .filter((n) => Number.isFinite(n))
    .reduce((a, b) => Math.max(a, b), 0);
  check('★ 减少动态效果下，按钮过渡时长被压到 5ms 以内（不是照旧播放 0.15s）',
    Boolean(rBtn) && maxDur < 0.005, { raw: rBtn, maxDur });

  const rCardHover = await reduced.evaluate(() => {
    for (const sheet of document.styleSheets) {
      let rules;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      for (const r of rules) {
        if (r.type === CSSRule.MEDIA_RULE && /prefers-reduced-motion/.test(r.conditionText)) {
          for (const inner of r.cssRules) {
            if (/\.goods-card:hover/.test(inner.selectorText ?? '') && inner.style?.transform === 'none') {
              return true;
            }
          }
        }
      }
    }
    return false;
  });
  check('★ 降级守卫里显式把卡片悬浮位移归零（只保留颜色/透明度的即时切换）',
    rCardHover === true, rCardHover);
  await reduced.close();

  console.log(`\n=== 动效与渲染性能断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
  if (failures.length) {
    console.log('失败项：');
    for (const f of failures) console.log(`  - ${f}`);
    process.exitCode = 1;
  }
} catch (e) {
  console.error(e);
  process.exitCode = 1;
} finally {
  await browser.close();
}
