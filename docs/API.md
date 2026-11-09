# 接口清单（API）

> 完整端点表与语义说明，从 README「已实现的接口」迁出（2026-11-09）。
> 由源码机械生成的契约全集见 `docs/agent/04-接口契约全集.md`，OpenAPI 摘要见 `docs/openapi/endpoints.md`。


## 通用约定

统一响应：`{"code","message","data","requestId","timestamp"}`；失败时 `code` 与 HTTP 状态码语义一致
（400 参数 / 401 未认证 / 403 无权限 / 404 不存在 / 409 冲突 / 422 业务规则 / 429 限流 / 503 降级）。

## 用户域（`duoduo-user`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并直接登录（手机号唯一，冲突转 409） | 免 |
| POST | `/api/auth/login` | 密码登录；账号不存在与密码错误返回同一提示，防手机号枚举 | 免 |
| POST | `/api/auth/refresh` | 刷新令牌轮转：旧令牌立即吊销，签发新对 | 免 |
| POST | `/api/auth/logout` | 吊销刷新令牌并清 Cookie | 免 |
| GET / PATCH | `/api/users/me` | 当前用户资料 / 改昵称头像 | 需登录 |
| POST | `/api/users/me/realname` | 实名认证（身份证仅存掩码） | 需登录 |
| POST | `/api/users/me/merchant` | **申请成为卖家**（实名前置：未实名 422；已是卖家则幂等返回）；角色升级后**需重新登录**才生效 | 需登录 |
| GET / POST | `/api/pets` | 宠物档案列表 / 新建（首只自动设为当前宠物） | 需登录 |
| PUT / DELETE | `/api/pets/{id}` | 更新 / 删除档案（默认宠物自动顺延） | 需登录 |
| POST | `/api/pets/{id}/default` | 设为当前宠物 | 需登录 |
| GET / POST | `/api/addresses` | **收货地址簿**（A8）：列表（**默认地址排最前**）/ 新增（**首个自动成为默认**） | 需登录 |
| PUT / DELETE | `/api/addresses/{id}` | 修改 / 删除（**删默认会自动顺延**到剩下的一条） | 需登录 |
| POST | `/api/addresses/{id}/default` | 设为默认（事务内先清旧默认，库内恒有且仅有 1 条默认） | 需登录 |
| GET | `/api/addresses/default` | 取默认地址；**没有默认时返回 200 + `data=null`**（不是 404，前端据此渲染「去添加」） | 需登录 |
| GET | `/api/addresses/internal/snapshot` | **内部快照**（供交易域下单取收货信息）：`?userId=&addressId=`；`fullAddress` 与地址簿接口**逐字相同**（拼接口径只有一处） | **内部凭据**，不进网关 |

> **地址簿的两条纪律**：
> ① **归属校验统一返回 404** —— 改 / 删 / 设默认他人地址一律与「id 不存在」**同码同文案**，刻意不区分
> 「不存在」与「无权限」，避免用状态码差异枚举他人地址 ID（这与 `docs/agent/07` 原文写的「403」是有意偏离）。
> ② **下单写的是快照** —— 用 `addressId` 下单时，收货人 / 电话 / 完整地址在**下单瞬间**写入 `t_order.receiver_*`，
> 此后改 / 删地址簿**都不影响历史订单**。传了 `addressId` 就**只认它**（直填三字段被忽略），避免两套来源冲突。

## 用户域：多多币（`duoduo-user` `/api/coins`，R-P1 第一百二十六轮）

