/**
 * 小程序「无浏览器」运行时 · **行为断言**
 *
 * 与 pages-smoke.test.js 的分工：
 *   · pages-smoke 只证明「30 个页面挂得起来、生命周期不抛错」—— 那是最低水位，
 *     页面逻辑写错了（拉不到数据、算错价格、请求体发错）它一条都抓不到；
 *   · 本文件反过来：**针对具体业务行为下断言**，让「页面改坏了测试就红」这件事真的成立。
 *
 * 覆盖六条最容易坏且后果最重的行为：
 *   1. 首页：真拉商品流并渲染（items 非空、id 是**字符串**、价格文案与图片都装配好）；
 *   2. 购物车改数量：请求体里 skuId 必须是**字符串** —— 18~19 位 id 一旦被
 *      Number() 转换就丢了精度，服务端 404，用户视角是「点了没反应」。
 *      **Python 探针天然传字符串，永远测不出这类缺陷，只有执行真实前端代码才行**；
 *   3. 下单：请求体带 addressId 与 idempotentKey，成功后跳订单列表（不是 switchTab
 *      ——orders 早就不是 tab 页了，switchTab 对非 tab 页**静默失败**）；
 *   4. 商品详情「加入购物车」：请求体 skuId 字符串 + quantity 数字；
 *   5. 确认订单：**金额三行（商品金额 / 抵扣 / 应付）必须自洽** —— 尤其是
 *      「服务端没有推荐券」这条分支，它曾让「应付」停在初值 0；
 *   6. 领券中心：**取数时机** —— onLoad/onShow 连着触发只能发一次请求，
 *      但超过节流窗口后必须真的重新取数（上界与下界一起钉，防「守卫吃掉功能」）。
 *
 * 写接口一律用桩拦住（`runtime.stub`）：行为断言要验「发了什么」，
 * 但不能真在库里留下订单/购物车条目 —— 上一次跑留下的脏数据会污染下一次，
 * 而且报错的是**下一个脚本**，最难归因。
 *
 * 用法：node scripts/mp-harness/behavior.test.js
 */
import assert from 'node:assert/strict';
import { createMiniProgramRuntime } from './runtime.js';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const PWD = 'duoduo12345';

let pass = 0;
const failures = [];
function check(name, fn) {
  return Promise.resolve()
    .then(fn)
    .then(() => {
      pass++;
      console.log(`  [PASS] ${name}`);
    })
    .catch((e) => {
      failures.push(name);
      console.log(`  [FAIL] ${name}\n        ${e.message}`);
    });
}
const step = (t) => console.log(`\n> ${t}`);

/** 等一小会儿：页面里的请求都是 Promise 链，挂载完不会立刻落地 */
const tick = (ms = 400) => new Promise((r) => setTimeout(r, ms));

