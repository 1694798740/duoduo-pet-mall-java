/**
 * 类目属性模板冒烟（2026-10-25，A-10）。
 *
 * 守五组事实：
 *  A. 模板维护：运营可增改启停删；同名 → 409；SELECT 无选项 / 非法类型 → 400；
 *     公开只读接口只返回启用项，后台列表含停用项；非运营访问 → 403。
 *  B. 发布闸门：必填缺失一次性汇总拒绝；NUMBER 非数字拒绝；SELECT 不在选项内拒绝；
 *     全部合法才放行，值仍落在 t_spu.spec_params（不另建值表）。
 *  C. 老商品不阻断：模板新增前发布的商品，只改标题不受必填项影响；
 *     一旦改规格参数，就必须补齐模板。
 *  D. 启停语义：停用的必填项不再拦截发布；重新启用后编辑规格立即恢复拦截。
 *  E. 回库断言：模板列、商品 spec_params、操作审计日志均与接口表现一致。
 *
 * 前置：容器在跑；51-category-attr-template.sql 已执行；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/category-attr-a10-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const PRODUCT_DB = 'duoduo_product';
const USER_DB = 'duoduo_user';

let pass = 0;
const failures = [];
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 400)));
  }
}

async function call(method, path, body, token, entry) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'X-Duoduo-Entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, entry)).body?.data?.accessToken;

/** 回库断言：只读，返回制表符分隔的行数组 */
function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const sku = (price = 1990, stock = 8) => [{ specName: '默认', price, stock, image: null }];
const msgOf = (res) => String(res.body?.message ?? res.body?.msg ?? JSON.stringify(res.body));

