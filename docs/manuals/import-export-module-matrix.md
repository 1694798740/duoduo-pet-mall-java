# 全站导入 / 导出模块矩阵（2026-10-27）

> 目标：把“哪些模块值得做导入导出、字段是什么、用同步还是异步、权限怎么判”一次说清，
> 后续按矩阵逐项落地，不再每做一个页面重新讨论口径。

## 0. 结论先行

| 类型 | 模块 | 现状 | 本轮动作 |
|---|---|---|---|
| 已有导入导出 | 商家商品（CSV 16 列） | ✅ A-17 | **✅ 2026-10-28 补 xlsx 模板与 xlsx 导入**（CSV 仍为主格式，两种格式同解析路径） |
| 高价值导入 | 员工管理 | ✅ 2026-10-28 | 模板下载 → 前端解析 → 服务端逐行邀请 + 逐条结果 |
| 高价值导出 | 订单、售后、结算/对账、评价、举报、入驻 | ✅ 2026-10-28 | 按当前筛选流式 CSV（BOM + 公式注入防护） |
| 中价值导出 | 商品审核、内容审核、变更历史、操作审计、运营账号、平台成员、秒杀、多多币流水 | ✅ 2026-10-28（第二批） | 与各列表同筛选的 CSV |
| 中价值导入 | 类目/品牌主数据、商品价格库存 | ✅ 价格库存、类目/品牌均 2026-10-28 | 均已完成：模板 + 逐行结果；类目/品牌额外带 dry-run |
| 异步导出 | 超过 5 万行的订单导出 | ✅ 2026-10-28（第 9 项） | 任务化：创建 → 后台分页写盘 → 站内通知 → 7 天内下载 |
| 低价值/不做 | 入驻申请导入、秒杀活动导入、运营账号导入 | — | 只做导出；导入风险高，暂不做 |
| 买家侧 | 我的订单、多多币流水 | ✅ 2026-10-28 | 只导出自己的数据（脱敏）：手机号 / 地址掩码、同步上限 5 万行 |

### 0.1 本轮已落地接口（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| trade | `GET /api/seller/orders/export` | 商家订单，按 status/keyword |
| trade | `GET /api/seller/settlements/export` | 商家结算台账（时间正序、金额为元） |
| trade | `GET /api/admin/orders/export` | 全平台订单，含完整收货信息 |
| trade | `POST /api/admin/seckill/activities/batch-status` | 秒杀批量上下架（≤100，逐条结果） |
| fulfilment | `GET /api/aftersales/seller/export` | 店铺售后 |
| fulfilment | `GET /api/admin/aftersales/export` | 全平台售后 |
| fulfilment | `GET /api/admin/reviews/export` | 评价（status/rating/keyword） |
| fulfilment | `GET /api/admin/share-reports/export` | 举报（status/keyword） |
| user | `GET /api/seller/staff/export` | 员工列表（手机号掩码） |
| user | `GET /api/seller/staff/import-template` | 两列模板：手机号、权限（分号分隔） |
| user | `POST /api/seller/staff/import` | 逐行邀请（≤200 行，返回 rowNo → 结果） |
| user | `POST /api/seller/staff/batch-enable` / `batch-disable` | 员工批量启停（≤100） |
| user | `GET /api/admin/merchants/export` | 入驻审核台（不含证件材料） |
| user | `POST /api/admin/merchants/batch-approve` / `batch-reject` | 入驻批量通过 / 驳回（≤100） |

### 0.2 第二批导出接口（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| product | `GET /api/admin/products/export` | 商品审核台，按 auditStatus / sellerId / keyword |
| product | `GET /api/admin/products/change-history/export` | 商品变更历史，按 spuId / keyword / event / sellerId |
| product | `GET /api/admin/listings/export` | 闲置内容审核，按 auditStatus / keyword |
| user | `GET /api/admin/audit-logs/export` | 操作审计，与检索同参数（时间 / 动作 / 类目 / 结果） |
| user | `GET /api/admin/users/export` | 运营账号（手机号掩码，不提供导入） |
| user | `GET /api/admin/merchant-staff/export` | 平台成员，按 keyword |
| user | `GET /api/admin/coins/ledger/export` | 多多币流水，按 userId / direction |
| trade | `GET /api/admin/seckill/activities/export` | 秒杀活动：一行一个 SKU（活动字段重复） |

