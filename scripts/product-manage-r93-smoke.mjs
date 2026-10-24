/**
 * 商品管理改造冒烟（2026-10-24，第九十三轮）。
 *
 * 守四组：
 *  A. 审核防绕过（核心）：驳回 → 编辑一次 → **再编辑一次** 仍为 PENDING（修复前第二次会自动通过）；
 *     人工通过后 rejected_at 清空，此后编辑才允许自动放行；
 *     另验证「卖家主动下架的商品，编辑后不会被悄悄上架」。
 *  B. 内容字段：富文本详情（含 script/onerror 注入）落库后被白名单消毒；
 *     商品介绍 / 规格参数 / 包装规格可存可读。
 *  C. 图片顺序：公开详情 images[0] === mainImage（首页卡片与详情首图一致）。
 *  D. 卖家列表回带评分字段（供商品分析页使用）。
 *
 * 前置：容器在跑；incremental/46 已执行；卖家 13600003333（店主）、运营 13684295254 存在。
 * 用法：NO_PROXY="*" node scripts/product-manage-r93-smoke.mjs
 */
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';

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

// 历史轮次遗留的在售 R93 商品会拉高类目均价，使本轮新品被判 PRICE_ANOMALY。
// 每次启动先下架它们（只动标题前缀匹配且在售的，幂等；不计入断言）。
async function offShelfStaleR93(token) {
  const stale = [];
  for (let page = 1; page <= 50; page++) {
    const data = (await call('GET', `/api/seller/products?page=${page}&pageSize=50`, null, token)).body?.data;
    const items = data?.items ?? [];
    stale.push(...items.filter((x) => String(x.title ?? '').startsWith('R93 冒烟商品') && x.status === 'ON_SALE'));
    if (items.length < 50 || page * 50 >= (data?.total ?? 0)) break;
  }
  let offShelfed = 0;
  for (const p of stale) {
    const r = await call('POST', `/api/seller/products/${p.id}/status`, { action: 'OFF_SHELF' }, token);
    if (r.status === 200) offShelfed++;
    else console.log(`  [CLEAN] 下架失败 id=${p.id} status=${r.status}`);
  }
  console.log(`  [CLEAN] 启动自清理：遗留 R93 在售商品 ${stale.length} 件，已下架 ${offShelfed} 件`);
}