单币种积分：**1 币 = 1 分**、与券叠加、单笔抵扣上限 = 券后金额 50%（向下取整）、12 个月有效、
订单完成 **T+7** 返币、平台承担抵扣成本（结算单列 `coinSubsidyAmount` 补贴）。
数据五表：账户 / 批次（`t_coin_lot`，12 个月有效期）/ 流水 / 冻结 / 冻结批次明细；
**不变式：账户余额 = Σ 未过期批次剩余**（加发建批次、释放成对退批次、回收上限 = 批次可扣量）。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/coins/me` | 我的币账户：余额 / 累计获得 / 消耗 / 30 天内到期批次 | 需登录 |
| GET | `/api/coins/ledger?page=&pageSize=` | 我的币流水（direction + 场景 + 记账后余额快照，可对账） | 需登录 |
| GET | `/api/admin/coins/summary` | 全站汇总：负债余额 / 累计发放 / 消耗 / 过期 / 开户数 / 30 天到期量 | 运营 |
| GET | `/api/admin/coins/ledger` | 全站流水（`userId` / `direction` 过滤） | 运营 |
| POST | `/api/admin/coins/adjust` | 人工调整（正=加发建批次、负=回收；**原因必填**，归 SECURITY 档审计；回收超批次可扣量 422） | 运营 |
| POST | `/api/coins/signin` | 每日签到（R-P2）：一天一次（唯一索引兜底），连签 3/7 天当天加发 | 需登录 |
| GET | `/api/coins/signin/month?ym=` | 当月签到月历：已签日期 / 连签天数 / 今日是否已签 | 需登录 |
| POST | `/api/coins/internal/*` | 供交易域调用：`balance` / `freeze`（下单冻结，幂等）/ `deduct`（支付核销）/ `release`（关单释放）/ `refund`（退款退回）/ `earn`（T+7 返币） | **内部凭据** |

> **失败语义三档**（`CoinGateway`）：**查询**（balanceOf）失败降级为 0（结算页必须打得开，币可以不用）；
> **动钱**（freeze / deduct / refundBack）失败必须抛（冻结失败 → 下单回滚；退款退币失败 → 整笔退款回滚可重试）；
> **释放**（release）best-effort + **冻结 TTL 兜底任务**（120 分钟 > 订单超时关单 30 分钟）双保险。
> 下单**只传 `useCoins` 开关不传币数**：用多少由服务端按「余额贪心 + 50% 上限」逐单分摊（`allocateCoins`），
> 冻结那一刻由用户域硬校验 —— 前端永远不是资金口径的判定方。

## 商品域（`duoduo-product`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/categories` · `/flat` · `/{id}` | 两级类目树 / 扁平列表 / 单个类目 | 免 |
| GET | `/api/home` | 首页聚合：类目 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选 | 免 |
| GET | `/api/products` | 商品检索（参数见下表） | 免 |
| GET | `/api/products/{spuId}` | 商品详情：SKU、适用属性聚合、同类推荐 | 免 |
| GET | `/api/products/skus/batch?ids=2001,2002` | **批量 SKU 采购视图**（供交易服务调用；一次取几十个，避免 N 次跨服务调用） | 免 |
| POST | `/api/products/skus/stock/{freeze,release,deduct,restock}` | **库存写接口**（冻结 / 释放 / 扣减 / 退货入库），见下 | **内部凭据** |
| GET | `/api/listings/search` · `/api/listings/{id}` | C2C 闲置**列表**（只返回 `ON_SALE` + `APPROVED`）/ **详情** + 同款全新推荐。**详情每次访问浏览量 +1**（数据库侧原子自增，响应即最新值；卖家看自己的走 `mine/{id}` 不计）。**卡片的两个计数口径不同**：`viewCount` = 浏览次数（打开/下拉刷新各 +1，同一用户可累加）；`wantCount` = 想要人数（买家首次咨询时 +1，按买家去重，每人只记一次） | 免 |
| POST | `/api/listings` | **发布闲置**（落 `PENDING` 待审；需实名；`sellerId`/`status`/`auditStatus` 概不接受客户端传值） | 需登录 |
| GET | `/api/listings/mine/list` | **我的发布**（`status` 与 `auditStatus` 两维筛选：`ON_SALE`/`OFF_SHELF`/`SOLD` × `PENDING`/`APPROVED`/`REJECTED`，含驳回原因；`auditStatus` 非法值 400）。**服务端两维正交**，小程序 5 个标签按**互斥单状态**拼参（交易三标签必须同时带 `auditStatus=APPROVED`，否则待审件会同时出现在「审核中」与「在售」——见 R53 热修二） | 需登录 |
| GET | `/api/listings/mine/{id}` | **卖家视角详情**（`ListingOwnerView` 全集字段含审核原因；未过审也可读自己条目；非本人 403） | 需登录 |
| POST | `/api/listings/{id}/resubmit` | **重新提审**（仅 `REJECTED` 可调，PENDING/APPROVED 均 422；实名与违禁词复检；成功后回 `PENDING` + `ON_SALE`，清空驳回原因） | 需登录 |
| POST | `/api/listings/{id}/status` | **上架 / 下架**（`action=ON_SALE`/`OFF_SHELF`；幂等开关；上架要求已过审；他人闲置 403、不存在 404） | 需登录 |
| POST | `/api/listings/{id}/edit` | **编辑闲置**（需实名；内容变更即回 `PENDING` 重新送审——审核结论针对旧内容；驳回件编辑后 `status` 恢复 `ON_SALE`；已售出 422；非本人 403）。**三段路径**：白名单 `/api/listings/*` 不区分方法，两段会把写端点放成匿名可调 | 需登录 |
| POST | `/api/listings/{id}/delete` | **删除闲置（软删）**（写 `deleted_at` + 落 `OFF_SHELF`，行留库供订单 / 会话 / 举报追溯；七处读路径与 ES 索引全部排除软删；已售出 422；非本人 403） | 需登录 |
| GET | `/api/listings/internal/cards` | **闲置卡批量视图**（供社交域取 `sellerId` 等事实；`X-Internal-Token` 保护） | 内部凭据 |
| POST | `/api/listings/internal/{id}/want?buyerId=` | **「想要」上报**（供社交域在买家**首次**发咨询消息时调用；`INSERT IGNORE` 去重表，**重复上报返回 `counted=false` 且不重复计数**；闲置不存在 404。**刻意用 POST**：这是写操作，GET 会被预取/重试刷歪计数） | 内部凭据 |
| POST | `/api/listings/{id}/orders` | **担保下单**（B-11：路径 id 为准、请求体不带 listingId 防「看 A 买 B」；价格取闲置快照；**单件 CAS 防超卖**（ON_SALE+APPROVED+未软删→SOLD，0 行 422「手慢了」）；同幂等键重试重放首单（事实校验与 CAS 落空两处都先反查交易域）；交易域建单失败补偿回滚 SOLD→ON_SALE。需实名） | 需登录 |
| GET | `/api/listings/orders/sold` | **我的卖出订单**（B-11：数据事实在交易域 t_order（orderType=C2C），商品域透传；登录即本人、不收卖家 id 参数；个人卖家无 SELLER 角色也能查） | 需登录 |
| POST | `/api/listings/orders/{orderNo}/ship` | **闲置订单发货**（B-11：归属（订单 sellerId==登录人）与状态机（PAID→SHIPPED）在交易域校验，403/422 原码透传；复用 shipInternal 与商家端同一状态机） | 需登录 |
| POST | `/api/listings/internal/{id}/release` | **释放闲置**（B-11：交易域在用户取消/运营强关/超时关单/退款四类钩子后回调，SOLD→ON_SALE；**幂等**：已不是 SOLD 返回 `released=false` 不报错；`X-Internal-Token` 保护） | 内部凭据 |
| GET | `/api/meta/brands` · `/dictionaries` | 品牌列表 / 筛选字典 | 免 |

**第三方商品上架审核（E14）** —— 商品有**两个正交维度**：交易维度 `status`（在售 / 下架，卖家可控）与
审核维度 `auditStatus`（`PENDING`/`APPROVED`/`REJECTED`，平台判定）。对外可见的唯一口径是**两者同时满足**，
由 `ProductService.visibleSpuWrapper()` 单点收口（MySQL 7 条读路径 + ES 索引/查询 + 采购视图），
未过审商品详情一律 **404**（与不存在同码，防状态码探测）。**平台自营免审**：`supplyType=self` 的商品恒 `APPROVED`。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/seller/products` | **我的商品**（按**审核维度** `auditStatus` 筛选；店主与店员看到同一批，归属店铺主体） | 需登录（SELLER / 店员 / 运营） |
| POST | `/api/seller/products` | **提交商品**（SPU + SKU 一事务落库；价格按**分**存储、SKU 编码服务端生成）。归属 / 供货类型 / 审核态**全部由服务端判定**——第三方落 `PENDING`，运营落 `APPROVED`；请求体带这三个字段无效 | 需登录 |
| GET | `/api/seller/products/{spuId}` | **商家视角详情**（编辑回填用；公开详情对未过审一律 404，但卖家必须读得回自己被我驳回的商品；非本店 403） | 需登录 |
| PUT | `/api/seller/products/{spuId}` | **编辑商品**（全内容字段；**内容变更即回 `PENDING` 并恢复在售**——审核结论针对「当时那段内容」，放它继续挂着过审等于允许「先过审、再改违规内容」；非本店 403） | 需登录 |
| POST | `/api/seller/products/{spuId}/status` | **上下架**（`ON_SALE`/`OFF_SHELF`）。**上架门禁**：目标态为在售时要求已过审，否则 **422** 并给出下一步提示（是「等审核」还是「去改内容」）；平台自营不受限 | 需登录 |
| POST | `/api/seller/products/{spuId}/resubmit` | **重新提交审核**（仅 `REJECTED` 可调，`PENDING`/`APPROVED` 均 422） | 需登录 |
| POST | `/api/seller/products/proxy-requests` | **E23 · 申请代运营（商品类）**：`{targetType:'PRODUCT', targetRef:spuId, actionType:'OFF_SHELF', reason}`；`tenant_id` **由登录态推导**、不由请求体指定 | 店主 / 店员（**平台账号 403**） |
| GET | `/api/seller/products/proxy-requests` | **E23 · 我的商品类工单**（只列本店；跨店看不到） | 店主 / 店员 |
| POST | `/api/seller/products/proxy-requests/{workOrderNo}/void` | **E23 · 撤销未执行的工单**（已消费/已撤销 400；不是本店的 404） | 店主 / 店员 |
| GET | `/api/admin/products/proxy-work-orders` | **E23 · 平台待办（商品域）**：可按 `tenantId` 或 `targetRef`(spuId) 筛选 | 运营 / 管理员（进门），执行另需权限点 |
| POST | `/api/admin/products/{spuId}/proxy-off-shelf` | **E23 · 代下架**：`{workOrderNo, reason}` 均必填；**持有效工单**才能替第三方店铺下架；自营商品走这里 **422** | `tenant:proxy-ops` |
| GET | `/api/admin/products` | **商品审核台**（只列**第三方**商品：自营免审，混进队列只会干扰待办；待审排最前；可按 `sellerId` 筛选） | 运营 / 管理员 |
| GET | `/api/admin/products/{spuId}` | **审核详情**（带全部背审材料，运营不必点进二级页） | 运营 / 管理员 |
| POST | `/api/admin/products/{spuId}/audit` | **审核结论**（`action=APPROVE`/`REJECT`；**驳回必填原因**，否则 400；**可重入**——允许改判，把改判逼成改库才会留下无法追溯的结论；驳回同时把 `status` 压成 `OFF_SHELF`） | 运营 / 管理员 |

`GET /api/products` 查询参数：

| 参数 | 说明 |
|---|---|
| `keyword` | 关键词，命中标题或副标题；**含违禁词直接 422** |
| `categoryId` | 类目 id；传一级类目会**自动展开其下子类目** |
| `brandId` / `supplyType` | 品牌 / 供货类型（`self` 自营、`merchant` 第三方） |
| `minPrice` / `maxPrice` | 价格区间（单位：分），语义是**区间相交**而非「最低价落在区间内」 |
| `species` / `lifeStage` | 按宠物档案筛选（`cat`/`dog`，`BABY`/`ADULT`/`SENIOR`） |
| `inStockOnly` | 只看有货；判据是**可售余量**（`stock − frozen_stock`），被他人下单锁定的不算有货 |
| `sort` | `default` / `sales` / `price_asc` / `price_desc` / `rating` / `newest`，非法值返回 400 |
| `page` / `pageSize` | 页码从 1 开始，`pageSize` 上限 100 |

## 交易域（`duoduo-trade`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/cart` | 购物车列表：实时价格 + 可售库存 + **失效原因码** + 已勾选合计 | 需登录 |
| GET | `/api/cart/count` | 件数角标（不查商品服务） | 需登录 |
| POST | `/api/cart/items` | 加购（同 SKU **累加数量**，不新增行） | 需登录 |
| PATCH | `/api/cart/items/{skuId}` | 改数量 | 需登录 |
| PATCH | `/api/cart/items/{skuId}/selection` | 勾选 / 取消勾选 | 需登录 |
| DELETE | `/api/cart/items/{skuId}` · `/api/cart/selected` | 删除一条 / 清空已勾选 | 需登录 |
| POST | `/api/orders/preview` | **结算预览**（只读）：返回按卖家拆单的 items / 商品总额 / 运费 / 券后应付；**不建单、不占券**；券不可用回 `couponMessage` 而非报错 | 需登录 |
| POST | `/api/orders` | **下单**（按卖家拆单 + 明细快照 + 冻结库存），必须带 `idempotentKey` | 需登录 |
| GET | `/api/orders` | 我的订单列表（可按 `status` 过滤，**每条内嵌资金托管信息**） | 需登录 |
| GET | `/api/orders/{orderNo}` | 订单详情（含明细快照 + 内嵌托管信息） | 需登录 |
| GET | `/api/orders/{orderNo}/settlement` | **查询该订单的资金托管状态**（托管中 / 已放款 / 已退回买家） | 需登录 |
| POST | `/api/orders/{orderNo}/pay` | **支付预下单**（必须带 `idempotentKey`）；`channel=sandbox` 返回**二维码内容与过期时间**，`channel=mock` 即时完成 | 需登录 |
| POST | `/api/orders/{orderNo}/cancel` | 取消（**仅待付款可取消**），并释放冻结 + 作废未支付支付单 | 需登录 |
| POST | `/api/orders/{orderNo}/confirm` | **确认收货**（已发货 → 已完成），并**放款给卖家** | 需登录 |
| POST | `/api/orders/{orderNo}/refund` | **买家自助退款**（仅「已付款且未发货」），资金退回买家 | 需登录 |
| POST | `/api/orders/{orderNo}/ship` | 发货（已付款 → 已发货） | **内部凭据** |
| POST | `/api/orders/internal/{orderNo}/refund` | **运营侧退款**（售后审核通过后执行，已付款 / 已发货均可） | **内部凭据** |
| POST | `/api/orders/internal/sweep` | 手动触发一轮补偿调度（超时关单 / 释放冻结 / 自动确认收货 / 入库补偿 / 支付单过期） | **内部凭据** |
| POST | `/api/orders/internal/listing-orders` | **C2C 建单**（B-11：商品域完成 listing CAS 后委托；orderType=C2C、单件无 SKU 明细、不冻库存、一口价=快照价；幂等撞键事务外重放且只认 C2C） | **内部凭据** |
| GET | `/api/orders/internal/listing-orders/idem` | **幂等键反查**（B-11：商品域 CAS 落空/已售时区分「同键重试」与「被买走」；无单返回 data=null） | **内部凭据** |
| GET | `/api/orders/internal/listing-orders/sold` | **卖家卖出订单列表**（B-11：sellerId 由商品域从登录态带入，本方法自己加归属条件） | **内部凭据** |
| POST | `/api/orders/internal/{orderNo}/listing-ship` | **闲置订单发货**（B-11：C2C 单 sellerId==操作者、PAID 才可发；复用 shipInternal 同一状态机；关单/退款经 AFTER_COMMIT 事件回调商品域释放闲置） | **内部凭据** |

## 支付渠道（`duoduo-trade` /payment）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/payments/callback/{channel}` | **渠道支付回调**（HMAC 验签 + 金额核对 + 幂等入账；时间戳过期 / 签名错误拒绝） | 渠道密钥验签 |
| POST | `/api/payments/sandbox/{payNo}` | **沙箱「扫码后确认支付」**（模拟用户在收银台点确认；走与回调同一套验签与入账逻辑） | 免（沙箱专属） |
| GET | `/api/payments/sandbox/pay/{payNo}` | **沙箱收银台页面**（二维码指向这里；展示单号 / 金额 / 状态，带确认按钮） | 免（扫码设备无登录态） |
| GET | `/api/payments/{payNo}` | 查询支付单状态（仅本人） | 需登录 |

## 卖家中心（`duoduo-trade` /api/seller，M5-9）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/seller/summary` | 经营概览：各状态订单数 + 托管/已放款/已退回金额（**过滤后汇总仍是全量**） | 卖家或运营 |
| GET | `/api/seller/orders` | 我的订单列表（按 `status` 过滤；**只返回归属自己的单**） | 卖家或运营 |
| GET | `/api/seller/orders/{orderNo}` | 订单详情（含买家收货信息与资金区） | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/ship` | **发货**（填物流公司/单号，落 `t_order` 并回显）；资金**仍托管**，不触发放款 | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/refund` | **售后退款**（已付款/已发货；已完成需追偿或平台垫付，返回 422） | 归属校验 |
| GET | `/api/seller/settlements` | 结算对账（分页 + 全量汇总金额） | 卖家或运营 |
| GET | `/api/seller/settlements/wallet` | **E22 · 我的钱包**：待结算/冻结中/可提现(净额)/累计到账/欠款/下次解冻 | 卖家（店员 403） |
| GET | `/api/seller/settlements/bills` | **E22 · 结算账单**（租户 × 周期）；`period` 形如 `2026-10` 或 `2026-10-05`，不传=当前月，非法 400 | 卖家（店员 403） |
| POST | `/api/seller/proxy-requests` | **E23 · 申请代运营（订单类）**：`{targetType:'ORDER', targetRef:orderNo, actionType:'SHIP'\|'AFTERSALE', reason}`；工单号带 `WO-ORD-` 前缀，`tenant_id` 由登录态推导 | 店主 / 店员（**平台账号 403**） |
| GET | `/api/seller/proxy-requests` | **E23 · 我的订单类工单**（含 `openCount`） | 店主 / 店员 |
| POST | `/api/seller/proxy-requests/{workOrderNo}/void` | **E23 · 撤销未执行的工单** | 店主 / 店员 |

**E22 佣金与结算口径（4 条规则，唯一出处都在 `SettlementService`）**：

- **抽佣 6%（600 bp）**，四舍五入到分：`(amount × bps + 5000) / 10000`。
  **自营（`seller_id = 0`）免佣** —— 佣金从左手交到右手，收了只会让指标虚增。
  费率走配置 `duoduo.trade.platform-fee-bps`，配 0 即全平台免佣。
- **T+7 可提现**：`released_at`（记账）与 `release_at`（可提现）分开存，
  合成一个字段表达不了"钱已经是他的、但还不能取"。
- **欠款抵扣**：可提现 = 已解冻 − 未结欠款，且不小于 0；**只算 RELEASED 不算 ADVANCED**
  （垫付的钱卖家没拿到，算进去会出现"越退款越有钱"的错账）。
- **双端同源**：商家端与平台端的钱包/账单调**同一个**服务方法，逐分一致 ——
  两边各算一遍的后果是"平台说能提 500、商家看到 480"，而那种分歧在技术上无解。

**★ 金额 / 计数字段的类型口径（2026-10-11 实测更正）**

`LongToStringJacksonModule` 的规则是**按字段名**判：命中 `NUMERIC_LONG_FIELDS`（金额 / 计数 / epoch 时间戳）→ 保持 JSON 数字，
否则（绝大多数 `Long` 是 id）→ 转成字符串。

这里原先写着「**该白名单只对普通 POJO 生效，record 组件不流经那条路径，所以 record 的金额字段必须逐个标注**」。
**该说法已被实验推翻**：新增的 `LongToStringJacksonModuleTest` 用同一个 ObjectMapper 把「普通类」与「record」各序列化一次，
**4/4 通过、行为完全一致**（命中白名单的 `payAmount` 是数字；未命中的 `someNumber` 与标识 `spuId` 是字符串）。
集成侧旁证：`scripts/id-precision-smoke.ps1`（**56/0**）里那 14 条「金额必须是 JSON 数字」的断言，
被测对象恰恰是 record（`OrderView.payAmount` / `TemplateView.thresholdAmount` …）。

- 因此 **record 上加 `@JsonSerialize(using = NumericLongSerializer.class)` 是可选的保险丝**，不是必需补偿（既有 34 处保留不动）；
- **真正会漏的只有一种**：语义是金额/计数、但**名字没登记进白名单**的新字段（例如新写的 `extraFee`）——
  它会被**静默**字符串化成 `"12900"`，不报任何错，只表现为前端 `+` 变拼接、`===` 恒 false、`.toFixed()` 抛错。
  **新增这类字段请把它加进 `NUMERIC_LONG_FIELDS`**（按名字生效，POJO 与 record 都管用），不要给单个 record 打补丁；
- `sellerId` / `tenantId` 这类**标识**刻意不标（标识不该参与算术）。


**权限模型（刻意的三条边界）**：

- **准入与归属分开校验**。准入看角色（非卖家 403），归属看 `t_order.seller_id`。
  **订单不存在返回 404、存在但不是你的返回 403**——刻意区分：订单号是雪花 ID 不可枚举，
  若统一 404 会让越权探测看起来像「查无此单」。
- **状态机不重写**。发货与退款转调 `OrderService` 的 `shipInternal` / `refundInternal`，
  与内部凭据入口共用同一段逻辑，避免两处状态机各改各的。
- **运营可见平台自营**（`sellerId=0`），因为当前商品域没有商家字段，自营单没有真正的卖家账号可登录。
- 接口**不接受任何 `sellerId` 参数**——可见范围完全由登录态推导，杜绝「换个 ID 看别人的单」。

> `/api/seller/**` **不在网关白名单里**（必须带登录态），也不受 `InternalTokenFilter` 管辖。
> 内部凭据只能证明「你是自己人」，证明不了「这单是你的」。

**E23 代运营口径（平台替第三方店铺做事，唯一的合法入口是一张工单）**

- **默认阻断**：平台账号对第三方店铺的订单 / 商品**没有任何直接权限**
  （订单侧 `seller_id` 归属校验 403；商品侧 `changeStatus` 收窄为「只有自营才能直接改」）。
- **唯一例外 = 店主给出的工单**，六个条件同时成立才算有效授权：
  ① 工单存在 ② 状态 `OPEN` ③ `target_type` 对得上 ④ `action_type` 对得上
  ⑤ `target_ref` 就是本次目标 ⑥ `tenant_id` = 目标的店铺主体 userId。
  判据顺序刻意是「先 400/404（授权能不能用）→ 再 403（对不对得上）」：
  运营看到 400 知道查工单、看到 403 知道查归属，两类事故的处理方式完全不同。
- **白名单三个动作**：代发货 / 代处理售后 / 代下架。执行时**必须**填执行说明，全量写审计。
- **一次性消费（CAS）**：`UPDATE ... WHERE id=? AND status='OPEN'`，影响行数 0 → **409 必须中止**；
  消费与动作在**同一个本地事务**里 —— 这正是工单表在交易库与商品库**各放一张**、
  而不是放共享库的原因（跨服务只能"先消费再调用"，中间失败会烧掉授权却没干活）。
  两表列名逐列相同，**共享的是契约不是代码**。
- **可撤销**：`OPEN → VOID`；已消费/已撤销的工单再用 → 400。
- **平台不能自己造授权**：平台账号调申请入口一律 403 —— 若能自己授权再自己执行，
  这套机制就只是个填写框，审计记录会很完整而它证明的事实并不存在。
- **商品域的"自营"判据是两个信号的与**：`supply_type != 'merchant'` **且** `seller_id = 0`
  （`SellerProductService.isPlatformSelf`，全仓唯一出口）。任一信号指向第三方就按第三方走 ——
  判重的代价是多走一步流程，判轻的代价是越权。**归属校验只用 `seller_id` 是不安全的**：
  它会让"归属列被改错"变成一条免授权通道。
- **审计动作码**：`ORDER_PROXY_SHIP`(CONTENT) / `AFTERSALE_PROXY_PROCESS`(FUNDS) /
  `PRODUCT_PROXY_OFF_SHELF`(CONTENT)，留痕 `detail` 里带工单号；**被拒的尝试也留痕**（`outcome=FAILED`）。
  ★ 新增动作码 = 新增一个"接收方（`duoduo-user`）也要认识"的契约，**只重建发送方不够**。
- **遗留**：`PUT /api/seller/products/{spuId}`（编辑商品）目前仍允许平台运营修改第三方店铺的商品，
  与上下架的收窄口径不一致，已登记为遗留项（影响面与回滚方式不同，单独评估）。
  ★ 校正：**并没有** `DELETE /api/seller/products/{spuId}` 这个端点 —— 商品只能上下架（`POST /{spuId}/status`，
  只接受 `ON_SALE` / `OFF_SHELF`）与编辑，不存在删除路径。此处此前写成「改/删」，是照着当时的
  `删除` 一起写的、并未核对控制器；`SellerProductController` 里没有 `@DeleteMapping`，
  `SellerProductService` 也没有删除方法。**登记遗留项时同样要核到端点上，不能凭印象写。**

**E24 租户健康度口径（一个面板横跨三个存储，四维各自要有独立证据）**

- **四维与来源**：订单量 / 成交额 → 交易库 `t_order`（`created_at` 的 UTC 自然日窗口）；
  售后率 → 履约库 `t_after_sale`（走**服务间内部接口** `/api/aftersales/internal/tenant-stats`，不经网关）；
  结算异常 → 交易库 `t_settlement` / `t_seller_debt` / `t_order` 三类**存量**异常（不带窗口，挂账是存量）；
  接口错误率 → Redis `duoduo:api:stat:{yyyy-MM-dd}`（网关对**带店铺身份的请求**逐日计数）。
- **为什么合成一个请求**：拆成四个接口会出现「四个数字来自四个时刻」，以及
  「其中一格加载失败但页面看起来完好」—— 后者是这一整类面板的通病。
- **★ 三态纪律**：比例字段分母为 0 时给 `null`（前端渲染「—」）而**不是** `0.00%`。
  「这段时间一单没成交」与「成交 100 单、零售后」是两个相反结论，写成同一个 `0.00%` 会把前者伪装成健康。
  同理档位有第四态 `NO_DATA`：窗口内既无成交也无接口调用 —— **没有可判断的数据**，不等于正常。
- **★ 样本门槛（有意的"不给档位"）**：结算异常 > 0，一笔就判 `RISK`（资金事实错位没有"轻微"一说）；
  但售后率要「≥ 20% **且** 成交 ≥ 5 单」、5xx 率要「≥ 1% **且** 调用 ≥ 10 次」才谈得上风险 / 关注。
  4 单里出 4 笔售后（100%）不会被标红 —— 绝对量太小，任何比例都没有统计意义；
  而「1 次失败就变红」的面板会在几天内被运维彻底无视，那比漏报更糟。
  所以面板**同时展示原始计数**，规则与门槛由 `/limits` 下发、页面如实渲染（前端不抄阈值）。
- **4xx 不参与判级**：401/404/400 大多是**预期的**（令牌过期、看别人的单、参数写错），
  5xx 才是无歧义的平台故障。4xx 仍照常展示 —— 它是排查商家端集成问题的线索。
- **降级方向是刻意的**：售后 / Redis 两路取不到 → **硬失败 503**（它们是判据，缺了面板就会说谎）；
  店铺名取不到 → 显式降级（响应 `nameResolved=false`，只影响展示）。**两处都不静默。**
- **★ 跨模块硬约束**：`TenantHealthJudgement.MAX_DAYS(30) ≤ TenantApiMetrics.KEEP_DAYS(40)`。
  否则超期那几天的 Redis 键已过期，`HGETALL` 的空 Map 与「那天没调用」**完全一样**，
  错误率会安静地变小。编译器管不到（两个常量在不同模块），只能靠单测守。
- **网关计数只认「带店铺身份的请求」**：`TenantApiMetricsFilter` 的 order 是 `-90`，
  必须**晚于** `JwtAuthGlobalFilter`（`-100`）—— 晚于它才读得到网关改写后的可信 `tid`，
  早于它读到的是调用方自己编的头（那就是一个新的越权面）。买家 / 运营 / 匿名请求一次都不计。
- **展示排序**：档位（风险优先）→ 下单数降序 → 租户 id 升序。第三键不是装饰：
  没有它，同档位同单数的两家店在两次请求间可能换序，被 `limit` 截断的永远是随机一批。

购物车条目的失效原因码（机器可读，前端只管展示文案）：
`SKU_NOT_FOUND` / `OFF_SHELF` / `OUT_OF_STOCK` / `STOCK_NOT_ENOUGH`。
**失效条目不计入结算金额** —— 否则结算页金额与订单金额会对不上。

## 平台管理后台（`/api/admin/**`，M10）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/reports/overview` | 运营报表：GMV / 总订单 / 已付款 / 退款率 / 转化率（全平台口径，SQL 聚合） | 运营 |
| GET | `/api/admin/settlements/summary` | 资金对账：托管中 / 已放款 / 已退回三组金额与笔数、佣金 | 运营 |
| GET | `/api/admin/settlements/wallets?tenantId=` | **E22 · 指定租户的钱包**（与商家端 `/api/seller/settlements/wallet` 同一段计算，逐分一致） | `finance:settlement`（仅 ADMIN） |
| GET | `/api/admin/settlements/bills?tenantId=&period=` | **E22 · 指定租户的结算账单**（租户 × 周期，与商家端同一段计算） | `finance:settlement`（仅 ADMIN） |
| POST | `/api/admin/settlements/compensate` | **平台垫付退款**（已完成订单退款：金额按买家实付记卖家欠款，托管单 `RELEASED→ADVANCED`，订单进 `REFUNDED`；**不回补库存**；`uk_seller_debt_order` 保证一笔订单只垫付一次，重复调用 409） | 运营 |
| GET | `/api/admin/settlements/debts` | 卖家欠款列表（可按 `status=OUTSTANDING/SETTLED/WRITTEN_OFF` 过滤） | 运营 |
| POST | `/api/admin/settlements/debts/{id}/settle` · `/write-off` | 欠款收回 / 核销（CAS 条件更新，二次调用 409） | 运营 |
| GET | `/api/admin/settlements/abnormal` | 异常挂账：已付款却缺托管单的订单（`LEFT JOIN` 排查） | 运营 |
| GET | `/api/admin/tenant-health?days=&limit=` | **E24 · 租户健康度面板**：一次请求返回**四维**（订单量/成交额、售后率、结算异常、接口错误率）—— 这四路分别来自**交易库、履约库、Redis**三个存储，拆成四个接口会得到「四个数字来自四个时刻」。窗口 `days` 默认 30 / 上限 30，行数 `limit` 默认 50 / 上限 200，非法值 **400**（不静默回落）。比例字段是 `String` 且**分母为 0 时给 `null`**（前端渲染「—」，不写 `0.00%`）；每行带 `level` 四态 `RISK`/`WATCH`/`NO_DATA`/`OK`（`NO_DATA` = 无样本，**必须与 `OK` 分开**）。`nameResolved=false` 表示店铺名这一路显式降级（只影响展示，四维判据仍有效） | 运营 |
| GET | `/api/admin/tenant-health/limits` | **E24 · 运行时口径**：窗口上限 / 默认值 / 行数上限 / 三类结算异常来源 / 判级阈值与**样本门槛** / 档位清单 / 接口计数保留天数。前端**不抄一份阈值**，判级规则从它渲染 —— 抄一份意味着政策调整后页面仍在按旧数解释数据 | 运营 |
| POST | `/api/admin/settlements/backfill?dryRun=` | **历史订单托管单回补**（A1 / I-14）：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`、佣金按当前 `PLATFORM_FEE_BPS` 重算并回传口径说明；`?dryRun=true` **只出计划、一行都不写库**；不带参数即执行；幂等靠 `uk_settle_order`（重复执行不产生重复单）；**不触碰已有托管单的订单**，也不给 `PENDING_PAYMENT`/`CLOSED` 补账（钱从未收过） | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/categories`、`/{id}`、`/{id}/status` | 类目增改删 + 启停用；**后台列表含停用项**（可重新启用），公开接口仍只暴露 ACTIVE | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/brands`、`/{id}`、`/{id}/status` | 品牌同上 | 运营 |
| GET | `/api/admin/listings` | 闲置审核工作台列表（含待审 / 已驳回，按 `auditStatus` 过滤） | 运营 |
| POST | `/api/admin/listings/{id}/audit` | 审核：`APPROVE` / `REJECT`（驳回必须填原因）；`audit_reason` / `audit_at` 落库留痕 | 运营 |
| GET | `/api/admin/orders`、`/{orderNo}` | 全平台订单列表（状态 / 订单号前缀过滤）与详情（无归属限制） | 运营 |
| POST | `/api/admin/orders/{orderNo}/force-close` | **强制关单**（仅待付款）：复用状态机合法流转 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR | 运营 |
| GET | `/api/admin/orders/proxy-work-orders` | **E23 · 平台待办（交易域工单）**：可按 `tenantId` 或 `targetRef`(orderNo) 筛选 | 运营 / 管理员（进门） |
| POST | `/api/admin/orders/{orderNo}/proxy-ship` | **E23 · 代发货**：`{workOrderNo, reason, logisticsCompany, logisticsNo}`；**持本店有效工单**才能替第三方发货；自营订单 **422**、他人订单 **403** | `tenant:proxy-ops` |
| POST | `/api/admin/orders/{orderNo}/proxy-aftersale` | **E23 · 代处理售后**：`{workOrderNo, reason}`；转调 `refundInternal`（与内部凭据入口同一段状态机） | `tenant:proxy-ops` |
| GET | `/api/admin/users?role=&keyword=` | **运营账号列表**（A7）：分页 + 角色过滤 + 关键字（手机号/昵称模糊匹配）；**脱敏视图**——不下发实名姓名 / 身份证 / 完整手机号 | 运营/ADMIN |
| POST | `/api/admin/users/{id}/role` | **变更用户角色**（A7，**仅 ADMIN**）：角色白名单（USER/SELLER/OPERATOR/ADMIN）；**先判权后校验参数**（无权者传非法角色也是 403，不泄露白名单）；自己降权 **422**、最后一个 ADMIN 不可降 **422**（==**并发**下才可能触发，见下）、同角色幂等 200 不留审计；每次变更写 `t_audit_log`；生效语义见 notice —— E17 起为「**下一个请求即生效（无需重新登录）**」，仅 Redis 不可用退回吊销令牌时才说「重新登录后生效」 | ADMIN |
| GET | `/api/admin/audit-sla/samples?status=&page=&pageSize=` | **E28 事后抽查队列**（商品域）：`status` 取 `PENDING`（默认，待复核）/ `PASSED` / `REJECTED`，不传=全部；**待复核按建单时间升序**（先来先做，24h 响应时长的守门就是它），已复核按复核时间倒序；视图冗余带出 `spuTitle` 与 `sellerId`，队列里一眼看出"该看哪件" | 运营 |
| POST | `/api/admin/audit-sla/samples/{id}/review` | **复核一条抽查任务**：`{action:'PASS'\|'REJECT', note}`。**PASS 只记结论、不动任何业务数据**（抽中 ≠ 下架）；**REJECT 是真处置** —— 商品驳回（`REJECTED`）+ 强制下架（`OFF_SHELF`）+ 来源记 `SAMPLE`，再上报商家违规计数（跨域，失败不回滚止血动作），最后落结论；`note` 判违规**必填**；**已复核再判 → 422**（否则误判率可被反复点通过刷平） | 运营 |
| GET | `/api/admin/audit-sla/metrics` | **SLA 自证指标**（窗口 30 天）：当前抽样比例 / 是否已自动收紧 / 已复核数 / 抽中违规数 / 抽中违规率 / 平均复核时长 / 误判率 / 待复核数。**读指标会顺带评估一次自动收紧**（连续 3 天抽中违规率 **> 10%** 才翻倍，恰好 10% 不算；封顶 40%） | 运营 |
| POST | `/api/admin/audit-sla/sweep` | **手动触发一轮抽查**：抽的是**昨天**那批自动放行商品（与定时器同口径，避免"点两次看到两个结果"）；**不看开关、不抢选主锁**；返回 `candidates/created/skipped/ratePercent/reasons`，幂等靠 `uk_biz_date`（重复点 `created=0`） | 运营 |
| GET/POST | `/api/admin/merchants/audit-sla/samples`、`/samples/{id}/review`、`/metrics`、`/sweep` | **入驻侧同链路**（店铺维度，路径前缀 `/api/admin/merchants`）：判违规走 `suspendByAudit` —— **资质造假是主体资格问题，直接停业、不攒次数**，停业用条件更新（`WHERE status='APPROVED'`，抢不到就不发通知不写留痕）；停业后**联动下架该店全部商品** | 运营 |
| GET | `/api/admin/audit-logs?actorId=&action=&category=&outcome=&targetId=&tenantId=&from=&to=&page=&pageSize=` | **E18 操作审计检索**：按操作人 / 动作标识 / 类目 / 结果 / 目标 / 租户 / 时间范围过滤，时间参数**两端都含**、起止颠倒或格式非法 **400**。返回 `immutable` 布尔值（**由服务端下发，前端不自行判断**）。**只有读，没有写/改/删** —— 审计表在代码层就没有 UPDATE / DELETE 路径（静态断言逐字扫描全仓） | 运营 |
| GET | `/api/admin/recon/runs?limit=` | **S3 对账轮次列表**（钱 / 库存两个范围各自一轮）：`{bizDate, scope, status, checkedCount, diffCount, highCount, startedAt, finishedAt, message}`。`status` 三态 **`CLEAN` / `DIRTY` / `FAILED`** —— `FAILED` 表示**没跑成**（依赖服务读不到 / 校验对象数为 0），**它与 `CLEAN` 的差别就是「报表一片绿但事实未知」与「真的没问题」的差别** | 运营 |
| GET | `/api/admin/recon/runs/{runId}/diffs?limit=` | **某一轮的差异明细**：按严重度（HIGH 优先）+ 规则 + 目标排序，带 `expected` / `actual` / `detail`（人能看懂的一句话）。**轮次不存在 → 404**（返回空列表会让「这一轮没有差异」与「这一轮不存在」混在一起） | 运营 |
| GET | `/api/admin/recon/rules` | **规则清单（源码声明 vs 运行时同一份）**：13 条（钱 8 / 库存 5），每条带 `scope` / `severity` / `refType`。冒烟脚本拿它和**源码正则数出来的条数**比对 —— 防「写了没上」 | 运营 |
| POST | `/api/admin/recon/runs` | **手动触发一轮对账**（唯一写端点）：`{bizDate?, scope?}`，默认今天 + 两个范围都跑。**同日重复触发是覆盖而非累积**（先删当天该轮次的明细再重算，靠 `uk_recon_run_day_scope` 兜底），返回两个范围里**最严重**的那个结论。动作码 `RECON_RUN_MANUAL` —— 触发本身不改业务数据，但它**会覆盖当天已存的结论**，所以必须留痕（否则"昨天的差异怎么没了"只能靠猜） | 运营 |

**★ S3 对账口径（每日自动对账 + 提醒）**

- **两个范围独立成败**：`MONEY` 与 `STOCK` 各落一条 `t_recon_run`。合并成一个范围的话，
  一次商品服务抖动会让整轮 `FAILED`，**连带把一个本来完全正确的钱对账结论也变成"未知"**。
- **对账 ≠ 把同一个函数算两次**：每条规则必须比**两个不同来源**（订单表 vs 托管表、交易域冻结台账 vs 商品域 `frozen_stock`）。
  拿同一个计算函数调两遍是恒等式，不是检查。唯一例外是 `MONEY_FEE_ILLEGAL`，它**故意不比费率**。
- **★ 钱是「政策的函数」，不是「当前费率的函数」**：拿库里存的历史 `platform_fee` 去比**今天的**费率，
  本地库实测产生 **92 条费率不符 + 375 条"自营被抽佣"** —— 全部是假的（那些单结算时费率还是 1%，
  或早于 E22 的自营免佣规则）。所以每日对账只做**结构性非法**检查（费率为负 / 佣金大于金额 / 金额为负），
  **费率类比较归回归测试**（`settlement-smoke`），不归每日对账。
- **`FAILED` 是一等状态**：取数抛异常、依赖服务不可用、`checkedCount == 0` 一律落 `FAILED`。
  `syncStatusFromCounts` 带 `AND status <> 'FAILED'`，**`FAILED` 永不被重新标成 `CLEAN`**。
  `checked_count == 0` 必须判 `FAILED` 的理由与铁律 2 同族：0 个对象必然「没有差异」，
  于是"什么都没看"与"看了且没问题"在报表上完全一样。
  **验证方式**：`docker stop duoduo-app-product` → STOCK 必须 `FAILED` 且带失败原因，同时 MONEY 不受影响。
- **同日重跑覆盖而非累积**：先 `deleteByRunOfDay` 再 `deleteByDayAndScope`，靠唯一键而不是应用层"查后写"。
- **计数必须回表读**：插入明细后重新 `COUNT(*)` 回写 —— `uk_recon_diff` 可能去重，
  否则报表说 5 条、点开只有 4 条。
- **跨域两向覆盖**：交易域只能送去"它知道有 FROZEN 的 SKU"，那个方向**永远发现不了**「交易域漏记冻结」。
  所以商品域新增反查 `GET /api/products/internal/skus/frozen-holders`，候选集 = 两个来源的**并集**。
- **严格读 vs 宽松读**：`ProductGateway` 原有的宽松读把 4xx 折成空 Map（购物车场景是对的：
  "这个商品不存在"是业务事实）；对账新增 `loadSnapshotsStrict` / `callStrict`，
  **任何**异常都抛 503 DEGRADED —— 对账里"读不到"≠"确实没有"，把前者当后者会把一次调用失败变成 N 条假差异。
- **暴露原始两列而不是 `availableStock`**：出参 `availableStock = max(0, stock - frozenStock)` 会把
  `frozenStock > stock` 这个异常**夹成 0**，前端只显示"售罄" —— 异常被一个看起来很合理的降级掩盖掉了。
- **提醒边界（刻意的）**：本轮不外接告警通道，只有 WARN 日志（带最多 10 条明细）+ 后台页 + 严重度计数。
- **★ 网关路由必须显式加 `/api/admin/recon`**：这个坑已经栽过 5 次（A11 / E14 / E18 / A3 / S3），
  现已固化为静态检查 `scripts/lib/check-gateway-routes.ps1`（250 条服务端路径逐条要求被网关覆盖，接 CI 第一步）。

**权限模型**：统一 `OperatorGuard.requireOperator()`（OPERATOR / ADMIN，忽略大小写）；
网关层 `/api/admin/**` 不进白名单（未登录 401），服务层校验角色（非运营 403）——两层各挡一种流量。
已付款 / 已发货订单**不开**后台干预口子，一律走退款 / 售后的合法流转。

## 履约域：评价与晒单（`duoduo-fulfilment` `/api/reviews`，M11/P2-1）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/reviews/product/{spuId}` | 商品评价分页（`rating` 星级过滤 / `hasImage` 只看带图） | **免登录** |
| GET | `/api/reviews/product/{spuId}/summary` | 评分汇总：平均分 / 星级分布 / 好评率 / 带图数 / 追评数（SQL 聚合） | **免登录** |
| POST | `/api/reviews` | 提交评价：`{orderNo, anonymous, items:[{spuId, skuId?, rating, content?, images?}]}`，一次可提交订单里多件 | 需登录 |
| POST | `/api/reviews/{id}/append` | 追评（每条评价一次，受追评窗口限制） | 仅本人 |
| GET | `/api/reviews/mine` | 我的评价（分页） | 需登录 |
| GET | `/api/reviews/order/{orderNo}` | 某订单下我的评价（详情页判断「还能补评哪几件」） | 需登录 |
| GET | `/api/reviews/reviewed?orderNos=a,b` | 我在这批订单里已评价过的订单号（订单列表页**一次**判断） | 需登录 |

**业务规则（写死在服务层，前端不重复实现）**：

- **只有本人已完成的订单能评价**：订单事实来自交易域内部接口 `GET /api/orders/internal/{orderNo}/fact`，
  返回**不含收货人姓名/电话/地址**（服务间最小必要字段）；请求体里没有任何 `userId` 可供伪造。
- **评的是订单里的商品**：`spuId` 必须出现在该订单明细中，否则 422 —— 挡住「拿 A 单给 B 商品刷好评」。
- **一单一商品一评**：唯一索引 `(order_id, user_id, spu_id)` 兜底，并发撞键转 **409**（不是 500）。
  刻意不是「一单一评」：一个订单常含多件商品，强迫写一条会把对 A 的好评与对 B 的差评挤进同一段文字。
- **图片只收 http/https 直链**（最多 9 张）：放开 `javascript:` / `data:` 等于把 XSS 入口交给任何能发评价的人。
- **匿名只影响展示**：昵称替换为「匿名用户」、头像置空，但真实 `user_id` 始终保留（信用分与风控需要真实主体）。

> 网关白名单**只放行只读的 `/api/reviews/product/**`**：未登录访客能看口碑（购物决策需要），
> 但提交 / 追评 / 我的评价一律强制登录。白名单按路径匹配，因此**不要把需登录的接口挪进 `product/` 子路径**。
>
> 跨域只读依赖两处（都是内部凭据保护，且都裁剪了字段）：交易域订单事实、
> 用户域 `GET /api/users/internal/briefs?ids=`（批量昵称头像，**只回 id/昵称/头像三个字段**）。

## 履约域：售后仲裁工单（`duoduo-fulfilment` `/api/aftersales`，A3）

在此之前，退款只有「卖家一键退」一种形态：买家想退款只能先私下沟通，卖家不同意就在系统里**无路可走**，
平台手里也没有任何可裁定的凭据（诉求是什么、何时提的、卖家怎么答的）。售后单把争议变成一条可查、可留痕、可裁定的记录。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/aftersales` | **买家立案**：`{orderNo, type, reason, images?}`。仅本人订单、仅「已发货 / 已完成」 | 需登录 |
| GET | `/api/aftersales/mine` | 我提起的售后（`status` 过滤 + 分页） | 需登录 |
| GET | `/api/aftersales/seller` | 我店铺的售后（可见范围由登录态推导，**不接受 `sellerId` 参数**） | 卖家 / 运营 |
| GET | `/api/aftersales/{id}` | 售后单详情（当事人或运营） | 需登录 |
| POST | `/api/aftersales/{id}/reply` | **卖家处理**：`{action: AGREE\|REJECT, reply?}`；`AGREE` **即执行退款**，`REJECT` 必填说明 | 该单卖家 |
| POST | `/api/aftersales/{id}/escalate` | **买家申请平台仲裁**（`PENDING` 或 `SELLER_REJECTED` 时可进入） | 该单买家 |
| GET | `/api/admin/aftersales` | 运营：全平台售后列表（`status` 过滤 + 分页） | 运营 |
| POST | `/api/admin/aftersales/{id}/arbitrate` | 运营裁定：`{result: APPROVE_REFUND\|REJECT, note}`，**`note` 必填**；`APPROVE_REFUND` 会真的执行退款 | 运营 |

**图片上传（A15，`duoduo-fulfilment` `/api/uploads`）**：`POST /api/uploads`（multipart `file`，需登录）→ `{url}` 绝对直链（评价晒单图 / 售后举证图共用）；`GET /uploads/{filename}` 公开读（UUID 文件名，正则防穿越）。白名单只放 `/uploads/**`，**上传必须登录**（白名单按路径不区分方法，别把上传也放进去）。

**状态机**：`PENDING`（待卖家处理）→ 卖家 `AGREE` 执行退款 / `REJECT` → `SELLER_REJECTED`；
买家可从 `PENDING` 或 `SELLER_REJECTED` `escalate` → `ARBITRATING`；运营裁定 → `REFUNDED` 或 `CLOSED`（两者都是终态）。
**不设「已驳回再改判」的边** —— 要改判只能新立案，这样每条售后单的结论永远只有一次，留痕不会自相矛盾。

几条刻意的规则：

- **钱不在这张表里**。裁定通过时调交易域**既有的**运营侧退款入口
  （`POST /api/orders/internal/{orderNo}/refund`），退款金额由那边回填 ——
  退款的状态机、托管 CAS、退货入库补偿在交易域只有一份实现，这里再写一条「售后专用退款」等于给自己造第二个事实来源。
- **顺序是先让钱动、再改售后单状态**。反过来会得到「售后单显示已退款、钱其实没退」这类最难查的账目分歧。
- **已完成订单（钱已放款给卖家）的裁定退款会被拒 422**，并把交易域的原话（需走追偿 / 平台垫付）透传给运营。
  这是项目已知边界 B-01，不是缺陷 —— 买家诉求该被受理，所以**立案**放行；但系统退不了这笔钱，只能走人工追偿。
- **状态迁移一律 CAS 条件更新**（把「当前必须是 X」写进 WHERE）：卖家点「同意」与运营点「裁定」同时到达时，
  数据库只让一个生效；钱那一侧的托管 CAS 会挡住第二次扣款。
- **唯一键是 `(order_id, type)`**，不是 `order_id` —— 同一订单「仅退款」被拒后，仍可另立「退货退款」。

自测记录：`scripts/aftersale-smoke.ps1` **101 项断言全绿**，覆盖 07 篇 A3 的六条验收标准
（非当事人 403 / 未发货 422 / 重复立案 409 / 裁定后订单与托管同步 REFUNDED / 缺裁定说明 422 / 全流程可查有留痕），
外加卖家拒绝→买家升级→运营裁定的完整闭环、卖家「同意即退款」这条独立资金路径、
同单不同类型可各自立案、越权读详情 403、卖家与运营列表准入与状态过滤，以及**已完成订单裁定退款被拒**的边界。
回归 `review-smoke.ps1` 59/0、`settlement-smoke.ps1` 125/0。

## 售后三端页面（`frontend/src/pages/`，A3 配套）

| 路由 / 位置 | 角色 | 内容 |
|---|---|---|
| `/after-sales/new?orderNo=…` | 买家 | 申请售后：类型（仅退款 / 退货退款，hint 写清两者区别）、诉求、举证图直链 |
| `/after-sales/:id` | 买家 | 详情：状态步骤条、卖家说明、裁定说明、退款金额；`PENDING`/`SELLER_REJECTED` 时可**申请平台仲裁** |
| `/after-sales` | 买家 | 我的售后（状态页签过滤 + 分页） |
| 订单列表 / 「我的」页 | 买家 | 「申请售后」只在**已发货 / 已完成**的订单上出现；未发货时买家本就能自助退款，走售后反而绕远 |
| `/seller/after-sales` | 卖家 | 店铺售后列表 + 处理弹窗：同意**立刻退款** / 拒绝**必填原因** |
| `/admin/after-sales` | 运营 | 全平台售后列表 + 裁定弹窗：支持退款（真的执行）/ 驳回，**说明必填** |

**关键实现决策**：

- **售后单 `id` 对外以字符串传输**。它是 18 位雪花值，远超 JS 安全整数上限（`9007199254740991`），
  `JSON.parse` 会静默改写成另一个数 —— 拿它拼 URL 必然打到不存在的单子。
  后端 `AfterSaleView.id` 类型是 `String`，前端从路由参数原样取用、**不做任何数值转换**。
- **弹窗默认结论是「驳回」而不是「支持退款」**：误点一下不会直接把钱退出去。
- **三端共用一份状态文案与配色**（`pages/afterSaleShared.tsx`）：各写一份必然漂移，
  而「同一状态在两个页面显示成两种说法」在用户眼里就是系统坏了。文案本身仍以服务端下发的 `statusLabel` 为准。
- **步骤条不按状态一一对应**：`SELLER_REJECTED` 是「卖家已处理完」而不是独立一阶段，硬映射会画出一条现实中不存在的路径。

自测记录：`scripts/e2e/aftersale-ui-check.mjs` **44 项断言全绿**（截图 `frontend/docs/screenshots/as_*.png`），
用真实 Chromium 走完整一条链：买家下单 → 申请售后 → 卖家拒绝（先验证空原因被拦）→ 买家申请平台仲裁
→ 运营裁定（先验证空说明被拦）→ 支持退款并执行 → 回到接口层确认**订单与托管同步 REFUNDED、退款金额等于实付**。

> ⚠️ 踩坑：前端路由是 `after-sales`（连字符），接口路径是 `/api/aftersales`（无连字符），两者**刻意不同**。
> 卖家端与运营端的入口链接曾写成 `/seller/aftersales`，命不中路由直接落到「页面不存在」——
> 这类问题 `tsc` 与 `vite build` 都发现不了，只有真实浏览器会现形。

## 履约域：物流轨迹（`duoduo-fulfilment` `/api/logistics`，A2）

| 端点 | 准入 | 说明 |
|---|---|---|
| `POST /api/logistics/internal/{orderNo}/tracks` | `X-Internal-Token` | 批量摄入轨迹节点（物流推送 / 交易域发货通知）；**直连服务，不经网关** |
| `GET /api/logistics/orders/{orderNo}/tracks` | 买家本人 / 该单卖家 / 运营 | 按发生时间正序返回；无轨迹回**空列表**（正常状态，不是 404） |

**节点字典 `LogisticsNode`（白名单，推送方只能选用不能自创）**：
`PICKED_UP` 已揽收 · `IN_TRANSIT` 运输中 · `ARRIVED_HUB` 到达分拨中心 ·
`OUT_FOR_DELIVERY` 派送中 · `SIGNED` 已签收 · `REJECTED` 已拒收 · `EXCEPTION` 物流异常。
中文文案由**服务端**下发（`nodeLabel`），前端不维护第二份映射 —— 两份映射迟早漂移。

**关键实现决策**：

- **幂等靠唯一索引 `uk_track_order_node_time`（订单号 + 节点码 + 发生时间）**，
  撞键即「跳过」，回执里 `inserted` / `skipped` 分开计数 —— 物流推送的常态就是失败重推 / 补推 /
  定时对齐，只回一个「成功」的话，重推与漏推在监控上看不出区别。
- **批量全有或全无（先全部校验、再逐条写）**：批次里混一条非法节点就整批 400 且一条都不落。
  若边校验边写，调用方无从知道前面几条落了没有，只能整体重推 —— 而重推又会被判成重复。
- **时间存「发生时间」而不是入库时间**，无时区写法**默认按 UTC 解释**（不是服务器本地时区）：
  按本地时区解释会让同一份报文在开发机（东八区）与容器（UTC）落成相差 8 小时的两条节点，
  唯一索引随之兜不住重复。
- **交易域发货后补推「已揽收」**：`AFTER_COMMIT` 事件 + best-effort —— 通知失败只记日志、
  绝不让发货失败；且**必须**在事务提交之后（否则履约域可能查到一个尚未提交的订单，
  反把节点写入判成「订单不存在」）。`occurredAt` 用**发货时间**而不是通知时刻，
  这样重推永远撞同一个唯一键。
- **轨迹是对真实运输过程的记录，不从属于订单状态机**：刻意不校验订单状态 ——
  订单可能因退款变 `REFUNDED` 而包裹仍在路上，按状态拦会把真实后续节点全部丢掉。
- **前端买家 / 卖家共用同一个时间线组件**（`components/LogisticsTimeline.tsx`），
  同一包裹不允许两边说法不一致；`shipped` 判定**同时看状态与 `shippedAt`**，
  否则买家一确认收货轨迹就整块消失，而签收记录恰恰是纠纷时最需要回看的。
- 路径**不挂在 `/api/orders/**` 下**：那一段整段路由给交易域，硬挂进去就得靠网关里的
  路由优先级特例来撑 —— 正是历史上造成过「新增端点只 404」的写法。

自测记录：`scripts/logistics-smoke.ps1` **79 项断言全绿**（含★断言总数守卫：任何一条 Check
因写法错误被静默跳过都会让守卫报失败），`scripts/e2e/logistics-ui-check.mjs` **44 项真实
Chromium 断言全绿**（截图 `frontend/docs/screenshots/lg_*.png`）。

## 营销域：优惠券（`duoduo-marketing` `/api/coupons`，A5）

第 6 个业务服务 `duoduo-marketing`（营销域 / 优惠券，:8085）。**领券中心是公开落地页**：
未登录也能看有什么券，点「立即领取」时才引导登录。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/coupons/templates` | **领券中心**：券模板列表 + `claimable` / `claimableReason`（能否领由**服务端**判定） | **免登录** |
| POST | `/api/coupons/templates/{id}/claim` | **领券**：重复领取不报错，返回 `replay=true` 与已有券码 | 需登录 |
| GET | `/api/coupons/mine?status=` | 我的券包（`status` 可选 `UNUSED` / `LOCKED` / `USED` / `EXPIRED`，不传为全部） | 需登录 |
| GET | `/api/coupons/mine/usable?amount=` | 结算页可用券：传商品总额，服务端返回每张券可用性与 `bestCouponCode` 最优券 | 需登录 |
| POST | `/api/coupons/admin/templates` | 建券模板（`OperatorGuard.requireOperator()`） | 运营 |

**内部接口**（`X-Internal-Token` 保护，**不进网关**，交易域直连 8085）：
`POST /api/coupons/internal/{lock,release,consume,sweep}` —— 依次对应「下单锁券 / 取消·关单解锁 / 支付核销 / 补偿扫描」；
`POST /api/coupons/internal/trial` —— **只读试算**（第五十二轮新增，供结算预览用，见下）。

**关键设计（为什么这样做）**：

- **券码用随机串而不用雪花主键**：`CP` + 14 位去混淆字符（字符表去掉 `0 / O / 1 / I`）——
  规避 JS `Number.MAX_SAFE_INTEGER` 精度坑（与既有 `AfterSaleView.id` 改 `String` 是同一个坑，项目内编号 INV-16）。
- **状态机** `UNUSED → LOCKED → USED`（`USED` 为终态）；`LOCKED` 可 release 回 `UNUSED`；`EXPIRED` 为终态。
- **锁券 / 核销 / 解锁全部走 CAS 条件更新**（判断写在 SQL 的 WHERE 里），**不用分布式锁**（INV-1）。
- **资金纪律**：营销域只算「能抵多少」，**折扣金额由交易域写入 `t_order.discount_amount`**；
  营销域是被调用方，**不依赖 openfeign**。
- **交易域接入点**：下单 `placeOrder` 先锁券（失败抛 422 带人话原因，如「满 200 元可用」）→ `paySuccess` 核销
  （核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁。
- **★ 试算与占券必须分开（第五十二轮）**：`lock` 是跨服务**写**（CAS 真占券），`trial` 是纯读。
  结算预览**只能**调 `trial` —— 否则用户每改一次数量就被占一次券，最后没提交还得干等 TTL 解锁。
  两者共用同一份 `precheck()`（存在 / 归属 / 状态 / 过期 / 未锁 / 门槛 / 抵扣），
  保证「预览说能用」与「下单真能用」结论一致；`trial` 用伪订单号 `__TRIAL__` 且**不落任何状态**。
- **两种券类型（2026-10-26 · E-05 折扣券）**：`FULL_REDUCE`（满减，固定 `discount_amount`）与
  `DISCOUNT`（折扣，`discount_percent` = 折后应付百分比，85 即 8.5 折；`max_discount_amount` 封顶，0 = 不封顶）。
  抵扣口径统一为 `CouponService#calculateDiscount`：满减 `min(固定额, 订单金额)`；折扣
  `floor(订单金额 × (100 − discount_percent) / 100)` 后按封顶裁剪，**一律向下取整**（多减 1 分放大后就是资金缺口）。
  折后比例缺失或越界按「不打折」处理 —— 宁可少减，也不按没人设过的比例减钱。
- **一单一券（不允许叠加）**：下单请求体只有单个 `couponCode`，多券不合并、不叠加；
  券包 / 结算页的「本单可抵」由**服务端按本单金额算**（不是回模板固定额），
  最优券也按「本单实际抵扣额」排序，避免「页面抵 5 元、实际减 1 元」。
- **折扣券必须有封顶**：没有 `max_discount_amount` 的 8 折券落在上万元订单上会一次减掉几千元，
  运营在没有任何上限参数时根本不敢发折扣券。
- **网关白名单收紧为精确路径 `/api/coupons/templates`**：白名单按**路径**而非方法匹配，
  放行 `/api/coupons/**` 会把 `POST /api/coupons/templates/{id}/claim` 也放成匿名可调（与 `/api/reviews/product/**` 是同一条纪律）。
- **`COUPON_LOCK_TTL_MINUTES` 默认 35**：必须**略大于**交易域订单超时关单 30 分钟，
  避免「订单还活着、券已被别人领走」。

自测记录：`scripts/coupon-smoke.ps1` **145 项断言全绿**（含★断言总数守卫）——覆盖
领券幂等 / 并发防超发 / 锁·解·核销 CAS / 折扣落交易域 / 生命周期闭环，另加公开只读边界、内部凭据 403、
并发锁券、归属校验、门槛文案、券码非雪花、sweep 幂等、TTL 环境变量校验、运营建券准入、清理复原。
折扣券另跑 `scripts/discount-coupon-smoke.ps1` **64 项断言**（建券参数边界 / 金额口径与封顶 / 向下取整 /
一单一券不叠加 / 小额订单拒绝 / 下单落账与核销 / 清理复原）。
前端 `/coupons`（领券中心 + 我的券包，**刻意不套 `RequireAuth`**）+ 结算页选券 + 订单详情展示优惠金额与券码；
折扣券面额显示为「8.5 折（最高减 ¥20）」而不是金额。

## 履约域：分享圈（`duoduo-fulfilment` `/api/shares`，R43 起 / R56 补全）

分享圈是「带用户评价的商品口碑内容」，与商品评价同性质 —— **未登录也要能逛**，
所以公开读与需登录写的分界线是这一块的核心设计，且**靠路径形状区分**
（网关白名单按路径匹配、**不区分方法**，见 `GatewaySecurityProperties` 里的逐条注解）。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/shares/feed?scope=` | 信息流；`scope=RECOMMEND`（默认）/ `FOLLOWING`（只看我关注的人，需登录） | **免登录**（RECOMMEND） |
| GET | `/api/shares/topics?limit=` | 热门话题榜（发布页「大家都在聊」） | **免登录** |
| GET | `/api/shares/topics/{name}` | 话题聚合页（该话题下的分享） | **免登录** |
| GET | `/api/shares/comments?shareId=&parentId=` | 评论列表；`parentId` 省略 = 一级评论（带回复数），传值 = 该评论的回复 | **免登录** |
| GET | `/api/shares/{id}` | 分享详情（分享落地页数据源） | **免登录** |
| GET | `/api/shares/mine/list` | 我的分享（编辑 / 删除入口的数据源） | 需登录 |
| POST | `/api/shares` | 发布（`spuId` + `content` + 最多 2 个 `topics`） | 需登录 |
| POST | `/api/shares/{id}/repost` | 转发到分享圈（落一条以**我**为作者的新分享） | 需登录 |
| POST | `/api/shares/{id}/edit` | 编辑自己的分享（**只改文字与话题**） | 需登录 |
| POST | `/api/shares/{id}/delete` | 删除自己的分享（**软删**） | 需登录 |
| POST | `/api/shares/{id}/like` · `/{id}/favorite` | 点赞 / 收藏 toggle | 需登录 |
| POST | `/api/shares/{id}/report` | 举报分享 | 需登录 |
| POST | `/api/shares/{id}/comments` | 发表评论 / 回复（`parentCommentId` 省略 = 一级评论） | 需登录 |
| POST | `/api/shares/comments/{commentId}/like` | 评论点赞 toggle | 需登录 |
| POST | `/api/shares/comments/{commentId}/report` | 举报评论 | 需登录 |

**运营（平台管理后台，落在履约域）**：

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/share-reports?status=` | 举报列表（带被举报内容摘要 `targetExcerpt`） | 运营 / 管理员 |
| POST | `/api/admin/share-reports/{id}/handle` | 处理：`ACCEPT`（成立 → 下架内容）/ `REJECT`，**说明必填** | 运营 / 管理员 |

**为什么公开读的这几条都是一段路径、而所有写路径都在两段以上**：这是刻意的。
白名单里放的是 `/api/shares/feed`、`/api/shares/topics`、`/api/shares/topics/*`、`/api/shares/*`
四条；`AntPathMatcher` 的 `*` **不跨 `/`**，所以 `/{id}/edit`、`/mine/list`、`/{id}/comments`、
`/comments/{id}/like` 这些写路径**永远落不进白名单**。
反面例子（也是本块最需要提醒后来者的地方）：若把评论列表做成 `GET /{id}/comments`（两段），
要放行它就得加 `/api/shares/*/comments` —— 那会**连带把 `POST /{id}/comments`（发表评论）
放成匿名可调**。命名在这里不是风格问题，是安全边界。

**内部接口**（`X-Internal-Token`，不经网关）：`POST /api/socials/internal/notices`
（评论 / 回复通知，**落在社交域**，见上节）；社交域的 `GET /api/socials/internal/following`
供本域关注流取「我关注的人」id 列表。

**其它要点**：`deleted_at` 软删（转发单的引用块要靠回查原帖才能显示，物理删会变空白）；
`edited_at` 与 `updated_at` 分开（后者会被点赞计数等非内容变更刷新，拿它当「已编辑」判据会误报）；
话题名 `name` 唯一（并发建同名话题靠唯一键 + 回查，而不是「先查再插」）；
举报唯一键 `(target_type, target_id, reporter_id)` 兜住并发重复提交。

---

## 社交域：通知中心与合并未读（`duoduo-social`，R50 上线 / R55 补生产者）

通知中心页面 R50 就已上线，但**此前没有任何业务生产者**（全库只有 3 条种子公告），
页面能打开、永远没有新通知。R55 把它补成完整链路（细节见顶部 R55 段）。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/socials/notices?page=&pageSize=&category=&read=&keyword=` | 通知列表（定向通知 + 全站公告合并视图；`category` 可选 `SYSTEM` / `ANNOUNCEMENT` / `FRIEND` / `MERCHANT` / `ORDER`；`read` = `UNREAD`/`READ` 与 `keyword` 为 2026-10-27 新增，**均在服务端筛**） | 需登录 |
| GET | `/api/socials/notices/unread-count` | 通知未读数（**已排除私信通知行**） | 需登录 |
| GET | `/api/socials/notices/unread-summary` | **合并未读** `{notice, message, total}` —— 角标的**唯一数据源**（小程序 tabBar 角标与**网页商城顶栏角标**都读它，两端同口径） | 需登录 |
| POST | `/api/socials/notices/{noticeId}/read` | 标记单条已读（幂等；他人 / 不存在 404，不区分以防枚举） | 需登录 |
| POST | `/api/socials/notices/read-all` | 全部已读（幂等；2026-10-27 修：此前只取最近 50 条，超过时角标清不干净） | 需登录 |
| POST | `/api/socials/notices/batch` | **批量操作** `{action: READ\|DELETE, ids[], all}` → `{affected, unread}`（2026-10-27 新增；`all=true` = 全部；删除写 `t_social_notice_deleted` 集合，公告共享行不物理删） | 需登录 |
| GET | `/api/orders/status-counts` | **买家订单各状态计数** `[{status, count}]`（2026-10-27；订单页二级 Tab 角标，服务端 GROUP BY 全量） | 需登录 |
| GET | `/api/aftersales/status-counts?side=buyer\|seller` | **售后各状态计数**（2026-10-27；口径与 `mine`/`seller` 同源） | 需登录 |
| GET | `/api/seller/products/audit-counts` | **商家商品审核态计数**（2026-10-27；与商品列表同归属 + 回收站不计） | 卖家 / 运营 |
| GET | `/api/seller/orders/status-counts` | **卖家订单各状态未读计数**（2026-10-27；已发货/已完成/已关闭/已退款「看过即减」，发货动作本身写已读 → 自己发货不会给已发货 +1） | 卖家 / 运营 |
| POST | `/api/orders/{orderNo}/read`、`/api/aftersales/{id}/read?side=`、`/api/seller/products/{spuId}/read`、`/api/seller/orders/{orderNo}/read` | **阅后即消上报**（幂等，`INSERT IGNORE` 已读集合；返回最新分桶，前端必须用它覆盖本地乐观值） | 需登录 / 卖家 |
| GET | `/api/admin/reviews/unread-count` | **运营 · 未读待审评价数**（2026-10-27；`PENDING` 排除本人已查看） | 运营 / 管理员 |
| POST | `/api/admin/reviews/{id}/read` | 点开评价详情 = 已读（幂等；返回最新未读数） | 运营 / 管理员 |
| GET | `/api/admin/share-reports/unread-count` | 举报处理台未读待处理数（同上口径） | 运营 / 管理员 |
| POST | `/api/admin/share-reports/{id}/read` | 点开举报详情 = 已读（幂等） | 运营 / 管理员 |
| GET | `/api/admin/aftersales/unread-count` | 售后仲裁未读待处理数（同上口径） | 运营 / 管理员 |
| POST | `/api/admin/aftersales/{id}/read` | 点开售后详情 = 已读（幂等） | 运营 / 管理员 |
| GET | `/api/socials/messages/unread-count` | 私信未读数（**按条计**，对齐主流 App 的「3 条未读」语义） | 需登录 |
| POST | `/api/socials/messages/{messageId}/recall` | **撤回私信**（2026-10-24）：仅发送者本人 + 2 分钟窗口 + 幂等；软删留痕、库内内容覆写 | 需登录 |
| POST | `/api/support/messages/{messageId}/recall` | **撤回客服消息**（2026-10-24，同上口径）；对端经 WS `RECALL` 信号实时感知 | 需登录 |
| POST | `/api/seller/support/sessions/{id}/messages/{messageId}/recall` | 商家侧撤回（同口径，仅本会话） | 商家 |