导出统一：`text/csv; charset=UTF-8`、UTF-8 BOM、RFC 4180 转义、`=+-@` 开头加单引号防公式注入、
北京时间、金额单位元；同步上限 5 万行，超出应转异步任务。导出为只读动作，不写审计日志。

### 0.3 第三批 · 价格 / 库存导入（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| product | `GET /api/seller/products/price-stock-template` | 四列模板：`商品ID,规格ID,价格(元),库存`（只有表头，无示例行） |
| product | `POST /api/seller/products/price-stock-import` | `{content}`，≤500 行 / 2MB；逐行调 `updateSkuPriceStock`，返回 `{total,successCount,failedCount,items:[{id=行号,success,errorCode,message}]}` |

口径：
- **逐行调用单 SKU 改价入口**，不另写 UPDATE —— 归属、`PRODUCT_PRICE` 权限、价格上限、库存非负、
  运营代改第三方商品工单门禁、`min_price/max_price` 重算全部复用同一条写路径，避免「页面改不了、表格能改进去」。
- 只改价 / 库存，**不触发内容重审、不升版本**；价格单位为元、最多两位小数。
- 价格与库存**至少填一项**，留空 = 不改该项；空行/空文件/表头不符/超限 → 整体 4xx，其余部分成功不回滚。
- 店员需 `PRODUCT_PRICE`，解析文件之前先判权限（否则 500 行会逐行跑成「全部失败」）。

### 0.4 第三批 · 类目 / 品牌导入（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| product | `GET /api/admin/categories/import-template` | 四列模板：`名称,父类目,排序号,图标`（只有表头） |
| product | `POST /api/admin/categories/import/dry-run` | `{content}` → 逐行计划 `{total,createCount,updateCount,skipCount,failedCount,items:[{lineNo,action,name,targetId,message}]}`，**不落库** |
| product | `POST /api/admin/categories/import` | 正式导入，返回 `{total,successCount,failedCount,items:[{id=行号,success,errorCode,message}]}` |
| product | `GET /api/admin/brands/import-template` | 四列模板：`名称,国家/地区,Logo地址,品牌故事`（只有表头） |
| product | `POST /api/admin/brands/import/dry-run` | 同形状的逐行计划，不落库 |
| product | `POST /api/admin/brands/import` | 正式导入，逐行结果同形状 |

口径：
- **必须能先 dry-run**：类目/品牌是商品公共主数据，写错一次波及大量商品；预演返回 `CREATE / UPDATE / SKIP / ERROR`，
  正式导入复用同一套决策逻辑，所以「预演里有 ERROR 的行，正式导入一定失败」，不存在预演通过、导入才炸。
- **按业务键匹配，不要求填 ID**：类目按「父类目 + 名称」，品牌按「同名」；`父类目` 列支持 **ID 或顶级类目名称**，
  留空 / 0 = 顶级；只支持两级，本文件新建的父类目行必须写在子类目之前（预演会明确指出顺序问题）。
- **空单元格 = 不改**：更新时只覆盖填了值的列（排序号 / 图标 / 国家 / Logo / 故事），避免「留空把已有内容清掉」。
- 文件级问题（空文件 / 表头不符 / 只有表头 / 超 500 行 / 超 2MB）→ 整体 4xx；
  行级问题 → 该行失败、其余照常，HTTP 200，部分成功不回滚。
- 权限：三个类目接口与三个品牌接口均 `OperatorGuard.requireOperator()`；普通卖家 403。
  dry-run 不写审计；正式导入写 `CATEGORY_CREATE` / `BRAND_CREATE` 审计并记录成功/失败行数。