// ---------------- HTTP 侧：注册一个买家并拿令牌 ----------------
async function post(p, body) {
  const res = await fetch(GW + p, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  return { status: res.status, data: json?.data, json };
}

/**
 * 注册 + 登录拿访问令牌。
 *
 * 两个坑（都是「拿不到令牌 → 一堆 401 → 排查方向跑偏到权限逻辑」的元凶）：
 *  1. `/api/auth/register` 的响应体是**扁平 UserView**，不带令牌；
 *  2. `/api/auth/login` 是**浏览器契约**，令牌走 httpOnly Cookie，小程序用不了 ——
 *     小程序走的是 `/api/auth/login/token`（响应体直接给 accessToken/refreshToken）。
 */
async function newBuyer() {
  const phone = '137' + String(Math.floor(1e7 + Math.random() * 8e7));
  await post('/api/auth/register', { phone, password: PWD, nickname: '小程序行为测试' });
  const login = await post('/api/auth/login/token', { phone, password: PWD });
  return { phone, token: login.data?.accessToken };
}

const buyer = await newBuyer();
assert.ok(buyer.token, `拿不到买家令牌：${JSON.stringify(buyer)}`);

// ---------------- 运行时 ----------------
const mp = createMiniProgramRuntime({ root: 'miniprogram', gateway: GW });
// 登录态：小程序把访问令牌存在 storage，键名见 utils/request.js 的 TOKEN_KEY
mp.state.storage.set('duoduo_access_token', buyer.token);

// ================= 1. 首页：商品流渲染 =================
step('首页：真实拉取商品流并渲染');
{
  const page = mp.mountPage('pages/index/index');
  await tick(900);

  await check('首页拉到了商品（items 非空）', () => {
    assert.ok(page.data.items.length > 0, `items=${JSON.stringify(page.data.items).slice(0, 160)}`);
  });
  await check('★ 商品 id 是**字符串**（长 id 走数字会掉精度）', () => {
    const id = page.data.items[0].id;
    assert.equal(typeof id, 'string', `id=${JSON.stringify(id)}（${typeof id}）`);
  });
  await check('商品装好了价格文案（分 → 元）', () => {
    const t = page.data.items[0].priceText;
    assert.ok(typeof t === 'string' && t.length > 0, `priceText=${JSON.stringify(t)}`);
  });
  await check('商品装好了图片地址（相对路径 SVG 已映射成本地位图）', () => {
    const src = page.data.items[0].imageSrc;
    assert.ok(typeof src === 'string' && src.length > 0, `imageSrc=${JSON.stringify(src)}`);
  });
  await check('首屏骨架已收起（firstLoading=false，否则界面永远转圈）', () => {
    assert.equal(page.data.firstLoading, false, page.data.firstLoading);
  });
  await check('请求真的打到了 /api/products', () => {
    const hit = mp.state.requests.some((r) => r.url.includes('/api/products'));
    assert.ok(hit, mp.state.requests.slice(-4).map((r) => r.url));
  });
  await check('首页没有把加载错误写进 data.error', () => {
    assert.equal(page.data.error, '', page.data.error);
  });
  page.unload();
}

/** 带令牌的请求（给一次性买家做夹具用；断言本身不走这条路） */
async function authed(method, p, body) {
  const res = await fetch(GW + p, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyer.token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, data: (await res.json().catch(() => null))?.data };
}

/** 购物车塞一条（真实库存，保证后续条目是服务端口径拼出来的，不是我手编的） */
await authed('POST', '/api/cart/items', { skuId: '2001', quantity: 1 });

// ================= 2. 购物车：改数量的请求体 =================
step('购物车：改数量（写接口用桩拦截）');
{
  const unstubUpdate = mp.stub(/\/api\/cart\/items\/[^/]+$/, {});
  const page = mp.mountPage('pages/cart/cart');
  await tick(1200);

  await check('购物车拿到了条目（items 非空）', () => {
    assert.ok(page.data.items.length > 0, `items=${JSON.stringify(page.data.items).slice(0, 200)}`);
  });

  const first = page.data.items[0] ?? { skuId: '2001', quantity: 1 };
  const beforeCount = mp.state.requests.length;
  if (page.data.items.length > 0) {
    page.instance.inc({ currentTarget: { dataset: { sku: first.skuId } } });
  }
  await tick(700);

  const calls = mp.state.requests.slice(beforeCount).filter((r) => r.url.includes('/api/cart/items'));
  await check('点了「+」真的发了改数量请求', () => {
    assert.ok(calls.length > 0, `新增请求=${JSON.stringify(mp.state.requests.slice(beforeCount))}`);
  });
  if (calls.length > 0) {
    // 改数量的 skuId 在 **URL 里**（PATCH /api/cart/items/{skuId}），不在请求体 ——
    // 断言必须跟着走：拿 URL 尾段与页面 data 里的 id 做**字符串**全等比较。
    // 页面若写了 Number(skuId)，19 位尾数会被改掉，这个断言立刻红。
    await check('★ URL 里的 skuId 与页面 data 里的 id **字符串全等**（长 id 被 Number() 就会露馅）', () => {
      const url = calls[0].url;
      const tail = url.split('/api/cart/items/')[1].split('?')[0];
      assert.equal(typeof first.skuId, 'string', `data.skuId=${JSON.stringify(first.skuId)}`);
      assert.equal(tail, first.skuId, { urlTail: tail, dataId: first.skuId });
    });
    await check('请求体 quantity 是数字且比原来 +1', () => {
      const body = calls[0].data ?? {};
      assert.equal(typeof body.quantity, 'number', `quantity=${JSON.stringify(body.quantity)}`);
      assert.equal(body.quantity, Number(first.quantity) + 1, { got: body.quantity, from: first.quantity });
    });
    await check('请求用的是 PATCH（写操作不落 GET，避免被预取/重试误触发）', () => {
      assert.equal(calls[0].method, 'PATCH', calls[0].method);
    });
  }
  unstubUpdate();
  page.unload();
}

// ================= 3. 下单：请求体 + 跳转 =================
step('下单：请求体与跳转（订单不落库）');
{
  let placedBody = null;
  const unstubAddr = mp.stub(/\/api\/addresses\/default/, {
    id: '710000000000000001',
    receiverName: '小林',
    receiverPhone: '13900000000',
    fullAddress: '上海市浦东新区张江路 1 号',
  });
  const unstubOrder = mp.stub('/api/orders', (opts) => {
    placedBody = opts;
    return { orderNo: '202610010000000001', orderCount: 1, payAmount: 100 };
  });
  const page = mp.mountPage('pages/cart/cart');
  await tick(1000);
  // 保证至少勾选一条：没勾选时页面会直接提示「请先勾选」，根本走不到下单
  page.instance.setData({ selectedCount: Math.max(1, page.data.selectedCount) });

  const navBefore = mp.state.navigations.length;
  page.instance.placeOrder();
  await tick(1200);

  await check('下单请求真的发了', () => {
    assert.ok(placedBody, '未捕获到 POST /api/orders');
  });
  if (placedBody) {
    await check('★ 请求体 addressId 是**字符串**（长 id 精度防线）', () => {
      assert.equal(typeof placedBody.data.addressId, 'string', JSON.stringify(placedBody.data.addressId));
    });
    await check('★ 请求体带 idempotentKey（双击 / 重试幂等的唯一凭据）', () => {
      const key = placedBody.data.idempotentKey;
      assert.ok(typeof key === 'string' && key.length >= 8, `idempotentKey=${JSON.stringify(key)}`);
    });
  }
  await check('★ 下单后跳转用 navigateTo 到 /pages/orders/orders（不是 switchTab —— orders 不是 tab 页，switchTab 会静默失败）', () => {
    const navs = mp.state.navigations.slice(navBefore);
    const hit = navs.find((n) => n.type === 'navigateTo' && String(n.url).includes('/pages/orders/orders'));
    assert.ok(hit, JSON.stringify(navs));
  });
  await check('下单成功后给出成功提示（不是静默）', () => {
    assert.ok(mp.state.toasts.length > 0, JSON.stringify(mp.state.toasts));
  });
  unstubAddr();
  unstubOrder();
  page.unload();
}

// ================= 4. 商品详情：加入购物车 =================
step('商品详情：加入购物车的请求体');
{
  let addBody = null;
  const unstubAdd = mp.stub(/\/api\/cart\/items$/, (opts) => {
    addBody = opts;
    return { skuId: '2001', quantity: 1 };
  });

  // 用首页拿到的第一个商品 id，保证是库里真实存在的
  const home = mp.mountPage('pages/index/index');
  await tick(900);
  const spuId = home.data.items[0].id;
  home.unload();

  const page = mp.mountPage('pages/product/product', { id: spuId });
  await tick(900);

  await check('详情页拉到了商品（不是空壳）', () => {
    const hasContent = JSON.stringify(page.data).length > 200;
    assert.ok(hasContent, JSON.stringify(page.data).slice(0, 200));
  });

  const addFn = ['addToCart', 'onAddCart', 'addCart'].find((k) => typeof page.instance[k] === 'function');
  await check('详情页存在「加入购物车」入口', () => {
    assert.ok(addFn, `未找到加购方法，可用方法=${Object.keys(page.instance).filter((k) => typeof page.instance[k] === 'function').join(',')}`);
  });

  if (addFn) {
    page.instance[addFn]();
    await tick(800);
    await check('★ 加购请求体 skuId 是**字符串**', () => {
      assert.ok(addBody, '未捕获到 POST /api/cart/items');
      assert.equal(typeof addBody.data.skuId, 'string', JSON.stringify(addBody.data.skuId));
    });
    await check('加购请求体 quantity 是数字（不是 "1" 这种字符串）', () => {
      assert.equal(typeof addBody.data.quantity, 'number', JSON.stringify(addBody.data.quantity));
    });
  }
  unstubAdd();
  page.unload();
}

// ================= 5. 确认订单：金额行的三行一致性 =================
/*
 * 为什么专门盯这一页：确认订单页把「商品金额 / 优惠抵扣 / 应付」做成了三份 state，
 * 而「应付」一度只有 applyCoupon() 会写 —— 当服务端**没有推荐券**时
 * （用户手上没有可用券、未达门槛、或未登录），applyCoupon 一次都不会被调用，
 * 于是「应付」永远停在 data 初值 '0'，页面上出现「商品金额 ¥189 / 应付 ¥0」这种
 * 自相矛盾、且看起来像资金故障的展示（实际扣款由服务端算，不受影响）。
 *
 * 断言钉的是**不变性质**而不是某个数字：没有券时「应付 == 商品金额」，
 * 有券时「应付 == 商品金额 − 抵扣」。数字写死会在改种子价后假红。
 */
step('确认订单：金额行必须三行自洽（无券 / 有券两种边界）');
{
  // 借首页拿一个真实存在的 spuId，保证 checkout 能拉到真实 SKU 与价格
  const home = mp.mountPage('pages/index/index');
  await tick(900);
  const spuId = home.data.items[0].id;
  home.unload();

  // —— 边界一：服务端没有任何推荐券（= 用户投诉的现场）——
  // 这是实测的原始响应：新账号 GET /api/coupons/mine/usable?amount=18900
  const unstubNoCoupon = mp.stub(/\/api\/coupons\/mine\/usable/, {
    amount: 0,
    coupons: [],
    bestCouponCode: null,
    bestDiscountAmount: 0,
  });
  const pageA = mp.mountPage('pages/checkout/checkout', { spuId });
  await tick(1000);

  await check('确认订单拉到了规格与金额（不是空壳）', () => {
    assert.ok(pageA.data.sku, 'sku 为空');
    assert.ok(pageA.data.amountFen > 0, `amountFen=${pageA.data.amountFen}`);
  });
  // 断言按**数值**比较，不比对 '189' / '189.00' 这种字符串形态 ——
  // 展示格式（fen2yuan 的 toFixed 规则）是另一件事，把它抄进测试只会带来假红。
  await check('★ 无可用券时「应付」不得停在初值 0（商品金额 189 / 应付 0 的现场）', () => {
    const pay = Number(pageA.data.payText);
    assert.ok(pay > 0, `payText=${JSON.stringify(pageA.data.payText)}`);
  });
  await check('★ 无可用券时「应付」必须等于「商品金额」', () => {
    assert.equal(Number(pageA.data.payText), pageA.data.amountFen / 100, {
      payText: pageA.data.payText,
      amountText: pageA.data.amountText,
      amountFen: pageA.data.amountFen,
    });
    assert.equal(pageA.data.payText, pageA.data.amountText, '两个金额行的文案也必须一致');
  });
  await check('无可用券时券行回落为「不使用」且不显示抵扣', () => {
    assert.equal(pageA.data.chosenCoupon, null);
    assert.equal(pageA.data.couponText, '不使用');
    assert.equal(pageA.data.discountText, '');
  });
  unstubNoCoupon();
  pageA.unload();

  // —— 边界二：服务端推荐了一张券 —— 保证修「无券分支」时没把「有券分支」带坏 ——
  const unstubBest = mp.stub(/\/api\/coupons\/mine\/usable/, (opts) => {
    const amount = Number(String(opts.url).split('amount=')[1]) || 0;
    return {
      amount,
      coupons: [
        { code: 'CPTEST000001', name: '测试满减券', thresholdAmount: 0, discountAmount: 500, usable: true, reason: null },
      ],
      bestCouponCode: 'CPTEST000001',
      bestDiscountAmount: 500,
    };
  });
  const pageB = mp.mountPage('pages/checkout/checkout', { spuId });
  await tick(1000);

  await check('★ 有推荐券时「应付」= 商品金额 − 抵扣', () => {
    const amountFen = pageB.data.amountFen;
    assert.ok(amountFen > 0, `amountFen=${amountFen}`);
    assert.equal(Number(pageB.data.payText), (amountFen - 500) / 100, {
      payText: pageB.data.payText,
      amountFen,
    });
  });
  await check('有推荐券时券名与抵扣文案都渲染出来了', () => {
    assert.equal(pageB.data.chosenCoupon && pageB.data.chosenCoupon.code, 'CPTEST000001');
    assert.ok(pageB.data.discountText.startsWith('-¥'), pageB.data.discountText);
    assert.equal(Number(pageB.data.discountText.slice(2)), 5, pageB.data.discountText);
  });
  unstubBest();
  pageB.unload();
}

// ================= 6. 领券中心：刷新时机与节流 =================
/*
 * 两个方向都要钉住，只钉一个方向就会被「假修复」蒙过去：
 *   * 上界：onLoad + onShow 是**连着**触发的，取数必须只发一次（否则每次进页面双倍请求）；
 *   * 下界：超过节流窗口后 onShow **必须**真的重新取数 ——
 *     否则「刷新」这个功能本身被守卫吃掉了，等于把 bug 换成另一个 bug。
 */
step('领券中心：onLoad/onShow 不得重复取数，超过节流窗口必须重新取数');
{
  const isTemplates = (r) => /\/api\/coupons\/templates/.test(r.url);
  const before = mp.state.requests.length;

  const page = mp.mountPage('pages/coupons/coupons');
  await tick(700);
  const first = mp.state.requests.slice(before).filter(isTemplates);

  await check('进页面只取一次券列表（onLoad 与 onShow 不重复）', () => {
    assert.equal(first.length, 1, `实际发了 ${first.length} 次：${first.map((r) => r.url)}`);
  });
  await check('★ 券列表真的渲染出来了（不是空壳，否则上面那条是空断言）', () => {
    assert.ok(page.data.templates.length > 0, `templates=${JSON.stringify(page.data.templates).slice(0, 160)}`);
  });

  // 30 秒内切回来：不应再打接口
  page.instance.onShow();
  await tick(300);
  await check('30 秒内 onShow 不再重复取数（节流生效）', () => {
    assert.equal(mp.state.requests.slice(before).filter(isTemplates).length, 1);
  });

  // 把时间戳往前拨 31 秒，模拟「过一会儿切回来」：**必须**重新取数
  page.instance._lastFetchAt = Date.now() - 31000;
  page.instance.onShow();
  await tick(700);
  await check('★ 超过节流窗口后 onShow 必须重新取数（刷新功能没被守卫吃掉）', () => {
    const n = mp.state.requests.slice(before).filter(isTemplates).length;
    assert.equal(n, 2, `实际 ${n} 次`);
  });

  page.unload();
}

/*
 * 自还原：本文件为了拿到「非空购物车」往库里加了一条（一次性买家，但仍要收回）。
 * 探针改了别人的样本数据，报错的会是**下一个脚本**，而且最难归因 ——
 * 所以加进去的东西自己删掉，不留悬空数据。
 */
await authed('DELETE', '/api/cart/items/2001');

console.log(`\n=== 小程序行为断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(failures.length ? 1 : 0);
