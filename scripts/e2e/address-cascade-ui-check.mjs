/**
 * A1 · 地址省市区级联 + 定位降级 · 真实浏览器 UI 自测
 *
 * 为什么单开一套：
 *   地址页原有 65 项测的是「增删改查 + 快照语义」，它们只用一条路径把地址存进去，
 *   测不出这轮改造真正会坏的地方 ——
 *     · 三级联动是否**真的级联**（选了省，市的选项是不是只属于这个省）；
 *     · 换省后**上一级的市/区有没有被清空**（不清就会存出「浙江省 / 深圳市」这种跨省地址）；
 *     · 直辖市（北京市/上海市）在数据里是否被归一（不能出现「市辖区」）；
 *     · 区划数据是否**懒加载**（100KB 体积不该进首屏包）；
 *     · 地图 Key 未配置时，定位按钮是否**体面降级**而不是把填地址卡死。
 *
 * 前置：9 容器在跑（网关 8080）+ 商城前端 8088 已构建。
 * 用法：UI_BASE=http://localhost:8088 node scripts/e2e/address-cascade-ui-check.mjs
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;

const PWD = 'duoduo12345';
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

async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : GW) + p, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (jar) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const pair = c.split(';')[0];
      const i = pair.indexOf('=');
      if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
  }
  const raw = await res.text();
  let json = null;
  try {
    json = JSON.parse(raw);
  } catch {
    /* ignore */
  }
  return { status: res.status, data: json?.data, raw };
}