### 0.5 第三批 · 异步导出任务（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| trade | `POST /api/admin/export-jobs` | `{module:"ORDERS", status?, keyword?}` → 任务视图；立即返回，后台生成 |
| trade | `GET /api/admin/export-jobs` | 我的任务（仅创建人可见），分页 |
| trade | `GET /api/admin/export-jobs/{id}` | 任务状态（轮询；含进度、失败原因、有效期） |
| trade | `GET /api/admin/export-jobs/{id}/download` | 下载 CSV；仅创建人、仅成功且未过期 |
| trade | `GET /api/admin/orders/export` | 同步导出；**超过 5 万行返回 409**，不再静默截断 |

口径：
- **同步端点不再静默截断**：旧实现 `LIMIT 50000` 会生成一份「看起来完整、其实少行」的 CSV，
  财务按它算会少钱。现在先 count，超限明确 409 并提示改用异步任务；前端在点「导出 CSV」前
  也按列表总数拦一次（避免把 409 的 JSON 直接呈现在页面上）。
- **异步任务与同步导出共用查询与列定义**：`OrderService#adminExportWrapper` +
  `OrderCsv`。所以两边行数与列顺序不会漂移。创建时 count 出的 `total_rows` 是进度分母，
  生成结束的 `row_count` 必须与它相等（冒烟有断言，防游标漏页）。
- **分页用 keyset 游标，不用 `selectPage`**：交易域分页插件 `maxLimit=100`，
  传入 size=2000 时第 2 页 offset 会跳到 2000，中间 1900 行被静默跳过 ——
  这不是慢，是导出少数据。游标按 `(created_at, id)` 向下取，每页只扫索引。
- **状态只前进** `PENDING → RUNNING → SUCCESS / FAILED / EXPIRED`，迁移全部 CAS；
  服务重启后 30 分钟仍未推进的任务由调度判 FAILED（带可读原因），不会永远「生成中」。
- **文件保留 7 天**，由调度删文件 + 置 EXPIRED；30 天前的任务行清理。
  文件落本地卷 `trade-exports`，**没有公开路径**，唯一入口是鉴权下载端点。
- **下载不做一次性 token**：网络抖动导致下载失败时，一次性链接会逼用户重新生成几十万行的任务。
  改为「创建人 + 成功 + 未过期」即可重复下载，并记录 `download_count` /
  `last_downloaded_at`（事后能回答「这份文件被取过几次」）。
- **完成推站内通知**：`SYSTEM` 分类、`bizType=EXPORT_JOB_READY / EXPORT_JOB_FAILED`，
  `jumpPage` 传 null（运营后台是网页端，没有对应小程序页；非空会触发社交域页面白名单校验）。
  通知失败不影响任务结果（best-effort）。
- 权限：全部 `OperatorGuard.requireOperator()`；别人的任务按 **404**（不泄漏「该 id 存在」）。
  导出仍是只读动作，不写审计。

### 0.6 买家侧 · 我的订单 / 多多币流水（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| trade | `GET /api/orders/export` | 我的订单，按 status / orderType / keyword（与列表同筛选） |
| user | `GET /api/coins/ledger/export` | 我的多多币流水（时间倒序，含记账后余额快照） |

口径：
- **只能导自己的数据**：查询条件里写死 `user_id = 当前登录用户`，不接受任何 `userId` 参数；
  未登录 401。与列表共用同一个 wrapper（`mineWrapper`），导出不会比页面多看见一行。
- **脱敏是导出列的一部分，不是前端遮罩**：手机号保首 3 末 4（`138****1234`），
  地址只留前 6 个字符 + `****`；订单行用单独的 `BUYER_HEADERS`，
  与运营导出（完整收货信息）**不共用列定义**，避免以后改运营列时顺手把买家隐私带出去。
- 同步上限 5 万行：先 count，超限 400 并提示按状态 / 关键词分批导出（买家侧不做异步任务，
  个人数据量级用不上，且异步文件的生命周期与权限模型是给运营设计的）。
- 其余口径与全站 CSV 一致：UTF-8 BOM、RFC 4180、公式注入防护、北京时间、金额元、`no-store`。
- 前端：买家「我的订单」工具栏「导出 CSV」（携带当前筛选）、「我的多多币 · 流水」标题行「导出流水」。
  下载走 `location.href` 导航到 GET 地址，同源 Cookie 鉴权，不占页面内存。

