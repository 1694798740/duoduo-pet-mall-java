/**
 * 个人中心静态守卫 —— 钉住 2026-10-21 那轮「撤销注销表单被浏览器自动填充」的修正。
 *
 * <h2>为什么这条要静态守卫而不是只靠 e2e</h2>
 * 撤销表单只在「冷静期 / 刚提交后」渲染，账号正常的访客永远看不到它 ——
 * e2e 要专门造一个冷静期账号才能摸到 DOM，成本高且脆。
 * 而这轮要防的回归恰恰是**几行属性被顺手删掉**（autoComplete="off" / "new-password"），
 * 静态扫描正好覆盖；e2e（me-page-ui-check.mjs）负责行为层（撤销区只在冷静期出现）。
 *
 * 判据（见类注释里的来源：Chrome 对 password 输入框会无视 autoComplete="off"，
 * 只有 "new-password" 能稳定阻止自动填充）：
 *   1. 撤销表单的 <form> 带 autoComplete="off"；
 *   2. 撤销表单的密码框带 autoComplete="new-password"；
 *   3. 撤销表单**必须**包在 `cancelling || submitted` 条件里（正常账号不渲染）；
 *   4. 三个分区卡片标题齐全（基本资料 / 账号状态 / 快捷入口 / 信用分 / 实名认证 / 账号注销）；
 *   5. 头像保存失败时不再覆盖存储层消息（UploadController 不再统一盖"请稍后重试"）。
 *
 * 用法：node scripts/check-me-page.mjs    （退出码 0 = 全绿）
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

let pass = 0;
const failures = [];
const check = (name, ok, got) => {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`);
  }
};

// ---------------- Me.tsx ----------------
const me = read('frontend/apps/mall/src/pages/Me.tsx');

// 1+2. autoComplete 两道闸
const cancelFormIdx = me.indexOf('撤销区只在冷静期出现');
check('找到撤销表单区块', cancelFormIdx > 0, cancelFormIdx);
const cancelBlock = cancelFormIdx > 0 ? me.slice(cancelFormIdx, cancelFormIdx + 4000) : '';
check('撤销表单 <form> 带 autoComplete="off"（阻止用户名回填）',
  /<form[^>]*autoComplete="off"/.test(cancelBlock), cancelBlock.slice(0, 120));
check('撤销密码框带 autoComplete="new-password"（Chrome 对 password 无视 off，只认这个）',
  /type="password"[\s\S]{0,400}?autoComplete="new-password"/.test(cancelBlock), null);

// 3. 撤销表单只在冷静期渲染
const renderGuard = /cancelling \|\| submitted \? \(?\s*<form/.test(cancelBlock)
  || /\{cancelling \|\| submitted \? \(/.test(me.slice(cancelFormIdx - 200, cancelFormIdx + 200));
check('撤销表单包在「cancelling || submitted」条件里（正常账号不渲染）', renderGuard, null);

// 4. 六张分区卡都在
for (const title of ['基本资料', '账号状态', '快捷入口', '信用分', '实名认证', '账号注销']) {
  check(`分区卡「${title}」存在`, me.includes(`>${title}</h3>`), null);
}

// 5. 上传失败不再被统一盖成"请稍后重试"
//    精确口径：`catch (BizException e)` 分支必须 `throw e;`（原样透出）；
//    "图片保存失败，请稍后重试"只允许留在 IOException 分支（客户端中断读流，
//    这种重试才有意义），全文件至多 1 处。
const upload = read('duoduo-fulfilment/src/main/java/com/duoduo/fulfilment/controller/UploadController.java');
const bizRethrow = (upload.match(/catch \(BizException e\) \{[\s\S]{0,400}?throw e;/g) ?? []).length;
check('UploadController 两处 BizException 分支都原样抛出（存储层可行动提示不被覆盖）',
  bizRethrow >= 2, bizRethrow);
check('「请稍后重试」只留在 IOException 分支（全文件至多 1 处）',
  (upload.match(/图片保存失败/g) ?? []).length === 1,
  (upload.match(/图片保存失败/g) ?? []).length);

// COS 欠费点名
const cos = read('duoduo-common-web/src/main/java/com/duoduo/common/web/storage/CosObjectStorage.java');
check('CosObjectStorage 对 451/arrears 给出「欠费 + 充值」可行动提示',
  cos.includes('arrears') && cos.includes('451') && cos.includes('充值'), null);

console.log(`\n=== 个人中心静态守卫：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exit(1);
}
process.exit(0);