async function main() {
  console.log('== 0. 登录卖家与运营 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  check('卖家（店主）登录成功', Boolean(sellerToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  if (!sellerToken || !opToken) return 1;
  await offShelfStaleR93(sellerToken);

  console.log('\n== 1. 发布商品（含富文本与四类内容） ==');
  const stamp = String(Date.now()).slice(-6);
  const created = await call('POST', '/api/seller/products', {
    title: `R93 冒烟商品 ${stamp}`,
    subtitle: '初始副标题',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    images: ['/img/products/cat-food.svg', '/img/products/cat-litter.svg'],
    detail: '这是商品介绍的纯文本',
    intro: '这是新的商品介绍字段',
    detailHtml: '<h2>详情标题</h2><p>正文<strong>加粗</strong></p><script>alert(1)</script><img src=x onerror="alert(2)"><a href="javascript:alert(3)">bad</a>',
    specParams: [{ name: '净含量', value: '2kg' }, { name: '适用阶段', value: '成犬' }],
    packaging: [{ name: '包装形式', value: '袋装' }],
    skus: [{ specName: '2kg', price: 1990, stock: 10, image: null }],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  check('发布成功', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;

  const detail1 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('商品介绍已保存', detail1?.intro === '这是新的商品介绍字段', detail1?.intro);
  check('规格参数已保存（2 条）', Array.isArray(detail1?.specParams) && detail1.specParams.length === 2, detail1?.specParams);
  check('包装规格已保存（1 条）', Array.isArray(detail1?.packaging) && detail1.packaging.length === 1, detail1?.packaging);
  const html = String(detail1?.detailHtml || '');
  check('富文本已落库且保留标题/加粗', html.includes('<h2>') && html.includes('<strong>'), html.slice(0, 120));
  check('富文本中的 script 被剥离', !/<\s*script/i.test(html), html.slice(0, 160));
  check('富文本中的 onerror 事件属性被剥离', !/onerror/i.test(html), html.slice(0, 160));
  check('富文本中的 javascript: 链接被剥离', !/javascript:/i.test(html), html.slice(0, 160));

  console.log('\n== 2. 图片顺序（公开详情） ==');
  const pub1 = (await call('GET', `/api/products/${spuId}`)).body?.data;
  if (pub1) {
    check('公开详情第一张 = 主图', pub1?.images?.[0] === '/img/products/dog-food.svg', pub1?.images);
    check('副图依次在后且不重复', JSON.stringify(pub1?.images) === JSON.stringify([
      '/img/products/dog-food.svg', '/img/products/cat-food.svg', '/img/products/cat-litter.svg',
    ]), pub1?.images);
    check('公开详情带富文本/规格/包装', Boolean(pub1?.detailHtml) && (pub1?.specParams ?? []).length === 2
      && (pub1?.packaging ?? []).length === 1, { html: Boolean(pub1?.detailHtml) });
  } else {
    check('公开详情可读（新商品自动过审后可见）', false, '404');
  }

  console.log('\n== 3. 审核：驳回 → 两次编辑 ==');
  const rej = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'REJECT', reason: '图片不清晰，请更换主图' }, opToken);
  check('运营驳回成功', rej.status === 200, rej.body);
  const afterReject = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('驳回后状态 = REJECTED 且下架', afterReject?.auditStatus === 'REJECTED' && afterReject?.status === 'OFF_SHELF',
    { audit: afterReject?.auditStatus, status: afterReject?.status });

  const edit1 = await call('PUT', `/api/seller/products/${spuId}`, { title: `R93 冒烟商品 ${stamp}（改1）` }, sellerToken);
  check('第一次编辑成功', edit1.status === 200, edit1.body);
  const afterEdit1 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('第一次编辑 → 待审核（原有行为）', afterEdit1?.auditStatus === 'PENDING', afterEdit1?.auditStatus);

  const edit2 = await call('PUT', `/api/seller/products/${spuId}`, { subtitle: '第二次编辑的副标题' }, sellerToken);
  check('第二次编辑成功', edit2.status === 200, edit2.body);
  const afterEdit2 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('★ 第二次编辑仍为待审核（修复「改两次绕过」）', afterEdit2?.auditStatus === 'PENDING',
    { audit: afterEdit2?.auditStatus, source: afterEdit2?.auditSource });
  check('商家侧没有混入"自动通过"来源', afterEdit2?.auditSource !== 'AUTO', afterEdit2?.auditSource);

  console.log('\n== 4. 人工通过后可自动放行 ==');
  const appr = await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE' }, opToken);
  check('运营通过成功', appr.status === 200, appr.body);
  const afterApprove = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('通过后驳回原因被清空', afterApprove?.auditReason == null, afterApprove?.auditReason);

  const edit3 = await call('PUT', `/api/seller/products/${spuId}`, { subtitle: '平反后的编辑' }, sellerToken);
  const afterEdit3 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('人工通过后编辑可自动放行（标记已清）', edit3.status === 200 && afterEdit3?.auditStatus === 'APPROVED',
    { status: edit3.status, audit: afterEdit3?.auditStatus });

  console.log('\n== 5. 卖家主动下架 → 编辑不应悄悄上架 ==');
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, sellerToken);
  await call('PUT', `/api/seller/products/${spuId}`, { subtitle: '下架状态下改个字' }, sellerToken);
  const afterOffEdit = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('主动下架的商品编辑后仍为下架', afterOffEdit?.status === 'OFF_SHELF', afterOffEdit?.status);

  console.log('\n== 6. 卖家列表回带评分字段 ==');
  const list = (await call('GET', '/api/seller/products?page=1&pageSize=50', null, sellerToken)).body?.data;
  const row = list?.items?.find((x) => String(x.id) === String(spuId));
  check('列表项含 rating / ratingCount', row != null && 'rating' in row && 'ratingCount' in row,
    row ? { rating: row.rating, ratingCount: row.ratingCount } : null);

  console.log('\n== 7. SKU 改价 / 改库存（不触发重审） ==');
  const beforePrice = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  const sku = (beforePrice?.skus ?? [])[0];
  check('存在可修改的 SKU', Boolean(sku?.id), beforePrice?.skus);
  if (sku?.id) {
    const newPrice = (sku.price ?? 0) + 700;
    const newStock = (sku.stock ?? 0) + 3;
    const auditBefore = beforePrice?.auditStatus;
    const upd = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
      { skus: [{ id: sku.id, price: newPrice, stock: newStock }] }, sellerToken);
    check('改价改库存接口成功', upd.status === 200, upd.body);
    const afterPrice = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
    const skuAfter = (afterPrice?.skus ?? []).find((x) => String(x.id) === String(sku.id));
    check('SKU 价格已更新', skuAfter?.price === newPrice, { before: sku.price, after: skuAfter?.price });
    check('SKU 库存已更新', skuAfter?.stock === newStock, { before: sku.stock, after: skuAfter?.stock });
    check('商品价格区间已同步（min=max=新价）',
      afterPrice?.minPrice === (afterPrice?.skus ?? []).reduce((m, x) => Math.min(m, x.price), Infinity)
      && afterPrice?.maxPrice === (afterPrice?.skus ?? []).reduce((m, x) => Math.max(m, x.price), -Infinity),
      { min: afterPrice?.minPrice, max: afterPrice?.maxPrice });
    check('★ 改价不改变审核态（不重审）', afterPrice?.auditStatus === auditBefore,
      { before: auditBefore, after: afterPrice?.auditStatus });

    const badPrice = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
      { skus: [{ id: sku.id, price: 0 }] }, sellerToken);
    check('价格 0 被拒（400/业务错误）', badPrice.status === 400 || (badPrice.body?.code && badPrice.body.code !== 0), badPrice.body);
    const badStock = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
      { skus: [{ id: sku.id, stock: -1 }] }, sellerToken);
    check('负库存被拒（400/业务错误）', badStock.status === 400 || (badStock.body?.code && badStock.body.code !== 0), badStock.body);
    const ghost = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
      { skus: [{ id: 999999999999, price: 100 }] }, sellerToken);
    check('不属于该商品的 SKU id → 404（不泄露归属）', ghost.status === 404, ghost.status);
  }

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();