### 0.7 xlsx 模板与导入（2026-10-28 · 第三批第 7 项）

| 模块 | xlsx 模板 | xlsx 导入 | 说明 |
|---|---|---|---|
| 商品管理（16 列） | ✅ `?format=xlsx` | ✅ | 表头 + 示例行 + 「说明」页；示例行与 CSV 模板同源 |
| 价格库存（4 列） | ✅ | ✅ | 只有表头 + 说明页；ID 列按文本写 |
| 类目 / 品牌（4 列） | ✅ | ✅（含 dry-run） | 只有表头 + 说明页；仍强制先预演 |

口径：
- **模板是二进制附件**，与 CSV 模板的 JSON 信封并存：`format=csv`（默认）仍返回
  `{ fileName, content }` 供前端拼 Blob；`format=xlsx` 直接写 `application/vnd...spreadsheetml.sheet`。
  两条映射用 `params = "format=xlsx"` 区分，**不改变已有 CSV 契约**（冒烟与老前端无需改动）。
- **导入两种格式走同一条解析路径**：前端把 CSV 读成文本、xlsx 读成 Base64（2MB → 2.7MB），
  服务端 `SpreadsheetRows` 统一转成「单元格行 + 物理行号」，再交给原有校验/分组/逐行结果。
  因此 xlsx 的报错文案、行号、dry-run 结论与 CSV 完全一致，不存在「xlsx 一套、CSV 一套」。
- **所有单元格按字符串写**：19 位雪花 ID / 价格若按数字写会被 Excel 转科学计数法或丢两位小数。
  模板的 ID 列是文本格式；用户仍把 ID 粘成数字时，报错会指向该行，而不是静默截断。
- 读文件：只读第一个工作表、只读不执行外部链接（公式取缓存值）；空行不导入但**保留 Excel 行号**，
  便于用户照着「第 5 行」定位；坏文件返回 400「无法解析 Excel 文件」，不会被当成 CSV 乱切。
- 上限不变：CSV / xlsx 同口径 ≤2MB（按解码后字节）、≤500 行；xlsx 层另有 5MB 防 zip 炸弹兜底。

### 0.8 第四批 · 剩余 P3 导出（2026-10-28）

| 域 | 接口 | 说明 |
|---|---|---|
| product | `GET /api/seller/products/price-stock/export` | 价格库存：商品ID/名称、规格ID/名称、价格、库存、商品状态、审核状态；筛选同商品列表 |
| fulfilment | `GET /api/reviews/seller/export` | 店铺评价：仅 APPROVED，匿名昵称服务端脱敏；筛选同评价列表 |
| trade | `GET /api/seller/product-stats/export` | 商品分析：成交件数/金额、成交订单数、退款件数/金额；按商品汇总 |
| social | `GET /api/seller/support/sessions/export` | 客服会话：只导会话元信息（买家/未读数/状态/处理人/最后消息时间），**不含聊天正文** |
| product | `GET /api/admin/categories/export` | 类目主数据：ID/名称/父类目/层级/状态/图标，含停用 |
| product | `GET /api/admin/brands/export` | 品牌主数据：ID/名称/国家/Logo/故事/状态，含停用 |
| trade | `GET /api/admin/tenant-health/export` | 租户健康：交易 / 售后 / 结算异常 / 接口错误率四维，按当前窗口最多 200 家 |

口径：
- 全部直接写 `text/csv; charset=UTF-8` 附件（BOM + RFC 4180 + 公式注入防护），前端 `window.location.href` 下载。
- 商家侧筛选与列表共用同一个 Wrapper；运营侧与面板同一次聚合，避免「页面看到的」和「导出的」不一致。
- 同步上限 5 万行；价格库存超过 5 万行、客服会话超过 5 万条时明确 409，不静默截断。
- 导出是只读动作，不写审计；权限边界沿用列表（商家登录态 / `OperatorGuard`），未登录 401。

## 1. 判定规则

一个模块值得做导入/导出的判据：