step('夹具：买家账号');
const phone = `137${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '地址级联测试' }, jar);
check('买家注册成功', reg.status === 200, reg.status);

// 授权定位并伪造一个杭州坐标：让「定位成功 → 服务端未配 Key」这条降级路径可被确定性地测到
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 960 },
  locale: 'zh-CN',
  permissions: ['geolocation'],
  geolocation: { latitude: 30.2741, longitude: 120.1551 },
});
const page = await ctx.newPage();
const consoleErrors = [];
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text());
});
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

/** 记录资源请求，用于证明区划数据是懒加载 */
const regionChunkRequests = [];
page.on('request', (r) => {
  if (/\/assets\/regions-.*\.js/.test(r.url())) regionChunkRequests.push(r.url());
});

const selProvince = () => page.getByLabel('省 / 直辖市', { exact: true });
const selCity = () => page.getByLabel('市', { exact: true });
const selDistrict = () => page.getByLabel('区 / 县', { exact: true });
const opts = async (sel) => sel.locator('option').evaluateAll((nodes) => nodes.map((n) => n.value));
const textNow = () => page.locator('body').innerText();

step('UI：登录并进入地址页');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
await page.goto(`${UI}/addresses`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

check('★ 进入地址页时**还没有**加载区划数据（懒加载，不进首屏）',
  regionChunkRequests.length === 0, regionChunkRequests);

step('UI：打开新增弹窗');
await page.getByRole('button', { name: '+ 新增地址' }).click();
await page.waitForTimeout(800);
await page.waitForFunction(() => document.querySelectorAll('select').length >= 3, { timeout: 8000 });

check('★ 打开弹窗后才把区划数据切块拉下来（体积不进首屏）',
  regionChunkRequests.length >= 1, regionChunkRequests);
check('省 / 市 / 区 三个字段都是**下拉**（不是输入框）',
  (await selProvince().count()) === 1 && (await selCity().count()) === 1 && (await selDistrict().count()) === 1,
  {
    provinceTag: await selProvince().evaluate((el) => el.tagName),
    cityTag: await selCity().evaluate((el) => el.tagName),
    districtTag: await selDistrict().evaluate((el) => el.tagName),
  });

const provinceOpts = await opts(selProvince());
check('★ 省份下拉覆盖 31 个省级行政区（+1 个「请选择」占位）',
  provinceOpts.length === 32, provinceOpts.length);
check('省份里含直辖市与自治区（北京市 / 上海市 / 新疆维吾尔自治区）',
  ['北京市', '上海市', '新疆维吾尔自治区'].every((n) => provinceOpts.includes(n)),
  provinceOpts.filter((n) => /北京|上海|新疆/.test(n)));

check('未选省时「市」与「区」不可选（避免跨省脏组合）',
  (await selCity().isDisabled()) && (await selDistrict().isDisabled()),
  { city: await selCity().isDisabled(), district: await selDistrict().isDisabled() });

step('UI：浙江省 → 杭州市 → 西湖区（三级联动）');
await selProvince().selectOption('浙江省');
await page.waitForTimeout(200);
const zjCities = await opts(selCity());
check('★ 选省后「市」解锁，且选项属于该省（含杭州市、不含深圳）',
  !(await selCity().isDisabled()) && zjCities.includes('杭州市') && !zjCities.includes('深圳市'),
  zjCities.slice(0, 12));
check('选省后「区」仍不可选（必须先选市）', await selDistrict().isDisabled());

await selCity().selectOption('杭州市');
await page.waitForTimeout(200);
const hzDistricts = await opts(selDistrict());
check('★ 选市后「区」解锁，且选项属于该市（含西湖区、不含浦东新区）',
  !(await selDistrict().isDisabled()) && hzDistricts.includes('西湖区') && !hzDistricts.includes('浦东新区'),
  hzDistricts.slice(0, 12));

await selDistrict().selectOption('西湖区');
check('三级都选中后值正确',
  (await selProvince().inputValue()) === '浙江省'
  && (await selCity().inputValue()) === '杭州市'
  && (await selDistrict().inputValue()) === '西湖区');

step('UI：换省必须清空市与区（防跨省脏地址）');
await selProvince().selectOption('广东省');
await page.waitForTimeout(200);
check('★ 换省后「市」被清空且区回到不可选',
  (await selCity().inputValue()) === '' && (await selDistrict().inputValue()) === '' && (await selDistrict().isDisabled()),
  { city: await selCity().inputValue(), district: await selDistrict().inputValue() });
check('换省后「市」选项变成广东的城市（含深圳市、不含杭州市）',
  (await opts(selCity())).includes('深圳市') && !(await opts(selCity())).includes('杭州市'),
  (await opts(selCity())).slice(0, 10));

step('UI：直辖市归一（上海市 → 只有一个「市」，不出现「市辖区」）');
await selProvince().selectOption('上海市');
await page.waitForTimeout(200);
const shCities = await opts(selCity());
check('★ 直辖市只列出一个市（上海市），且不含「市辖区」这种非地名',
  shCities.filter(Boolean).length === 1 && shCities.includes('上海市') && !shCities.includes('市辖区'),
  shCities);
await selCity().selectOption('上海市');
await page.waitForTimeout(200);
check('上海市的区县含浦东新区、不含西湖区',
  (await opts(selDistrict())).includes('浦东新区') && !(await opts(selDistrict())).includes('西湖区'),
  (await opts(selDistrict())).slice(0, 10));

step('UI：定位按钮（配了 Key 要真回填；没配 Key 要体面降级，都不能卡死）');
check('弹窗里有「定位当前地址」按钮',
  (await page.getByRole('button', { name: /定位当前地址|定位中/ }).count()) === 1);
await page.getByRole('button', { name: /定位当前地址|定位中/ }).click();
await page.waitForTimeout(1500);
const afterLocate = await textNow();
// 有两种合法结果，二者都算通过（取决于服务端是否配了 Web 服务 Key）：
//   ① 已配置 → 用伪造的杭州坐标应真回填「浙江省 / 杭州市 / 某区」；
//   ② 未配置 → 必须给出明确降级提示，而不是笼统报错。
const locateFilled = (await selProvince().inputValue()) === '浙江省'
  && (await selCity().inputValue()) === '杭州市'
  && (await selDistrict().inputValue()) !== '';
const locateDegraded = /地图服务尚未配置|定位权限被拒绝|定位失败|定位超时|定位服务调用失败/.test(afterLocate);
check('★ 定位后要么真回填（杭州坐标 → 浙江省/杭州市/某区），要么给明确降级提示',
  locateFilled || locateDegraded,
  { locateFilled, locateDegraded, province: await selProvince().inputValue(), city: await selCity().inputValue(), district: await selDistrict().inputValue() });
// 这条只在「服务端确实配了 Web 服务 Key」时才要求真回填 —— 否则会在没有 .env 的
// 环境（CI / 别人 clone）里无条件变红，属于把环境差异当成功能缺陷。
const probe = await call('GET', '/api/geo/regeo?lng=120.1551&lat=30.2741', undefined, jar);
const geoConfigured = Boolean(probe?.data?.configured);
check('★ 服务端已配 Key 时必须真回填（不是只弹提示）；未配置则跳过该要求',
  geoConfigured ? locateFilled : true,
  { geoConfigured, locateFilled, text: afterLocate.slice(0, 200) });
check('★ 定位之后表单仍可继续填（省市区下拉未被禁用，失败也不阻断）',
  !(await selProvince().isDisabled()), await selProvince().isDisabled());

step('UI：附近候选（外卖式挑选，避免单一位置错误）');
// 候选按钮形如「花园坪C区一巷8号 · 73米」；只有配了 Key 才会出现，未配置时跳过。
if (geoConfigured) {
  const candButtons = page.locator('button').filter({ hasText: /\d+\s*米/ });
  const candCount = await candButtons.count();
  check('★ 定位后给出多个附近候选（不是只回填一个位置）', candCount >= 2, candCount);
  const candTexts = await candButtons.allInnerTexts();
  const distances = candTexts
    .map((t) => Number((t.match(/(\d+)\s*米/) || [])[1]))
    .filter((n) => Number.isFinite(n));
  check('★ 候选按距离升序排列（最近的在最前）',
    distances.length >= 2 && distances.every((v, i) => i === 0 || v >= distances[i - 1]),
    distances);
  // 点第二条：详细地址应变成该候选的地点名
  await candButtons.nth(1).click();
  await page.waitForTimeout(300);
  const pickedText = candTexts[1];
  const detailValue = await page.getByPlaceholder('例如 文三路 99 号 3 幢 501 室').inputValue();
  check('★ 点选候选后详细地址被填入该地点名',
    detailValue.length > 0 && pickedText.startsWith(detailValue),
    { pickedText, detailValue });
} else {
  console.log('  [SKIP] 服务端未配置地图 Key，候选断言跳过');
}

step('UI：地图选点（有 JS Key 则用真实地图，否则验证降级 + 用桩验证集成逻辑）');
{
  await page.getByRole('button', { name: '🗺️ 地图选点' }).click();
  await page.waitForTimeout(6000);
  const mapState = await page.evaluate(() => ({
    dialogOpen: document.body.innerText.includes('在地图上选点'),
    amapLoaded: Boolean(window.AMap),
    failedHint: /地图加载失败|未配置网页地图 Key|地图加载超时/.test(document.body.innerText),
  }));
  check('★ 点「地图选点」会弹出地图弹窗', mapState.dialogOpen, mapState);
  // 两种合法结果：真实地图可用，或给出明确降级原因（Key 域名白名单/网络）
  check('★ 地图要么真的加载出来，要么给出明确降级原因（不静默失败）',
    mapState.amapLoaded || mapState.failedHint, mapState);

  if (mapState.amapLoaded) {
    const confirm = page.getByRole('button', { name: '使用这个位置' });
    check('地图加载成功时「使用这个位置」可点', await confirm.isEnabled());
    await confirm.click();
    await page.waitForTimeout(2500);
    check('★ 地图确认后回填了省市区',
      (await selProvince().inputValue()).length > 0 && (await selCity().inputValue()).length > 0,
      { province: await selProvince().inputValue(), city: await selCity().inputValue() });
  } else {
    // Key 未授权域名时也能证明集成逻辑正确：用桩替换 window.AMap 后重开弹窗
    check('地图加载失败时「使用这个位置」禁用（不误导用户）',
      !(await page.getByRole('button', { name: '使用这个位置' }).isEnabled()));
    await page.getByRole('button', { name: '取消' }).last().click();
    await page.waitForTimeout(400);
    await page.evaluate(() => {
      window.__amapStubUsed = false;
      window.AMap = {
        Map: function (el, opts) {
          window.__amapStubUsed = true;
          this.opts = opts;
          this.on = () => {};
          this.destroy = () => {};
          el.innerHTML = '<div class="stub-map"></div>';
        },
        Marker: function (opts) {
          this.opts = opts;
          this.on = () => {};
          this.setPosition = () => {};
          this.getPosition = () => ({ getLng: () => 114.231199, getLat: () => 22.706634 });
        },
      };
    });
    await page.getByRole('button', { name: '🗺️ 地图选点' }).click();
    await page.waitForTimeout(1500);
    const stubUsed = await page.evaluate(() => Boolean(window.__amapStubUsed));
    check('★ 集成逻辑：拿到 AMap 后会用初始坐标创建地图与可拖地标', stubUsed, stubUsed);
    const confirm = page.getByRole('button', { name: '使用这个位置' });
    check('桩就绪后「使用这个位置」可点', await confirm.isEnabled());
    await confirm.click();
    await page.waitForTimeout(2500);
    check('★ 地图确认后回填了省市区（复用后端逆地理）',
      (await selProvince().inputValue()).length > 0 && (await selCity().inputValue()).length > 0,
      { province: await selProvince().inputValue(), city: await selCity().inputValue() });
    // 清掉桩，避免影响后续步骤
    await page.evaluate(() => { delete window.AMap; });
  }
}

step('UI：提交一条真实地址，验证级联选出的值能存进去');
await page.getByPlaceholder('例如 张小三').fill('级联测试');
await page.getByPlaceholder('11 位手机号').fill('13900009999');
await selProvince().selectOption('浙江省');
await page.waitForTimeout(150);
await selCity().selectOption('杭州市');
await page.waitForTimeout(150);
await selDistrict().selectOption('西湖区');
await page.getByPlaceholder('例如 文三路 99 号 3 幢 501 室').fill('文三路 100 号 1 幢 101 室');
await page.getByRole('button', { name: '保存' }).click();
await page.waitForTimeout(1600);

const listText = await textNow();
check('★ 保存成功，列表出现「浙江省杭州市西湖区」完整地址',
  listText.includes('级联测试') && listText.includes('浙江省杭州市西湖区文三路 100 号 1 幢 101 室'),
  listText.slice(0, 400));

step('UI：编辑回显（下拉要能还原已有的省市区）');
await page.locator('.card', { hasText: '级联测试' }).first().getByRole('button', { name: '编辑' }).click();
await page.waitForTimeout(900);
await page.waitForFunction(() => document.querySelectorAll('select').length >= 3, { timeout: 8000 });
check('★ 编辑时三个下拉回显为原地址的省 / 市 / 区',
  (await selProvince().inputValue()) === '浙江省'
  && (await selCity().inputValue()) === '杭州市'
  && (await selDistrict().inputValue()) === '西湖区',
  {
    province: await selProvince().inputValue(),
    city: await selCity().inputValue(),
    district: await selDistrict().inputValue(),
  });

step('清理：删掉本轮建的地址');
await page.keyboard.press('Escape');
await page.waitForTimeout(400);
const delOk = await call('DELETE', `/api/addresses/${(await call('GET', '/api/addresses', undefined, jar)).data?.[0]?.id}`, undefined, jar);
check('（清理）测试地址已删除', delOk.status === 200, delOk.status);

// 401 是**设计内状态**（未登录时 /api/users/me、/api/cart/count 必然 401），
// 口径与其它套件一致：只过滤 401 与 favicon，pageerror 一律不放过。
check('全流程无 console 报错 / pageerror',
  consoleErrors.filter((e) => !/favicon|status of 401/i.test(e)).length === 0,
  consoleErrors.filter((e) => !/favicon|status of 401/i.test(e)).slice(0, 3));

await ctx.close();
await browser.close();

console.log(`\n=== 地址级联 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