**内部接口**（`X-Internal-Token` 保护，**只写不读、不进网关**，trade / fulfilment 直连 8087）：
`POST /api/socials/internal/notices`（批量推送，返回本次落库条数 `accepted`）、
`POST /api/socials/internal/notices/announce`（全站公告；收件人由服务端固定为 `0`，契约里干脆不给这个字段）。

**关键设计（为什么这样做）**：

- **两阶段落库**：先把整批逐条校验（分类白名单 / 标题正文长度 / `jump_page` 必须落在
  `MiniProgramPages` 的 28 个真实页面内）再统一 insert —— 第 N 条非法则**整批不写**，
  失败就是「什么都没发生」，不会出现半批生效。
- **★ 合并口径落服务端，前端只显示**：`total = 通知（不含私信） + 私信未读`。私信到达会额外写一条
  `bizType=MESSAGE` 的通知行作为「有人找你」的入口，该行**必须从通知未读里排除** —— 否则一条消息会被
  数两次（通知 + 私信），角标凭空翻倍。**注意** `biz_type <> 'MESSAGE'` 对 `biz_type IS NULL` 返回 NULL
  （即不匹配），而**全站公告的 `biz_type` 恰好是 NULL**，故必须写 `biz_type IS NULL OR biz_type <> 'MESSAGE'`。