1. **有“逐条重复录入/抄表”成本** → 做导入（员工、商品、类目/品牌）。
2. **有“按月/按筛选汇总交财务或复盘”的需求** → 做导出（订单、售后、结算、评价、举报、审计）。
3. **数据含证件/图片/合同** → 只导出，不做 Excel 导入（入驻）。
4. **动作本身是审批/裁定** → 不做导入（审核结论必须逐条看）。
5. **隐私/资金** → 导出必须带角色脱敏与审计；导入必须服务端二次校验。

## 2. 模块矩阵

### A. 商家端

| 模块 | 导入 | 导出 | 字段 / 说明 | 优先级 |
|---|---|---|---|---|
| 商品管理 | ✅ 已有 CSV **+ xlsx（2026-10-28）** | ✅ 已有 CSV | 16 列分组模板；xlsx 模板多一个「说明」页，示例行与 CSV 同源 | P1 |
| 商品价格 / 库存 | ✅ 2026-10-28（CSV + xlsx） | ✅ 2026-10-28 | 导出：商品ID/名称、规格ID/名称、价格(元)/库存/状态；导入只改价库存不重审 | P2 |
| 订单管理 | — | ✅ 已有（2026-10-28 核对） | 订单号/状态/商品数/商品总额/运费/优惠/实付/买家/下单时间/发货时间；按当前筛选 | P1 |
| 售后管理 | — | ✅ 已有（2026-10-28 核对） | 售后单号/订单号/类型/状态/退款金额/申请时间/处理时间；按当前筛选 | P1 |
| 结算 / 对账 | — | ✅ 已有（2026-10-28 核对） | 账期/订单号/商品金额/佣金/补贴/应结/状态/放款时间；按账期导出 | P1 |
| 评价管理 | — | ✅ 2026-10-28 | 商品/评分/内容/昵称/回复/时间；仅 APPROVED，匿名脱敏 | P2 |
| 员工管理 | ✅ 已有（导入模板 + 逐行结果） | ✅ 已有（2026-10-28 核对） | 导入：手机号/姓名(可选)/权限键；导出：成员/角色/权限/状态/加入时间 | P1 |
| 商品分析 | — | ✅ 2026-10-28 | 商品/成交件数/成交额/成交订单数/退款件数/退款金额；按商品汇总 | P3 |
| 客服会话 | — | ✅ 2026-10-28 | 会话/买家/处理人/未读/状态/最后消息时间；**不含聊天正文**（隐私） | P3 |

### B. 运营后台

| 模块 | 导入 | 导出 | 字段 / 说明 | 优先级 |
|---|---|---|---|---|
| 订单干预 | — | ✅ 已有（2026-10-28 核对） | 全平台订单；运营可导出完整收货信息（审计） | P1 |
| 售后仲裁 | — | ✅ 已有（2026-10-28 核对） | 全平台售后 + 裁定结论/说明 | P1 |
| 评价管理 | — | ✅ 已有（2026-10-28 核对） | 全平台评价 + 审核态/来源/操作人 | P2 |
| 举报处理 | — | ✅ 已有（2026-10-28 核对） | 类型/摘要/理由/状态/处理人/说明 | P2 |
| 商品审核 | — | ✅ 已有（2026-10-28 核对） | 商品/店铺/审核态/版本/提交时间/驳回原因 | P2 |
| 内容审核（闲置） | — | ✅ 已有（2026-10-28 核对） | 标题/卖家/价格/城市/审核态/原因 | P2 |
| 商品变更历史 | — | ✅ 已有（2026-10-28 核对） | 商品/事件/操作人/版本/字段 diff/时间 | P2 |
| 操作审计日志 | — | ✅ 已有（2026-10-28 核对） | 动作/类目/操作人/结果/目标/租户/IP/链路/时间 | P2 |
| 入驻审核 | ⬜ 仅文本 | ✅ 已有（2026-10-28 核对） | 导出：公司/联系人/状态/提交时间；导入暂不做（证件必须逐份看） | P3 |
| 平台成员（店员） | — | ✅ 已有（2026-10-28 核对） | 店铺/成员/角色/状态/权限 | P2 |
| 运营账号 | — | ✅ 已有（2026-10-28 核对） | 手机号/昵称/角色/状态/2FA；导入不做（提权风险） | P3 |
| 秒杀活动 | — | ✅ 已有（2026-10-28 核对） | 活动/商品/价格/配额/时间/状态；导入不做 | P3 |
| 多多币 | — | ✅ 已有（2026-10-28 核对） | 流水/任务奖励/活动预算；导出按筛选 | P2 |
| 类目 / 品牌 | ✅ 2026-10-28 | ✅ 2026-10-28 | 导入：模板 + dry-run + 逐行结果；导出：主数据全量（含停用） | P2 |
| 租户健康 / 报表 | — | ✅ 2026-10-28 | 按当前窗口导出交易 / 售后 / 结算异常 / 接口错误率四维（最多 200 家） | P3 |

