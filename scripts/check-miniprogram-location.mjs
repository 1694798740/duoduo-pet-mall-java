/**
 * 小程序定位能力的静态守卫 + 纯函数断言（2026-10-20）。
 *
 * 为什么需要它：
 *   `wx.getLocation` / `wx.chooseLocation` 自 2022 年起属于**隐私接口**，
 *   必须在 app.json 的 `requiredPrivateInfos` 里声明，否则调用直接失败，
 *   而且 errMsg 与"没授权"完全不同。这类"声明被误删"的回归，
 *   跑真机才能发现，代价很高 —— 这里用静态检查把它挡住。
 *
 * 覆盖：
 *   1) app.json 声明了 getLocation / chooseLocation / choosePoi；
 *   2) 声明了 permission.scope.userLocation.desc（授权弹窗需要它）；
 *   3) 地址页确实走"先预检授权、失败统一收口"的路径（防止有人绕过）；
 *   4) classifyLocationError 对真实 errMsg 的分类正确（含最容易混淆的两种）；
 *   5) 所有失败文案都提到"省 / 市 / 区"（兜底必须指向手动选择）；
 *   6) wx.choosePoi 的三条硬约束：已声明、type=1/type=2 分开处理、
 *      且**没有拿它替换掉地图选点**（POI 选不到门牌号）；
 *   7) wx.chooseMedia（售后凭证选图）也已声明 —— 它同属隐私接口，
 *      漏声明时定位类功能正常、唯独上传图片真机失败，最容易被忽略。
 *
 * 用法：node scripts/check-miniprogram-location.mjs
 * 退出码：0 全过；1 有失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const require = createRequire(import.meta.url);

let pass = 0;
const fails = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    fails.push(name);
    console.log(`  [FAIL] ${name}${got === undefined ? '' : `  实际=${JSON.stringify(got)}`}`);
  }
}

// ---------- 1) app.json 声明 ----------
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'miniprogram/app.json'), 'utf8'));
const declared = Array.isArray(appJson.requiredPrivateInfos) ? appJson.requiredPrivateInfos : [];
check('app.json 声明了 requiredPrivateInfos', Array.isArray(appJson.requiredPrivateInfos), declared);
check('声明包含 getLocation（定位当前地址）', declared.includes('getLocation'), declared);
check('声明包含 chooseLocation（地图选点）', declared.includes('chooseLocation'), declared);
check('声明包含 choosePoi（附近位置快选）', declared.includes('choosePoi'), declared);
// 售后凭证上传用 wx.chooseMedia 选图，同样属隐私接口（相册/摄像头），漏声明真机直接失败
check('声明包含 chooseMedia（售后凭证选图）', declared.includes('chooseMedia'), declared);
// ★ 微信 app.json 的键名本身就带点：permission["scope.userLocation"]，不是嵌套对象
const desc = appJson.permission?.['scope.userLocation']?.desc ?? '';
check('permission.scope.userLocation.desc 已填写（授权弹窗展示用）', desc.trim().length >= 8, desc);

// ---------- 2) 页面确实走预检 + 统一收口 ----------
const pageJs = fs.readFileSync(path.join(root, 'miniprogram/pages/address/address.js'), 'utf8');
check('地址页在调用定位前做了授权预检（ensureLocationAuth）', pageJs.includes('ensureLocationAuth('));
check('定位/选点失败走统一收口（onLocateBlocked）', pageJs.includes('onLocateBlocked('));
check('失败后会把页面带到省市区选择（addr-region-field 锚点）',
  pageJs.includes('.addr-region-field') && fs.readFileSync(path.join(root, 'miniprogram/pages/address/address.wxml'), 'utf8').includes('addr-region-field'));

// ---------- 3) 分类纯函数 ----------
const loc = require(path.join(root, 'miniprogram/utils/location.js'));
const cases = [
  ['getLocation:fail the api need to be declared in the requiredPrivateInfos field in app.json', 'NOT_DECLARED'],
  ['chooseLocation:fail the api need to be declared in the requiredPrivateInfos field in app.json', 'NOT_DECLARED'],
  ['getLocation:fail auth deny', 'DENIED'],
  ['chooseLocation:fail auth deny', 'DENIED'],
  ['choosePoi:fail auth deny', 'DENIED'],
  ['choosePoi:fail the api need to be declared in the requiredPrivateInfos field in app.json', 'NOT_DECLARED'],
  ['getLocation:fail:system permission denied', 'SYSTEM'],
  ['chooseLocation:fail cancel', 'CANCELLED'],
  ['getLocation:fail timeout', 'TIMEOUT'],
  ['getLocation:fail:ERROR_NOCELL&WIFI_LOCATIONSWITCHOFF', 'SERVICE_OFF'],
  ['', 'UNKNOWN'],
];
for (const [msg, want] of cases) {
  const got = loc.classifyLocationError(msg);
  check(`分类：${msg.slice(0, 52) || '(空)'} → ${want}`, got === want, got);
}

// "声明缺失"绝不能落到"权限被拒"，否则会误导用户去翻手机设置
check('声明缺失与权限拒绝是两个不同分类（防误导）',
  loc.classifyLocationError('getLocation:fail the api need to be declared in the requiredPrivateInfos field in app.json') !== 'DENIED');

// ---------- 3.5) wx.choosePoi 的硬约束 ----------
// ① POI 回调有两种形态：type=2 有经纬度（可反查省市区），type=1 只选到城市
//    （经纬度不可用）。混在一起处理会把表单写成"空省空区"。
check('POI 分支区分了 type=2（有经纬度）与 type=1（仅城市）',
  pageJs.includes('res.type === 2'), /res\.type\s*===?\s*2/.exec(pageJs)?.[0]);
check('POI 的 type=1 分支不拿坐标反查（防止空省空区）',
  /type === 1|只选到城市/.test(pageJs));
check('POI 失败同样走统一收口（onLocateBlocked + 附近位置）',
  pageJs.includes("'附近位置'") && pageJs.includes('wx.choosePoi('));
check('POI 没有替换掉地图选点（三个入口并存）',
  pageJs.includes('chooseOnMap()') && pageJs.includes('chooseNearbyPoi()'));
check('售后页确实在用 chooseMedia 选图（凭证上传）',
  fs.readFileSync(path.join(root, 'miniprogram/pages/after-sale/after-sale.js'), 'utf8').includes('wx.chooseMedia('));
check('wxml 上有三个定位入口（定位/选点/附近位置）',
  (() => {
    const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/address/address.wxml'), 'utf8');
    return wxml.includes('bindtap="locate"') && wxml.includes('bindtap="chooseOnMap"') && wxml.includes('bindtap="chooseNearbyPoi"');
  })());

// ---------- 4) 文案必须指向手动兜底 ----------
const codes = ['NOT_DECLARED', 'DENIED', 'SYSTEM', 'SERVICE_OFF', 'TIMEOUT', 'UNKNOWN'];
for (const code of codes) {
  const text = loc.describeLocationError(code, '定位');
  check(`文案（${code}）提到「省 / 市 / 区」兜底`, text.includes('省 / 市 / 区'), text);
}
check('只有拒绝/系统关闭才值得弹「去设置」',
  loc.canOpenSetting('DENIED') && loc.canOpenSetting('SYSTEM')
  && !loc.canOpenSetting('NOT_DECLARED') && !loc.canOpenSetting('SERVICE_OFF'));

console.log(`\n=== 小程序定位守卫：通过 ${pass} 项，失败 ${fails.length} 项 ===`);
if (fails.length) {
  for (const f of fails) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