- **同会话连发合并为一条未读**：窗口内同 `bizType + bizId` 的未读行**原地 update**（id 不变、不新增行）；
  用户**读完后**对方再发会**新插一条**（保证永远有未读、能再次提醒）。合并的边界是「未读」而非「永久一条」。
- **推送一律 best-effort**：trade / fulfilment 侧只 `log.warn`，**异常绝不外抛** —— 消息 / 订单此时已落库，
  为附属通知把主流程判成失败，会让用户看到与事实相反的提示而去重发。
- **`AFTER_COMMIT` 才推**：交易域四处通知都在事务提交后发布 —— 事务没提交完就推，用户点进订单页会**查不到订单**。
- **签收只认「本次真写入」**：物流节点按**首次 insert 成功**才推，被唯一索引挡掉的重推**不重复推**。

小程序侧：`utils/unread.js`（**角标唯一出口**，60s 轮询 + 并发单飞 + 失败返回 `-1` 不清角标 + `>99 → '99+'`）、
`utils/notice.js`（跳转与弹窗冷却）、`components/notice-toast`（顶部滑入卡片，4s 自动收起）。
`scripts/check-miniprogram.js` **第 8 条规则**（error 级）全仓禁止绕过 `utils/unread.js` 直接调 `setTabBarBadge`。
自测：`scripts/r55-notice-smoke.py`（42 项）+ `scripts/r55-unread-badge-test.js`（59 项）。

网页商城侧（**2026-10-12 对齐，2026-10-27 收口**）：`packages/shared/src/store/app.tsx`（未读状态 + 实现）+
`apps/mall/src/components/Layout.tsx`（**顶栏铃铛角标**，按登录态启停轮询；**头像不再挂角标** ——
铃铛是消息的唯一入口，头像上的数字既点不进页面也会让人误以为有两个未读来源）。
同样读 `unread-summary` 的 `total`，刷新点三处（打开菜单 / 回前台 / 60s 轮询）、请求单飞、**失败不清零**。
e2e：`scripts/e2e/notice-chat-follow-ui-check.mjs`（**50 项**，④ 段专测角标）；自检步骤见 `docs/SELFTEST.md` §4.10c。