### C. 买家端 / 小程序

| 模块 | 导入 | 导出 | 说明 | 优先级 |
|---|---|---|---|---|
| 我的订单 | — | ✅ 2026-10-28 | 仅本人；手机号/地址脱敏；按当前筛选，上限 5 万行 | P3 |
| 我的多多币流水 | — | ✅ 2026-10-28 | 仅本人；方向/金额/余额/业务/时间；上限 5 万行 | P3 |
| 通知中心 | — | — | 不做（消息不是报表） | — |

> 状态说明（2026-10-28）：上表的 ✅ 已按**控制器实际端点**逐项核对（不是按计划推断）。
> 第四批已补齐全部剩余导出：商家「价格库存 / 评价 / 商品分析 / 客服会话」、
> 运营「类目 / 品牌 / 租户健康」；矩阵内不再有未实现的导出项。
> 只保留「通知中心」等明确不做（消息不是报表）的模块。

## 3. 技术落地顺序

**第一批（同步、CSV 优先）**
1. 员工导入/导出（模板 + 预览 + 逐行邀请/权限）
2. 商家订单导出、运营订单导出
3. 商家售后导出、运营售后导出
4. 商家结算/对账导出

**第二批（按筛选流式导出）**
5. 评价、举报、商品审核、内容审核、变更历史、操作审计
6. 运营账号/平台成员/秒杀/多多币流水

**第三批（xlsx 与异步）**
7. **商品 / 价格库存 / 类目 / 品牌 xlsx 模板与 xlsx 导入 ✅ 2026-10-28**、**价格库存导入 ✅ 2026-10-28**
8. **类目/品牌导入（dry-run + 逐行结果）✅ 2026-10-28**
9. 超过 5 万行走异步导出任务（生成后站内通知下载）**✅ 2026-10-28**
10. 买家侧「我的订单 / 我的多多币流水」脱敏导出 **✅ 2026-10-28**

## 4. 统一接口形状

```text
GET  /api/{scope}/{module}/export?{当前筛选}&format=csv|xlsx
POST /api/{scope}/{module}/import  { rows: [...], batchKey }
GET  /api/{scope}/{module}/import-template?format=csv|xlsx
```

返回：

- 导入：`{total, successCount, failedCount, items:[{rowNo,id,success,errorCode,message}]}`
- 导出：文件流；文件名 `{module}-{yyyyMMdd-HHmm}.csv`
- 异步：`POST /api/{scope}/export-jobs` → `{jobId}`；`GET /api/{scope}/export-jobs/{jobId}` 轮询；
  `GET /api/{scope}/export-jobs/{jobId}/download` 下载（已实现 `scope=admin`、`module=ORDERS`）

## 5. 权限与安全

- 卖家导出/导入：`requireSellerScope`，只能操作本店；店员需对应权限键。
- 运营：`OperatorGuard.requireOperator()`；导出全量收货信息写审计。
- 文件：只收 `.csv/.xlsx`，≤5MB，≤500 行同步；公式注入防护；UTF-8 BOM。
- 脱敏：买家手机号/地址按角色；运营全量需审计；聊天正文不导出。
- 异步文件：本地卷 7 天过期，过期由调度删文件；下载必须登录且仅创建人可取，
  不做一次性 token（下载失败要能重试），下载次数记在任务行上。