async function main() {
  console.log('== 0. 登录卖家与运营 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  check('卖家（店主）登录成功', Boolean(sellerToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  if (!sellerToken || !opToken) return 1;

  const stamp = String(Date.now()).slice(-7);
  const createdSpuIds = [];
  let attrIds = [];
  let catId = null;
  let legacySpuId = null;

  try {
    console.log('\n== 1. 建冒烟类目与「模板之前」的老商品 ==');
    const cat = await call('POST', '/api/admin/categories',
      { name: `A10 冒烟类目 ${stamp}`, parentId: '0', sortNo: 0, icon: '' }, opToken);
    catId = cat.body?.data?.id;
    check('运营新建冒烟类目', cat.status === 200 && Boolean(catId), cat.body);
    if (!catId) return 1;

    const publicEmpty = await call('GET', `/api/categories/${catId}/attrs`);
    check('新类目公开模板为空', publicEmpty.status === 200 && (publicEmpty.body?.data ?? []).length === 0,
      publicEmpty.body);

    const legacy = await call('POST', '/api/seller/products', {
      title: `A10 老商品 ${stamp}`,
      categoryId: catId,
      mainImage: '/img/products/dog-food.svg',
      // 模板出现前的自由 KV：没有「净含量 / 适用阶段」这些必填名
      specParams: [{ name: '旧自定义', value: '保留值' }],
      skus: sku(),
    }, sellerToken);
    legacySpuId = legacy.body?.data?.id;
    if (legacySpuId) createdSpuIds.push(String(legacySpuId));
    check('模板之前发布商品成功（无模板不拦截）', legacy.status === 200 && Boolean(legacySpuId), legacy.body);
    const legacyDetail0 = (await call('GET', `/api/seller/products/${legacySpuId}`, null, sellerToken)).body?.data;
    const legacyVersion0 = legacyDetail0?.version;
    check('老商品自由 KV 原样保留', (legacyDetail0?.specParams ?? [])[0]?.value === '保留值', legacyDetail0?.specParams);

    console.log('\n== 2. 运营维护模板：新增 / 校验 / 唯一 / 权限 ==');
    const net = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '净含量', inputType: 'TEXT', required: true, sortNo: 1 }, opToken);
    const netId = net.body?.data?.id;
    check('新增 TEXT 必填属性「净含量」', net.status === 200 && Boolean(netId), net.body);

    const stage = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '适用阶段', inputType: 'SELECT', required: true, options: ['幼年', '成年', '老年'], sortNo: 2 }, opToken);
    const stageId = stage.body?.data?.id;
    check('新增 SELECT 必填属性「适用阶段」', stage.status === 200 && Boolean(stageId), stage.body);
    check('SELECT 选项已回读', JSON.stringify(stage.body?.data?.options) === JSON.stringify(['幼年', '成年', '老年']),
      stage.body?.data?.options);

    const protein = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '蛋白质含量', inputType: 'NUMBER', required: false, sortNo: 3 }, opToken);
    const proteinId = protein.body?.data?.id;
    check('新增 NUMBER 选填属性「蛋白质含量」', protein.status === 200 && Boolean(proteinId), protein.body);
    attrIds = [netId, stageId, proteinId].filter(Boolean);

    const dup = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '净含量', inputType: 'TEXT' }, opToken);
    check('同名属性 → 409（唯一索引兜底）', dup.status === 409, dup.body);

    const emptyOptions = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '空选项', inputType: 'SELECT', options: [' ', ''] }, opToken);
    check('SELECT 无有效选项 → 400', emptyOptions.status === 400, emptyOptions.body);

    const badType = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '非法类型', inputType: 'BOOLEAN' }, opToken);
    check('非法属性类型 → 400', badType.status === 400, badType.body);

    const blankName = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '   ', inputType: 'TEXT' }, opToken);
    check('空属性名 → 400', blankName.status === 400, blankName.body);

    const adminList = await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken);
    check('后台模板列表 3 条且按 sortNo 排序',
      (adminList.body?.data ?? []).map((x) => x.name).join(',') === '净含量,适用阶段,蛋白质含量',
      adminList.body?.data?.map((x) => x.name));
    check('后台列表标记必填状态',
      adminList.body?.data?.find((x) => x.name === '净含量')?.required === true
      && adminList.body?.data?.find((x) => x.name === '蛋白质含量')?.required === false,
      adminList.body?.data);

    const publicAttrs = await call('GET', `/api/categories/${catId}/attrs`);
    check('公开模板只含启用项且 3 条', (publicAttrs.body?.data ?? []).length === 3, publicAttrs.body);
    check('公开模板带类型/必填/选项',
      publicAttrs.body?.data?.find((x) => x.name === '适用阶段')?.inputType === 'SELECT'
      && publicAttrs.body?.data?.find((x) => x.name === '适用阶段')?.required === true
      && (publicAttrs.body?.data?.find((x) => x.name === '适用阶段')?.options ?? []).length === 3,
      publicAttrs.body?.data);

    const sellerRead = await call('GET', `/api/admin/categories/${catId}/attrs`, null, sellerToken);
    check('卖家读后台模板 → 403', sellerRead.status === 403, sellerRead.status);
    const sellerWrite = await call('POST', `/api/admin/categories/${catId}/attrs`,
      { name: '越权属性', inputType: 'TEXT' }, sellerToken);
    check('卖家写模板 → 403', sellerWrite.status === 403, sellerWrite.status);

    const upd = await call('PUT', `/api/admin/category-attrs/${proteinId}`,
      { required: true, sortNo: 9 }, opToken);
    check('编辑属性：改必填与排序成功', upd.status === 200 && upd.body?.data?.required === true
      && upd.body?.data?.sortNo === 9, upd.body);
    const afterUpd = (await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken)).body?.data ?? [];
    check('编辑后排序位置生效（蛋白质含量排最后 → 仍在最后但 sortNo=9）',
      afterUpd.find((x) => x.name === '蛋白质含量')?.sortNo === 9, afterUpd.map((x) => `${x.name}:${x.sortNo}`));
    const revert = await call('PUT', `/api/admin/category-attrs/${proteinId}`,
      { required: false, sortNo: 3 }, opToken);
    check('编辑属性：恢复选填', revert.status === 200 && revert.body?.data?.required === false, revert.body);

    console.log('\n== 3. 发布闸门：必填 / 数字 / 选项 ==');
    const missingBoth = await call('POST', '/api/seller/products', {
      title: `A10 缺必填 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '旧自定义', value: '不影响' }], skus: sku(),
    }, sellerToken);
    check('必填全缺 → 400', missingBoth.status === 400, missingBoth.body);
    check('错误一次性汇总「净含量」「适用阶段」',
      msgOf(missingBoth).includes('净含量') && msgOf(missingBoth).includes('适用阶段')
      && msgOf(missingBoth).includes('必填'), msgOf(missingBoth));

    const missingOne = await call('POST', '/api/seller/products', {
      title: `A10 缺选项 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '净含量', value: '2kg' }], skus: sku(),
    }, sellerToken);
    check('只缺「适用阶段」→ 400 且不误报已填项',
      missingOne.status === 400 && msgOf(missingOne).includes('适用阶段') && !msgOf(missingOne).includes('净含量'),
      missingOne.body);

    const badSelect = await call('POST', '/api/seller/products', {
      title: `A10 错选项 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '净含量', value: '2kg' }, { name: '适用阶段', value: '中年' }], skus: sku(),
    }, sellerToken);
    check('SELECT 值不在选项内 → 400', badSelect.status === 400, badSelect.body);
    check('错误提示列出可选项', msgOf(badSelect).includes('只能选') && msgOf(badSelect).includes('成年'),
      msgOf(badSelect));

    const badNumber = await call('POST', '/api/seller/products', {
      title: `A10 错数字 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '净含量', value: '2kg' }, { name: '适用阶段', value: '成年' },
        { name: '蛋白质含量', value: '高' }], skus: sku(),
    }, sellerToken);
    check('NUMBER 非数字 → 400', badNumber.status === 400 && msgOf(badNumber).includes('必须是数字'),
      badNumber.body);

    const good = await call('POST', '/api/seller/products', {
      title: `A10 合法商品 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '净含量', value: '2kg' }, { name: '适用阶段', value: '成年' },
        { name: '蛋白质含量', value: '32.5' }], skus: sku(),
    }, sellerToken);
    const goodId = good.body?.data?.id;
    if (goodId) createdSpuIds.push(String(goodId));
    check('模板齐全 → 发布成功', good.status === 200 && Boolean(goodId), good.body);
    const goodDetail = (await call('GET', `/api/seller/products/${goodId}`, null, sellerToken)).body?.data;
    check('规格参数按模板值落库（3 条）',
      (goodDetail?.specParams ?? []).length === 3
      && (goodDetail.specParams ?? []).find((x) => x.name === '适用阶段')?.value === '成年',
      goodDetail?.specParams);

    console.log('\n== 4. 老商品不阻断；改规格时才校验 ==');
    const titleEdit = await call('PUT', `/api/seller/products/${legacySpuId}`,
      { title: `A10 老商品改标题 ${stamp}`, version: legacyVersion0 }, sellerToken);
    check('★ 只改标题：老商品不被新增必填项阻断', titleEdit.status === 200, titleEdit.body);
    const legacyAfterTitle = (await call('GET', `/api/seller/products/${legacySpuId}`, null, sellerToken)).body?.data;
    check('标题已更新且旧规格未被模板改写',
      legacyAfterTitle?.title === `A10 老商品改标题 ${stamp}`
      && (legacyAfterTitle?.specParams ?? [])[0]?.value === '保留值',
      { title: legacyAfterTitle?.title, specs: legacyAfterTitle?.specParams });

    const specEditMissing = await call('PUT', `/api/seller/products/${legacySpuId}`,
      { specParams: [{ name: '旧自定义', value: '保留值' }], version: legacyAfterTitle?.version }, sellerToken);
    check('改规格但缺必填 → 400', specEditMissing.status === 400 && msgOf(specEditMissing).includes('净含量'),
      specEditMissing.body);

    const specEditOk = await call('PUT', `/api/seller/products/${legacySpuId}`,
      { specParams: [{ name: '净含量', value: '5kg' }, { name: '适用阶段', value: '老年' }],
        version: legacyAfterTitle?.version }, sellerToken);
    check('补齐必填后改规格成功', specEditOk.status === 200, specEditOk.body);
    const legacyFinal = (await call('GET', `/api/seller/products/${legacySpuId}`, null, sellerToken)).body?.data;
    check('老商品规格已替换为补齐后的值',
      (legacyFinal?.specParams ?? []).length === 2
      && (legacyFinal.specParams ?? []).find((x) => x.name === '适用阶段')?.value === '老年',
      legacyFinal?.specParams);

    console.log('\n== 5. 模板启停语义 ==');
    const off = await call('PUT', `/api/admin/category-attrs/${stageId}/status?active=false`, null, opToken);
    check('停用「适用阶段」', off.status === 200, off.body);
    const publicAfterOff = (await call('GET', `/api/categories/${catId}/attrs`)).body?.data ?? [];
    check('公开模板不再返回停用项', publicAfterOff.length === 2
      && !publicAfterOff.some((x) => x.name === '适用阶段'), publicAfterOff.map((x) => x.name));
    const adminAfterOff = (await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken)).body?.data ?? [];
    check('后台列表仍能看到停用项（可再次启用）',
      adminAfterOff.find((x) => x.name === '适用阶段')?.status === 'INACTIVE', adminAfterOff);

    const withoutStage = await call('POST', '/api/seller/products', {
      title: `A10 停用后发布 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '净含量', value: '1kg' }], skus: sku(),
    }, sellerToken);
    const withoutStageId = withoutStage.body?.data?.id;
    if (withoutStageId) createdSpuIds.push(String(withoutStageId));
    check('停用的必填项不再拦截发布', withoutStage.status === 200 && Boolean(withoutStageId), withoutStage.body);

    const on = await call('PUT', `/api/admin/category-attrs/${stageId}/status?active=true`, null, opToken);
    check('重新启用「适用阶段」', on.status === 200, on.body);
    const afterOnDetail = (await call('GET', `/api/seller/products/${withoutStageId}`, null, sellerToken)).body?.data;
    const afterOnEdit = await call('PUT', `/api/seller/products/${withoutStageId}`,
      { specParams: [{ name: '净含量', value: '1kg' }], version: afterOnDetail?.version }, sellerToken);
    check('重新启用后改规格立即恢复拦截', afterOnEdit.status === 400 && msgOf(afterOnEdit).includes('适用阶段'),
      afterOnEdit.body);

    console.log('\n== 6. 回库断言 ==');
    const attrRows = sql(PRODUCT_DB,
      `SELECT name, input_type, is_required, IFNULL(options, '-'), sort_no, status
       FROM t_category_attr WHERE category_id = ${catId} ORDER BY sort_no, id`);
    check('库中模板 3 行', attrRows.length === 3, attrRows);
    const netRow = attrRows.find((r) => r.startsWith('净含量\t'));
    check('净含量：TEXT / 必填 / 无选项', netRow === '净含量\tTEXT\t1\t-\t1\tACTIVE', netRow);
    const stageRow = attrRows.find((r) => r.startsWith('适用阶段\t'));
    check('适用阶段：SELECT / 选项 JSON / 已重新启用',
      stageRow === '适用阶段\tSELECT\t1\t["幼年","成年","老年"]\t2\tACTIVE', stageRow);

    const specRow = sql(PRODUCT_DB,
      `SELECT spec_params FROM t_spu WHERE id = ${goodId}`);
    const specJson = specRow.join('');
    check('商品 spec_params 落库含模板值',
      specJson.includes('净含量') && specJson.includes('适用阶段') && specJson.includes('成年')
      && specJson.includes('蛋白质含量'), specJson.slice(0, 300));

    const auditCounts = sql(USER_DB,
      `SELECT action, COUNT(*) FROM t_audit_log
       WHERE target_type = 'CATEGORY_ATTR' AND created_at >= UTC_TIMESTAMP() - INTERVAL 15 MINUTE
       GROUP BY action`);
    const auditMap = Object.fromEntries(auditCounts.map((line) => {
      const [action, count] = line.split('\t');
      return [action, Number(count)];
    }));
    check('审计：新增 3 次', (auditMap.CATEGORY_ATTR_CREATE ?? 0) >= 3, auditMap);
    check('审计：编辑 1 次', (auditMap.CATEGORY_ATTR_UPDATE ?? 0) >= 1, auditMap);
    check('审计：启停 2 次', (auditMap.CATEGORY_ATTR_STATUS ?? 0) >= 2, auditMap);

    console.log('\n== 7. 删除模板后不再拦截，并清理夹具 ==');
    const delNet = await call('DELETE', `/api/admin/category-attrs/${netId}`, null, opToken);
    check('删除必填项「净含量」', delNet.status === 200, delNet.body);
    const afterDel = await call('POST', '/api/seller/products', {
      title: `A10 删除后发布 ${stamp}`, categoryId: catId, mainImage: '/img/products/dog-food.svg',
      specParams: [{ name: '适用阶段', value: '幼年' }], skus: sku(),
    }, sellerToken);
    const afterDelId = afterDel.body?.data?.id;
    if (afterDelId) createdSpuIds.push(String(afterDelId));
    check('删除后的属性不再要求填写', afterDel.status === 200 && Boolean(afterDelId), afterDel.body);

    for (const id of [stageId, proteinId]) {
      const r = await call('DELETE', `/api/admin/category-attrs/${id}`, null, opToken);
      check(`删除属性 ${id}`, r.status === 200, r.body);
    }
    const publicFinal = await call('GET', `/api/categories/${catId}/attrs`);
    check('模板删空后公开列表为空', (publicFinal.body?.data ?? []).length === 0, publicFinal.body);
    attrIds = [];

    const auditDel = sql(USER_DB,
      `SELECT COUNT(*) FROM t_audit_log WHERE action = 'CATEGORY_ATTR_DELETE'
       AND target_type = 'CATEGORY_ATTR' AND created_at >= UTC_TIMESTAMP() - INTERVAL 15 MINUTE`);
    check('审计：删除 3 次', Number(auditDel[0]) >= 3, auditDel[0]);
  } finally {
    console.log('\n== 8. 清理 ==');
    for (const id of createdSpuIds) {
      const r = await call('POST', `/api/seller/products/${id}/delete`, null, sellerToken);
      console.log(`  [CLEAN] 软删商品 ${id} -> ${r.status}`);
    }
    // 失败路径：把可能残留的属性与类目删掉，避免污染下一轮
    const list = await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken);
    for (const item of list.body?.data ?? []) {
      const r = await call('DELETE', `/api/admin/category-attrs/${item.id}`, null, opToken);
      console.log(`  [CLEAN] 删除残留属性 ${item.name} -> ${r.status}`);
    }
    if (catId) {
      const r = await call('DELETE', `/api/admin/categories/${catId}`, null, opToken);
      console.log(`  [CLEAN] 删除冒烟类目 ${catId} -> ${r.status}`);
      const leftAttrs = sql(PRODUCT_DB, `SELECT COUNT(*) FROM t_category_attr WHERE category_id = ${catId}`);
      check('清理后库中无残留模板', Number(leftAttrs[0]) === 0, leftAttrs[0]);
    }
  }

  await sleep(300);
  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();
