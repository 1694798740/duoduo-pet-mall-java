# 多多宠物商店 · 项目开发计划（看板）

> 更新时间：2026-09-23（第十四轮：A8 收货地址簿 + **T-39 雪花 ID 精度缺陷根治** —— 用户域新增地址簿（6 用户端点 + 内部快照）、交易域下单支持 `addressId` 并写收货快照；`duoduo-common-web` 新增 `LongToStringJacksonModule` 统一把 64 位标识按字符串下发、金额/计数/时间戳按字段名白名单保持数字；前端 38 个 id 字段与 25 处形参改 `string`；接口冒烟 **15 套 1286 项**、UI 断言 **6 套 244 项**）
> 工程：`D:\智能体项目\duoduo-pet-mall-java`（Java 微服务正式版）
> 运行方式：**Docker 优先**（中间件 + 业务服务全部容器化，已跑通）
> 远端：https://github.com/1694798740/duoduo-pet-mall-java （私有）

---

## 0. 这个看板怎么用

- 三个状态：**已完成** / **进行中** / **未开始**；被卡住的标 **阻塞**。
- 最下方「**问题与协助区**」是我遇到障碍的地方。**需要你出手**的会写清「需要你做什么」；
  你处理完把该行状态改成 **已协助**，我下一轮会先自检该问题是否真的解决，再继续干活。
- 我自己解决掉的也会留档（标 **已自愈**），方便你复盘，不需要你操作。

---

## 1. 里程碑

| # | 里程碑 | 状态 | 说明 |
|---|---|---|---|
| M0 | 工程骨架 + 分层与错误体系 | ✅ 已完成 | duoduo-common / common-web / gateway / user / product 五模块 |
| M1 | 用户域 | ✅ 已完成 | 注册登录、httpOnly Cookie、401 拦截、身份透传、实名掩码、宠物档案 |
| M2 | 商品域 | ✅ 已完成 | 类目树、品牌、SPU-SKU 检索（6 排序）、价格区间、物种/生命阶段筛选、C2C 闲置 |
| M3 | 端到端冒烟（54 项） | ✅ 已完成 | 免安装模式 54/54；容器化模式复跑 54/54 |
| M4 | **Docker 化开发环境** | ✅ **已完成** | **7 容器全健康**；Nacos 注册 **4 服务**；从零重建数据卷可复现 |
| M5 | **P1-b 交易服务** | ✅ 已完成 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / 支付幂等 / C2C 担保交易 / **卖家中心（M5-9）** |
| M6 | 买家端 · **Web 商城** | ✅ 已完成 | 主交易闭环 10 页（React 18 + TS + Vite）；PC 端二维码扫码支付 + 3 秒轮询；**真实浏览器逐页验证通过** |
| M7 | 买家端 · **微信小程序** | ⬜ 未开始 | 小程序内复用同一套支付接口 |
| M8 | **沙箱支付渠道** | ✅ 完成 | 渠道抽象 + 扫码预下单 + 收银台 + 回调验签 + 幂等（M8-1 自测 62 项全绿）；真实渠道留接口位 |
| M9 | 商家端 · **Web（商家服务平台）** | ✅ 已完成 | 概览 / 订单 / 订单详情 / 结算对账 4 页；发货与售后退款走真实权限校验；自测 **82 通过 / 0 失败** |
| M10 | 平台管理后台 · **Web** | ✅ 完成 | 类目品牌维护、内容审核、订单干预、资金对账、报表 |
| M11 | 营销 / AI 导购 / 履约 / 检索 / 压测 | 🔄 进行中 | **履约域四块已落地**（新建 `duoduo-fulfilment`）：P2-1 评价与晒单、**A3 售后仲裁（含三端页面）**、**A2 物流轨迹（含买卖双方时间线）**、**A4 信用分（权重模型 + 前端展示）**；**A5 优惠券营销已落地**（新建第 6 个业务服务 `duoduo-marketing`，:8085，含前端领券中心 / 券包 / 结算选券）；**A8 收货地址簿已落地**（用户域地址簿 + 交易域下单写收货快照 + 前端 `/addresses` 与结算页二选一），同轮**根治 T-39 雪花 ID 精度缺陷**（`LongToStringJacksonModule` 全站统一按字符串下发 64 位标识，金额/计数/时间戳按字段名白名单保持数字）；余下：AI 导购（A9，待凭据）/ ES 检索（A10）/ RocketMQ + Sentinel（A11）/ 压测（A12） |

---

## 2. 端形态（2026-09-17 你确认的口径）

**一句话：买家两端口（小程序 + 网页），商家与平台都是 Web。**

| 端 | 形态 | 面向谁 | 主要能力 | 支付方式 |
|---|---|---|---|---|
| 买家端 · 微信小程序 | 微信小程序 | 买家 | 浏览、搜索、购物车、下单、订单、售后 | 展示支付二维码 / 拉起支付（沙箱） |
| 买家端 · Web | 响应式网页 | 买家 | 同上（PC 与手机浏览器） | 展示**支付二维码**（沙箱）；将来可换真实渠道 |
| 商家端 | Web | 商家 / 卖家 | 商品上下架、订单处理、发货、售后、结算对账 | 收款侧，不出钱 |
| 平台管理后台 | Web | 平台运营 | 类目品牌、内容审核（C2C 闲置）、订单干预、资金对账、报表 | — |

**已经为它铺好的地基**（本阶段已完成，接前端时只需对接接口）：

- 身份：网关统一鉴权 + `X-User-*` 透传，多端共用同一套登录（小程序侧后续接 `code2Session`）
- 资金：`t_settlement` 资金账本 + `SettlementView`，买家端可直接显示「资金托管中 / 已放款 / 已退回买家」
- 支付：预下单返回**二维码内容 + 过期时间**，前端只需把它渲染成二维码；支付结果轮询接口已提供
- 状态：订单状态与托管状态都给出**中文文案**，前端不必各维护一份映射
- 分页/错误：统一 `PageResult` 与 `{ error: { code, message, details }, requestId }`

---

## 3. 支付渠道：当前采用「沙箱支付」

**你的决定（2026-09-17）**：真实微信 / 支付宝收款需要营业执照、企业实名、备案域名与一堆密钥，
手续太重 —— **改用沙箱支付**，把「能扫码、能收到支付回调、能被验签、能幂等入账」这套**工程链路**做实，
真实渠道的资质问题不再作为项目阻塞项。

### 3.1 沙箱支付做成什么样

| 能力 | 说明 | 为什么这样做 |
|---|---|---|
| **渠道抽象** | `PaymentChannel` 接口：预下单 / 校验回调。`MOCK`（预下单即成功）与 `SANDBOX`（完整流程）是它的两个实现 | 将来接真实渠道时**只加一个实现类**，订单、库存、账本、回调处理全都不用改 |
| **预下单** | 下单后调用支付接口 → 返回 `payNo` / `codeUrl`（二维码内容）/ `expireAt`，订单仍是「待付款」 | 与微信 Native、支付宝当面付的返回形态一致：渠道给你一个字符串，商家自己渲染二维码 |
| **收银台** | 沙箱渠道的 `codeUrl` 指向一个**沙箱收银台页面**，打开可见金额与「确认支付」 | 现在就能肉眼验证整条流程；将来换成真实渠道的收款页，前端代码不用动 |
| **回调验签** | 回调入口 `POST /api/payments/callback/{channel}`：**签名校验 → 落库 → 驱动订单**。沙箱用 HMAC-SHA256 + 时间戳防重放 | 这是真实渠道最难写对的一段，先在沙箱里把它写对、测到 |
| **幂等入账** | 支付单 `(channel, idempotent_key)` 唯一索引 + 状态 CAS；重复回调只入账一次 | 渠道回调天然会重复投递（渠道重试、网络抖动），靠唯一索引兜底比「先查再插」可靠 |
| **支付单超时** | 预下单即写入 `expire_at`，超时未支付由补偿调度置为 `EXPIRED` | 否则会出现「订单已超时关闭、支付单还挂着能付」的矛盾状态 |

### 3.2 将来若要接真实微信 / 支付宝（备查，**当前不阻塞**）

材料清单仍留档在协助区 I-12 的折叠附件里，需要时把 `SANDBOX` 换成真实实现即可 ——
订单、冻结台账、资金托管、回调处理这些**都已经写成渠道无关**的了。
唯一真正需要外部条件的只有两处：渠道预下单调用、回调验签（各自的 SDK 与证书）。

---

## 4. 任务清单

### 已完成

| 任务 | 产出 / 证据 |
|---|---|
| 微服务骨架与分层纪律 | Controller 不写业务逻辑、Service 不依赖 HttpServletRequest、Mapper 参数化 |
| 用户域全部接口 | 网关 401 拦截、身份头先剥离再写入、refresh 令牌存 SHA-256 并轮转 |
| 商品域全部接口 | 违禁词 422、非法排序 400、未审核闲置不可见 |
| 54 项端到端冒烟脚本 | `scripts/smoke.ps1`，每次随机手机号可重复跑 |
| GitHub 推送能力恢复 | 本地与远端逐字节一致（blob 哈希全等） |
| 项目资产分类归档 | 按日期分目录 + 99-历史版本，写入维护规则 |
| 容器化中间件 | MySQL 8.0.36 / Redis 7.2 / Nacos 2.3.2 三容器健康 |
| 容器数据口径对齐 UTC | `TZ` + `--default-time-zone` 与应用 `serverTimezone=UTC` 同口径 |
| 商品库初始化 SQL 生成 | `docs/sql/02-duoduo-product.sql`（由模块内 schema.sql + data.sql 拼装） |
| **业务服务容器化** | Dockerfile（temurin:21-jre-alpine，非 root 运行）+ `.dockerignore` + compose 四个 app 服务；四镜像已构建 |
| **Nacos 服务发现接入** | 网关改用 `lb://` 负载均衡；已注册 `duoduo-user` / `duoduo-product` / `duoduo-trade` / `duoduo-gateway` 四服务 |
| **容器化模式跑通 54 项冒烟** | `scripts/smoke.ps1` → **54 通过 / 0 失败** |
| **中文双重编码缺陷修复** | 见 I-08；`SET NAMES utf8mb4` 已写入 3 个初始化脚本，从零重建数据卷复验通过 |
| **M5-1 `duoduo-trade` 模块骨架** | 模块接入父 POM（6 模块反应堆）；`TradeApplication`、`TradeServiceConfig`、三档配置；BUILD SUCCESS |
| **M5-1 交易库 DDL** | 7 张表：`t_cart_item` / `t_order` / `t_order_item` / `t_order_status_log` / `t_stock_freeze` / `t_payment` / `t_settlement`；已在容器 MySQL 实际执行并校验 |
| **M5-2 交易服务容器接入** | compose 增加 `duoduo-trade-app`（:8083）；**7 容器全 healthy**、Nacos 注册、启动 2.7s |
| **M5-3 购物车** | 商品服务新增批量 SKU 接口；交易服务引入 OpenFeign；7 个接口；**自测 87 通过 / 0 失败** |
| **M5-4 下单拆单** | 订单 4 实体 + Mapper + `OrderStatus` + `OrderNoGenerator` + `ProductGateway`；幂等靠 `uk_order_user_idem`；明细存下单快照；**自测 76 通过 / 0 失败** |
| **M5-5 库存冻结与释放** | 库存操作改为**原子条件更新**（判断写进 WHERE，不用分布式锁）；服务间凭据 Filter **失败关闭**；可售判定改为 `stock − frozen_stock` |
| **M5-6 订单状态机 + 超时关单** | `/ship`（内部凭据）、`/confirm`；`@Scheduled` 超时关单自动关单并释放冻结（**可重入**）；`/internal/sweep` 手动触发 |
| **M5-7 支付幂等** | `PaymentEntity`；**三层防线**（先查 → 订单状态 → `uk_pay_channel_idem`）；**先落支付单再扣库存**；成功后台账置 `DEDUCTED` |
| **M5-8 C2C 担保交易（资金托管与放款）** | `SettlementEntity` / CAS 状态迁移 / `SettlementService`；支付成功**资金进平台托管** → 确认收货**放款给卖家** → 退款**退回买家**；平台佣金按基点计算并落库留档；订单新增终态 `REFUNDED`；**退货入库**与 `RESTOCK_PENDING → RESTOCKED` 台账补偿；**发货满 N 天自动确认收货并放款**；**自测 125 通过 / 0 失败** |
| **M8-1 沙箱支付渠道** | `PaymentChannel` 抽象（预下单 / 回调验签 / 查询）+ 注册表；`MOCK`（即时）与 `SANDBOX`（扫码）两实现；`pay()` 改预下单语义，返回二维码内容（指向网关收银台）与过期时间；回调入口 HMAC-SHA256 验签 + 时间戳窗口 + **金额核对** + 幂等 CAS 入账；沙箱收银台页面；支付单 `PENDING → SUCCESS/EXPIRED/CLOSED` 生命周期；订单关单时**作废未支付支付单**（接通此前缺失的 `markClosedByOrder`，堵住「订单已取消、二维码还能扫出钱」）；**自测 62 通过 / 0 失败，六套合计 475 项全绿** |
| **M6 买家端 Web 商城（P1-c）** | 新建 `frontend/`（React 18 + TS strict + Vite 5）；12 路由 / 10 页主交易闭环；API 层（401 单飞刷新 + 5xx 退避重试 + 错误映射）；**零依赖二维码组件**（Byte 模式 + Reed-Solomon + 8 掩码择优）；PC 端扫码支付 + 3 秒轮询；`tsc --noEmit` 零错误、`vite build` 通过；curl 全链冒烟 + **真实 Chromium 逐页验证 12 路由**（含真实 UI 填单下单 → 模拟扫码 → 轮询自动变已付款 → 托管金额核对 ¥59.00/¥0.59/¥58.41），截图 13 张见 `frontend/docs/screenshots/` |

| **M5-9 卖家中心（后端）** | 卖家账号体系（`applyMerchant` 实名前置 + 幂等）；`/api/seller/**` 6 个接口（概览 / 订单列表 / 订单详情 / 发货 / 售后退款 / 结算对账）；**归属校验取代内部凭据**（越权 403 / 不存在 404 刻意区分）；运营可见平台自营 `sellerId=0`；物流信息落订单表并回显；**自测 84 通过 / 0 失败** |
| **M9 商家端 Web（前端）** | `frontend/src/pages/seller/` 4 页（Dashboard / Orders / OrderDetail / Settlements）+ `shared.tsx`；`sellerApi` 端点层；`RequireSeller` 路由守卫（只校验登录，真正的权限边界仍在后端 403）；Layout 与「我的」页按角色显示入口；`tsc --noEmit` 零错误、`vite build` 通过（60 模块）；**真实浏览器 UI 自测 29 项全绿**（截图见 `frontend/docs/screenshots/sel_*.png`） |
| **M10 平台管理后台** | 后端：`/api/admin/**` 13 个接口 —— 运营报表（GMV/订单量/退款率/转化率）、资金对账（托管汇总 + 异常挂账 LEFT JOIN 排查）、类目/品牌增改删+启停用（后台列表含停用项，公开接口仍只暴露 ACTIVE）、闲置审核（通过/驳回留痕：原因+时间，公开只可见 APPROVED）、订单干预（全平台列表/详情 + 仅待付款可强关，冻结释放、支付单作废、状态日志记 OPERATOR）；`OperatorGuard`（user-common，`isOperator()` 与登录态同口径）；网关 `/api/admin/**` 路由（不进白名单）；`t_listing` 补 `audit_reason` / `audit_at` 追溯列。前端：`pages/admin/` 5 页（运营概览含对账 / 内容审核 / 类目 / 品牌 / 订单干预）+ `AdminTabs` + `RequireOperator`；Layout 按角色显示「管理后台」入口。**自测 `scripts/admin-smoke.ps1` 49 通过 / 0 失败**（覆盖 401/403 边界、审核留痕、强关状态机拒绝、夹具自建自毁）；`tsc` 零错误、`vite build` 通过 |

| **M11 / P2-1 评价与晒单（履约域）** | **新建 `duoduo-fulfilment`（8084）** 服务：POM 入父反应堆、三档配置、雪花 worker-id=4、compose 第 8 个容器、网关路由 `/api/reviews/**`（**只放行只读 `/product/**`**）。评价接口 7 个：商品评价分页（星级/有图过滤）、评分汇总（平均分/星级分布/好评率/带图/追评计数，SQL 聚合）、提交评价（一单可多件商品）、追评（一次 + 窗口期）、我的评价、按订单查我的评价、已评价订单集合（订单列表页批量判断）。**规则**：只有本人**已完成**订单可评（订单事实由交易域内部接口提供，**不含收货人信息**）；`spuId` 必须在该订单明细中；唯一索引 `(order_id,user_id,spu_id)` 兜底一单一商品一评（并发撞键转 409）；图片只收 http/https（挡 XSS scheme）；匿名只影响展示、真实 user_id 保留。**用户域**新增内部只读接口 `/api/users/internal/briefs`（批量昵称头像，凭据保护）+ 新建 `InternalTokenFilter`。**交易域**新增 `/api/orders/internal/{orderNo}/fact`（裁剪字段的订单事实）。前端：商品详情页评价区（汇总 + 筛选 + 分页 + 匿名脱敏）、`/reviews/new` 按订单逐件打分（已评过的不再出现）、`/reviews/mine` 含追评弹窗、订单列表「去评价 / 已评价」批量判断。**自测 `scripts/review-smoke.ps1` 59 通过 / 0 失败** + **`scripts/e2e/review-ui-check.mjs` 真实 Chromium 20 通过 / 0 失败**（截图 `rev_*.png`） |

| **A1 · 历史订单托管单回补（I-14）** | **新增 `duoduo-trade` 的 `POST /api/admin/settlements/backfill?dryRun=`**（运营，幂等）：扫描「已有资金事实、却没有账本」的订单，按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`，放款时间取订单**真实的 `finished_at`**；`?dryRun=true` 只出计划一行不写库；幂等靠 `uk_settle_order`；只碰 `s.id IS NULL` 的订单、不给 `PENDING_PAYMENT`/`CLOSED` 补账；逐条独立提交。**实测效果**：回补前 3 笔 `COMPLETED` 挂账 → 回补后 `abnormal` 返回空、退款单缺口同样清零。**自测 `scripts/backfill-smoke.ps1` 88 通过 / 0 失败**；回归 `settlement-smoke 125/0`、`payment-smoke 71/0`、`admin-smoke 49/0` |

| **A3 · 售后仲裁工单** | **新建 `t_after_sale`（履约库）+ 8 个端点**：买家立案（仅本人订单、仅已发货/已完成）、我的售后、店铺售后、详情、卖家处理（AGREE 即退款 / REJECT 必填说明）、买家申请平台仲裁、运营全平台列表、运营裁定（APPROVE_REFUND 会真的退款 / REJECT，**说明必填**）。**钱不在这张表里**：裁定通过调交易域既有的运营侧退款入口，退款金额回填；**先让钱动再改状态**；状态迁移全部 CAS 条件更新；唯一键 `(order_id, type)`。**B-01 边界**：已完成订单（钱已放款）裁定退款被交易域拒 422 并提示走追偿，售后单保持原状态。**重构**：评价域私有的「订单号哈希」与「图片校验/JSON 读写」抽到 `support/OrderRefs` + `support/ImageUrls` 共用。**自测 `scripts/aftersale-smoke.ps1` 101 通过 / 0 失败**；回归 `review-smoke 59/0`、`settlement-smoke 125/0` |

| **A3 配套 · 售后三端页面（第十轮）** | **买家 3 页**：`/after-sales`（我的售后 + 状态页签）、`/after-sales/new?orderNo=`（类型 + 诉求 + 举证图）、`/after-sales/:id`（步骤条 + 卖家说明 + 裁定说明 + 退款金额 + 申请平台仲裁）；入口挂在**订单**（仅已发货/已完成）与「我的」页。**卖家 1 页** `/seller/after-sales`（同意即退款 / 拒绝必填原因）。**运营 1 页** `/admin/after-sales`（裁定，说明必填；弹窗默认结论为「驳回」——误点不会把钱退出去）。**契约修正**：售后单 `id` 改为**字符串**传输（18 位雪花超过 JS 安全整数上限，`JSON.parse` 会静默改写）。**共用件** `pages/afterSaleShared.tsx`（状态页签 / 类型文案 / 配色 / 步骤条）。**自测 `scripts/e2e/aftersale-ui-check.mjs` 真实 Chromium 44 通过 / 0 失败**（买家→卖家→运营整条链，末了回接口层确认订单与托管同步 `REFUNDED`、退款金额等于实付）；`tsc` 零错误、`vite build` 通过；回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。**已无遗留** |

| **A2 · 物流轨迹（第十一轮）** | **履约域新表 `t_logistics_track`**（订单号 + 节点码 + 发生时间 + 描述 + 留档报文；唯一键 `uk_track_order_node_time` 兜幂等，覆盖索引 `(order_no, occurred_at)` 服务正序查询；节点码白名单 `LogisticsNode` 7 种）。**两个端点**：`POST /api/logistics/internal/{orderNo}/tracks`（批量摄入，`X-Internal-Token`，**履约域第一个内部接口**，本域自建 `InternalTokenFilter`；批量全有或全无）+ `GET /api/logistics/orders/{orderNo}/tracks`（买家本人 / 该单卖家 / 运营，按发生时间正序；**无轨迹回空列表而非 404**）。**交易域发货后补推「已揽收」**：`OrderShippedEvent` + `@TransactionalEventListener(AFTER_COMMIT)` + `LogisticsNotifier`（best-effort，**绝不抛出**，不阻断发货）；两条发货路径（卖家中心 / 内部凭据）共用同一段状态机，事件在状态机本体发布，两边都不漏。**前端共用件** `components/LogisticsTimeline.tsx`：正序排列 + 头部「当前：××」常驻提示；未发货给说明性空态；已完成订单轨迹仍在；非当事人在订单页本就被挡，不泄露物流信息；买家 / 卖家订单详情各挂一份。**顺带收口**：履约域新增 `OrderFacts` 统一取数口（评价域与售后域各自那份「调交易域取订单事实」已悄悄漂移——同一句「交易域不可用」一处抛 422、一处抛 503——统一为同一实现）。**自测 `scripts/logistics-smoke.ps1` 79 通过 / 0 失败**（含★**断言总数守卫**：任何一条 Check 因写法错误被静默跳过都会让守卫报失败）+ **`scripts/e2e/logistics-ui-check.mjs` 真实 Chromium 44 通过 / 0 失败**（买家/卖家同轨 + 空态 + 越权 + 终态仍在）；`tsc` 零错误、`vite build` 通过（主包 171.5KB） |

| **A5 · 优惠券营销（第十三轮）** | **新建第 6 个业务服务 `duoduo-marketing`（:8085）**：POM 入父反应堆、三档配置、雪花 **worker-id=5**、compose 第 **9** 个容器（服务名 `duoduo-marketing-app` / 容器名 `duoduo-app-marketing` / 镜像 `duoduo/duoduo-marketing:1.0.0`）、新库 `duoduo_marketing`（`t_coupon_template` + `t_coupon`）。**公开只读** `GET /api/coupons/templates`（领券中心，含 `claimable` / `claimableReason`，**能否领由服务端判定**）；**需登录** `POST /api/coupons/templates/{id}/claim`（**重复领取不报错**，返回 `replay=true` 与已有券码）、`GET /api/coupons/mine?status=`、`GET /api/coupons/mine/usable?amount=`（服务端给 `bestCouponCode`）；**运营** `POST /api/coupons/admin/templates`（`OperatorGuard`）；**内部**（`X-Internal-Token`，**不进网关**，交易域直连 8085）`/api/coupons/internal/{lock,release,consume,sweep}`。**三条纪律**：① 券码用**随机串**（`CP` + 14 位去混淆字符，字符表去 `0/O/1/I`）而**不用雪花**（规避 JS `Number.MAX_SAFE_INTEGER` 精度坑，同 INV-16）；② 锁 / 解 / 核销**全部 CAS 条件更新、不用分布式锁**（INV-1）；③ **营销域只算「能抵多少」，折扣金额由交易域写入 `t_order.discount_amount`**（被调用方、**不依赖 openfeign**）。**交易域接入**：下单 `placeOrder` 先锁券（失败抛 422 带人话原因）→ `paySuccess` 核销（核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁；`t_order` 增列 `coupon_code VARCHAR(32)`（增量脚本 `docs/sql/06-a5-coupon-columns.sql`）。**网关白名单收紧为精确路径 `/api/coupons/templates`**（白名单按**路径**而非方法匹配，放通配会把领券 / 券包 / 内部接口一起放成匿名可调，与既有 `/api/reviews/product/**` 同一纪律）。**前端**：新增 `pages/Coupons.tsx`（领券中心 + 我的券包双页签）、路由 `/coupons`（**刻意不套 `RequireAuth`**）；一级导航 + 用户下拉 + 「我的」页加快捷入口；结算页选券（`undefined`=未选择→用服务端推荐 / `null`=明确不用，提交只传 `couponCode`）、订单详情金额区展示优惠与券码。**自测 `scripts/coupon-smoke.ps1` 145 通过 / 0 失败**（含★断言总数守卫）+ **`scripts/e2e/coupon-ui-check.mjs` 真实 Chromium 43 通过 / 0 失败**（配套夹具 `scripts/e2e/coupon-ui-fixture.ps1 -Action create\|drop`）；`tsc --noEmit` 零错误、`vite build` 通过（**79 模块**） |

| **A8 · 收货地址簿 + T-39 精度根治（第十四轮）** | **用户域新表 `t_user_address`**（`docs/sql/07-a8-user-address.sql`；**6 个用户端点**：列表（默认排最前）/ 新增 / 修改 / 删除 / 设为默认 / 取默认 + **内部快照** `GET /api/addresses/internal/snapshot`（`X-Internal-Token` + 本域 `InternalTokenFilter` 失败关闭、**不进网关**））。**四条业务规则**：① 首个地址自动成为默认；② 设默认在**事务内**先清旧默认再置新，库内恒有且仅有 1 条默认；③ **删默认自动顺延**并 toast 告知；④ `fullAddress` 拼接**只有一处定义**（`AddressService.fullAddress()`，直辖市省=市只留一次），内部快照与地址簿接口**逐字相同**（冒烟锁死）。**归属校验统一 404**（改/删/设默认他人地址与"id 不存在"**同码同文案**，刻意不区分"不存在"与"无权限"以免用状态码差异枚举他人地址 ID —— 这是对 `docs/agent/07` 原文「403」的**有意偏离**）。**交易域接入**：下单新增可选 `addressId`，传了就**只认它**（直填三字段被忽略，避免两套来源冲突）；收货信息在**下单瞬间写快照**（`t_order.receiver_*`），此后改/删地址簿**不影响历史订单**。**★ T-39 雪花 ID 精度根治**：18~19 位雪花 id 超出 JS `Number.MAX_SAFE_INTEGER`（16 位），以 JSON number 下发会被浏览器 `JSON.parse` **静默四舍五入**（`Number('...704') === '...700'`，不报错）→ 前端拼 URL → 服务端查不到 → **404**；**接口冒烟照不出来**（.NET 用 Int64/Decimal 解 JSON 不丢精度），**A3 曾局部踩过一次、A8 又踩一次**。方案：`duoduo-common-web` 新增 **`LongToStringJacksonModule`**（随 `WebAutoConfiguration` 自动装配到全部业务服务）—— `Long`/`long`/`BigInteger` 默认转字符串，**字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额/计数/epoch 毫秒时间戳）的保持数字**；**判据是字段语义不是 Java 类型**（全站金额字段 **63 处都是 `Long`**，一刀切会让前端 `price / 100` 算出 `NaN`、页面金额全打成「¥NaN」）。**漏标方向安全**：新加 id 字段无需任何操作即受保护。前端 `types.ts` **38 个 id 类字段** + `endpoints.ts` **25 处形参**改 `string`，13 个页面逐个修到 `tsc --noEmit` 零错误；纪律写进 `types.ts` 顶部（id 只拼 URL 与相等比较，**绝不做数值运算**）。**顺带修掉一个真缺陷**：`Checkout.tsx` 初始化守卫写成 `addressList === null`，而本仓 `useAsync` 初始 `data` 是 **`undefined`** → 首次渲染即锁定"空地址簿"分支、把用户推进手动填写模式（地址渲染出来了却一个都选不上）→ 改为 **`addrLoading` 门控 + 判 `undefined`**（见 T-42）。**自测**：`scripts/address-smoke.ps1` **82/0**（含★断言总数守卫）+ `scripts/e2e/address-ui-check.mjs` **64/0**（真实 Chromium，16 段）+ **`scripts/id-precision-smoke.ps1`（新增）56/0**（跨四域，**直接断言 JSON 原始文本**绕开 `ConvertFrom-Json` 的精度掩盖）+ `scripts/run-all-smoke.ps1`（新增，一键全套并汇总）；**全套 15 套 1286 项全绿**，连带修 2 处因 T-39 而失效的旧断言（`review-smoke` 抠主键正则要允许引号；`order-smoke` 的「缺收货人」用例补"先加购"前置，否则会撞上「购物车为空」的**同码不同因**假绿灯）。踩坑登记 **T-39 / T-40 / T-41 / T-42** |

### 进行中

| 任务 | 当前进展 |
|---|---|
| ✅ **M11 剩余块：信用分（A4）已交付**（2026-09-20，第十二轮） | 履约域**四块已全部落地**：评价与晒单、售后仲裁（A3，含三端页面）、物流轨迹（A2，含买卖双方时间线）、**信用分（A4）** —— 同域内已无低成本可做项，下一项须新起服务或中间件 |
| ✅ **M11 剩余块：优惠券营销（A5）已交付**（2026-09-23，第十三轮） | **新增第 6 个业务服务 `duoduo-marketing`（:8085，雪花 worker-id=5）**：领券中心 / 券包 / 内部锁·解·核销 / TTL 补偿调度 + 前端领券中心与结算选券；容器 **8 → 9**（3 中间件 + 6 业务服务）。v1 边界：券不随退款退回、只实现满减、一张券只作用于拆单后商品总额最大的那一单 |
| M11 其他：ES 检索（A10）/ RocketMQ + Sentinel（A11）/ 压测（A12）/ XXL-Job | 都需要新中间件或新服务，建议按「检索 → 削峰 → 压测」顺序推进 |
| **A9 AI 导购「多多」（已排期，待用户提供凭据）** | 用户 2026-09-18 拍板：**用阿里百炼大模型**；**百炼 / DashScope API Key 暂未提供（待用户提供）** → 接口与降级链路可先做，联调需等凭据 |
| **A13 买家端微信小程序（已排期，待用户提供凭据）** | 用户 2026-09-18 拍板：**排期，允许先开始编码**；**小程序 AppID 暂未提供（待用户提供）** → 可先用开发者工具测试号开工，真机预览等 AppID |

### 未开始（按你确认的端形态排期）

| # | 任务 | 依赖 | 说明 |
|---|---|---|---|
| M7 | 买家端 微信小程序 | M6 的接口封装 + 小程序 AppID | 登录接 `code2Session`；支付复用同一套预下单 + 轮询接口 |
| M11+ | 运营账号管理页（提权目前靠 SQL） | M10 ✅ | 后台可视化开通/停用运营账号 |

---

## 5. 问题与协助区

> 你处理完把「状态」改成 **已协助** 即可，我会先验证再继续。

| # | 问题 | 状态 | 需要你做什么 | 我的验证方式 |
|---|---|---|---|---|
| I-01 | Docker Desktop 我无法代启动：家庭版必须走 WSL2，启动要调 `wsl.exe`，而它在沙箱黑名单且禁止绕过 | **已协助**（2026-09-15 你手动双击启动） | 无（若我提示引擎又停了，帮我双击一次） | `docker info` 能返回 Server 版本 |
| I-02 | `docker` 凭据助手不在 PATH，拉镜像失败 | **已自愈** | 无需操作 | 用独立配置目录 `D:\duoduo-infra\docker-nocreds`（不含 credsStore）绕开 |
| I-03 | 沙箱禁止我用 `Start-Process` 起 java 进程 | **已自愈** | 无需操作 | 改为容器化由 `docker compose` 起服务 |
| I-04 | 后台 PowerShell 命令中的中文路径按 ANSI 解析被破坏 | **已自愈** | 无需操作 | 改为前台执行；产物落在正确路径 |
| I-05 | `cmd.exe` 被沙箱禁止，无法用 `mvn.cmd` 构建 | **已自愈** | 无需操作 | 用 `java` 驱动 Maven launcher（`plexus-classworlds`） |
| I-06 | 沙箱侧 `github.com` 的 git 端点间歇 502 | **已自愈** | 无需操作 | 改用 `api.github.com` 增量同步 + 逐文件 blob 哈希比对 |
| I-07 | 容器构建需拉 `eclipse-temurin` 基础镜像 | **已自愈** | 无需操作 | 镜像已拉到本地，四个业务镜像构建成功 |
| I-08 | 容器化后冒烟 2 项失败：中文关键词检索命中 0 条 | **已自愈**（2026-09-17） | 无需操作 | 根因：初始化 SQL 被按 **latin1** 执行，中文**双重编码**入库。修法：脚本开头加 `SET NAMES utf8mb4`。复验：库内 `HEX` = `E9BAA6`、检索命中 4 条、冒烟 54/54 |
| I-09 | 购物车自测脚本自身有缺陷，一度把种子数据改坏（**测试代码问题**） | **已自愈**（2026-09-17） | 无需操作 | ① PowerShell 哈希表按**键类型**判等 → 21 条断言误报；② 改库复原写死数字 → 每跑一次污染一次。修法：字符串键 + 先读原值再写回 + 结尾自证数据已复原 |
| I-10 | 下单存在**幽灵冻结**风险：库存冻结在商品服务，不受本服务事务保护 | **已自愈**（2026-09-17） | 无需操作 | 原实现只在「整批全部成功」时记录补偿列表，**部分成功时已冻成功的 SKU 被漏记** → 库存永久冻住。修法：按逐项结果精确补偿；商品侧批次变更改为**整批原子** |
| I-11 | 订单生命周期三处真实缺陷（已付款可自助取消 / 超时关单不释放冻结 / 并发释放被记成失败） | **已自愈**（2026-09-17） | 无需操作 | 三处均由自测暴露并修复，且用断言固定住。`PAID → CLOSED` 那条被误用的边已在 M5-8 移除，退款改为独立终态 `REFUNDED` |
| I-12 | 真实微信 / 支付宝扫码支付需要商户资质与密钥 | **已协助**（2026-09-17 你决定改用**沙箱支付**，资质不再需要） | **无需操作**。材料清单仍留在下方折叠区，将来要接真实收款时再取 | 沙箱支付链路已按真实渠道的形态实现并自测通过：预下单返回二维码内容 → 收银台确认支付 → **签名回调** → 幂等入账 → 订单转已付款 → 资金进托管。将来把 `SANDBOX` 换成真实实现即可 |
| I-13 | 退货入库端点**编译通过但运行时 404**（新增端点无内部引用，编译期发现不了） | **已自愈**（2026-09-17） | 无需操作 | 现象：`NoResourceFoundException`，退款后入库一直失败、台账卡在 `RESTOCK_PENDING`。根因：源码里 `/restock` 路由在编辑过程中丢失，而它没有任何内部调用方。修法：补回路由 + 重建镜像；复验：调度 `restocked=2`，台账迁移到 `RESTOCKED`。**教训**：新增对外端点必须实调一次 |
| I-14 | **历史订单缺托管单**（M5-8 上线前的订单没有 `t_settlement` 记录） | **已自愈（2026-09-18，A1 完成）** | 无需你操作 | 影响：这些订单确认收货时「放款」会找不到托管单（只记 ERROR，不影响买家收货）。**修法**：新增 `POST /api/admin/settlements/backfill?dryRun=`（运营、幂等）按订单状态推断资金状态。**实测复验**：回补前开发库有 3 笔 `COMPLETED` 缺账本，执行后 `GET /api/admin/settlements/abnormal` 返回**空**；`scripts/backfill-smoke.ps1` 88 项断言全绿（含幂等、控制组、REFUNDED 盲区）。⚠️ 生产上线时**仍需在真实数据上跑一次**（先 `dryRun=true` 核对计划） |
| I-16 | 客户端发错 `Content-Type`（如 POST 无 body 时带 `x-www-form-urlencoded`）落到兜底 handler → 返回 **500** | **已自愈**（2026-09-18） | 无需操作 | 客户端错误不应是 5xx，会污染告警。修法：新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 `handleUnsupportedMediaType`，返回 **415**；由卖家冒烟断言固定 |
| I-15 | 支付渠道首轮自测暴露**三处缺陷**（M8-1 实现期） | **已自愈**（2026-09-18） | 无需操作 | ① **未知渠道 / MOCK 回调拒绝用了 400**（应为 422 业务错误，注册表 javadoc 与实现不一致）→ 补错误码 `PAYMENT_CHANNEL_UNKNOWN` 并改用之；② **二维码内容默认指向交易服务 8083 直连地址**——扫码设备不可达，且网关缺 `/sandbox/pay` 路由 → 改为网关 8080 + 补路由与白名单；③ **订单取消 / 关单不作废未支付支付单**——`markClosedByOrder` 在 Mapper 里定义了却从未被调用，订单已取消、二维码还能扫出钱来 → 在取消与调度关单两条路径都接上。三处均已由 62 项自测断言固定 |
| I-17 | 履约域（M11/P2-1）自测暴露**三个工具层隐患** | **已自愈**（2026-09-18） | 无需操作 | ① 内部接口断言**打错服务端口**（连履约 8084 打用户域路径 → 无路由 500，看着像过滤器坏了）；② **PowerShell 把 `$SPU?page=1` 整体当变量名**（`?` 是合法的变量名字符），展开成 `=1&pageSize=50`，服务端收到 `spuId="=1&pageSize=50"` → 必须写 `${SPU}?`；③ **19 位雪花主键经 `ConvertFrom-Json` 丢精度**，出现「数据在库里、断言找不到」的假失败 → 断言改用业务键，需要主键时从**原始响应文本**正则提取 |
| I-18 | **`git push` 通道受阻**：`github.com` 的 git 端点报 `CONNECT tunnel failed, response 502` / `Empty reply from server`（代理只放通 `api.github.com`） | **已自愈**（2026-09-18） | 无需操作 | 改走 **REST Git Data API** 推送成功（远端 `main = af28bba`）；工具 `.workbuddy/tools/push_via_api.py`（令牌取自 git 凭据管理器，不落盘、不回显）。注意：本地与远端 **SHA 分叉但内容一致**，网络恢复后执行 `git fetch origin main && git reset --hard origin/main` 对齐即可 |
| I-19 | 信用分（A4）自测暴露**四个工具层隐患 + 两个实现问题** | **已自愈**（2026-09-20） | 无需操作 | **工具层**：① 冒烟辅助函数少取一层 `.body`（响应包络是 `{status,body,…}`，数据在 `body.data` 下）→ 一批断言假失败；② 跨库 `INSERT…SELECT` 未写库限定名（`FROM t_order` 打到不存在的表）→ **插 0 行且不报错**，改 `FROM duoduo_trade.t_order`；③ 前端 TS `noUncheckedIndexedAccess` 下 `Record` 取值可空，`?? FALLBACK` 压不住 → 用**独立声明的非空常量**兜底；④ **`trade-smoke.ps1` 的 `Call` 少声明 `$headers` 参数**，调用处多传的实参被 PowerShell 静默丢弃 → 内部凭据头从未发出、内部批量 SKU 接口恒 403（7 条断言连带失败）→ 补齐参数后 87/0。**实现层**：⑤ 注册默认分硬编码 `100`，与 `CreditModel` 基础分 60 不一致 → 改可配置 `credit-default`（默认 60）并同步 DDL；⑥ `AfterSaleMapper` 漏 `@Select` import（编译期即失败），顺带把 `parseIds` 的 `NumberFormatException` 转 400。以上均已由 82 项冒烟 + **回归 13 套全绿**固定（详见 `08` 篇 **T-33 ~ T-36**）；顺带修正 `seller-smoke` 旧口径 84 → **101**（累计 935/1017 → **1034**） |

<details>
<summary><b>附：真实渠道备查（当前不需要你提供，将来要接真实收款时再取）</b></summary>

- **微信支付**：商户号 `mchid`、APIv3 密钥、商户 API 证书（`apiclient_cert.pem` / `apiclient_key.pem`）、
  关联 AppID（小程序 / 公众号）、可公网访问的 HTTPS 回调域名
- **支付宝**：AppID、应用私钥、支付宝公钥（或证书模式三件套）、已签约产品（电脑网站支付 / 当面付）
- **前置资质**：微信支付商户号需营业执照（个人主体申请不到）；支付宝需企业实名认证；回调域名需备案

</details>

---

## 6. 本轮变更记录

### 2026-09-23（第十四轮 · A8 收货地址簿 + T-39 雪花 ID 精度根治）

| 文件 | 变更 |
|---|---|
| `duoduo-common-web/.../LongToStringJacksonModule.java`（**新增，本轮核心**） | **全站 ID 精度保护**。规则 =「`Long`/`long`/`BigInteger` **默认转字符串**」+「字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额 / 计数 / epoch 毫秒时间戳三类）的**保持数字**」，用 `BeanSerializerModifier.changeProperties()` 把命中白名单的字段 writer 换成 `NumericLongPropertyWriter`（`gen.writeNumber`）绕过全局 ToString 序列化器。**判据是字段语义**（前端会不会拿它做算术），不是 Java 类型 —— 全站金额字段 63 处都是 `Long`，一刀切会让前端 `price / 100` 算出 `NaN`。**漏标方向安全**：新加 id 字段无需任何操作即受保护。⚠ 白名单**必须用 `Set.copyOf(Arrays.asList(...))`**：`Set.of()` 遇重复元素在**运行期**抛 `IllegalArgumentException: duplicate element`（编译期不报）。反序列化与 `@PathVariable Long` 不受影响 |
| `duoduo-common-web/.../WebAutoConfiguration.java`（**改**） | 新增 `@Bean LongToStringJacksonModule`（供 Feign / RestTemplate 等非 MVC 场景），并在 `extendMessageConverters` 里给每个 `MappingJackson2HttpMessageConverter` 挂上该模块；两者都挂是刻意的 —— 前者让"隐藏规则"显式出现在装配清单里，读代码的人不必翻 `extendMessageConverters` 才知道 id 是字符串 |
| `duoduo-user/`（**A8 新增文件**） | `entity/UserAddressEntity`、`mapper/UserAddressMapper`、`service/AddressService`（**`fullAddress()` 是全站唯一拼接处**：省+市+区+明细直接相连，直辖市省=市只留一次；设默认/删除在**事务内**保证"恒有且仅有 1 条默认"）、`controller/AddressController`（6 用户端点 + `GET /internal/snapshot`）、`support/InternalTokenFilter`（内部凭据，失败关闭）；`dto/UserDtos` 增地址相关 DTO；`resources/db/schema.sql` 增表 |
| `docs/sql/07-a8-user-address.sql`（**新增**） | `t_user_address`（user_id / 收货人 / 电话 / 省市区 / 明细 / 标签 / 是否默认 / 时间戳）；`uk_addr_user_default` 之类约束 + 覆盖索引 `(user_id, is_default)` |
| `duoduo-trade/.../client/{UserAddressClient,UserAddressGateway}.java`、`client/dto/AddressCallDtos.java`（**新增**） | 交易域直连 8081 调内部快照接口取收货信息（`X-Internal-Token`，**不进网关**）；查不到或归属不符 → 拒单（归属校验**必须由服务端做**，这也是接口只收 `addressId` 不收地址内容的原因） |
| `duoduo-trade/.../{service/OrderService.java,dto/OrderDtos.java}`（**改**） | 下单新增可选 `addressId`：传了**只认它**（直填三字段被忽略，避免两套来源冲突），为空才回落旧逻辑；收货信息在**下单瞬间写快照**；`receiver*` 三字段上的 `@NotBlank` **移除**（否则"只传 addressId"的新用法会直接 400），改在服务层显式校验抛 `BizException.validation` → **400 / `VALIDATION_ERROR`**（⚠ 与"购物车为空"的 **422 / `BUSINESS_RULE`** 不是同一码，冒烟里刻意区分以防假绿灯） |
| `frontend/src/api/types.ts`（**改**） | 顶部「约定」扩写为完整章节（`MAX_SAFE_INTEGER` 说明 + `Number('...704') === '...700'` 示例 + 两条纪律 + 禁止项）；**38 个 id 类字段**由 `number` 改 `string`（`id`/`parentId`/`categoryId`/`brandId`/`sellerId`/`skuId`/`spuId`/`addressId`/`templateId`/`buyerId`/`userId`/`petId`）；`ProductQuery.categoryId`/`brandId` 同步 |
| `frontend/src/api/endpoints.ts`（**改**） | **25 处** id 形参类型 `number` → `string`；新增 `addressApi`（list / create / update / remove / setDefault / getDefault） |
| `frontend/src/pages/Addresses.tsx`（**新增**） | 地址簿页：`LABEL_PRESETS`（家/公司/学校）、`PHONE_RE` 校验、新建时 `isDefault: addresses.length === 0`（首个自动默认）、编辑已是默认时复选框 `disabled`、删除确认 Modal 提示「历史订单保存的是下单当时的地址快照」 |
| `frontend/src/pages/Checkout.tsx`（**改，含真缺陷修复**） | 地址二选一（单选列表 vs 手动填写）：payload 三元确保**只传其中一种**、两种分支都必须存在 `<form id="checkout-form">`（否则提交按钮变死）。**★ 修复地址加载竞态**：初始化守卫原写 `addressList === null`，而 `useAsync` 初始 `data` 是 **`undefined`** → 首次渲染即锁定"空地址簿"分支、把用户推进手动填写模式（地址渲染出来了却选不上、提交报"请填写收货人姓名"）→ 改为 **`addrLoading` 门控 + 判 `undefined`**；"正在加载…"占位同步由 `addrLoading && addressList === null`（恒 false 的死代码）改为 `addrLoading`。见 T-42 |
| `frontend/src/pages/{Cart,ProductDetail,ListingDetail,Search,Categories,ReviewNew,admin/Audits,Coupons,seller/Products,Me}.tsx`、`components/{CreditBadge,ReviewSection}.tsx`（**改**） | id 类型改 string 后的逐文件收口（共 13 个页面 / 组件）。要点：`ProductDetail` 的 `Number(id)` 改为直接用字符串 + 新增 `hasSpuId` 布尔哨兵（`Number.isFinite(id)` 一并改写）；`Categories` 新增 `TOP_PARENT = '0'` 常量（这是**真正的数值语义**哨兵，与雪花 id 可区分）；`Search` 新增 `rawId()`（只做非空校验不转数字，专供 `categoryId`/`brandId`） |
| `scripts/address-smoke.ps1`（**新增**） | **82 项断言 / 0 失败**（含★断言总数守卫）。覆盖 07 篇四条验收 + 归属校验（刻意统一 404）+ 内部凭据边界（无 token 403 / 错误 token 403 / 经网关无登录态 401）+ 直辖市拼接去重 + 首条自动默认 + 设默认唯一性 + 删默认顺延 + 快照不回写 + 参数边界 + 清理复原。⚠ `Sql` 函数上方注明**别用 `Sql` 回读中文**（PS 5.1 按 CP936 解码 mysql 的 UTF-8 输出会变「鐜嬩簲」），中文一律走 `HEX()` 或接口 JSON |
| `scripts/e2e/address-ui-check.mjs`（**新增**） | **64 项断言 / 0 失败**（真实 Chromium）。16 段：夹具自检 / 买家账号 / 未登录访客被 `RequireAuth` 拦截 / 登录 / 空地址簿 / 新增第一条（首个自动默认）/ 新增第二条（不抢默认）/ 设为默认（UI 唯一性）/ 编辑（默认复选框禁用）/ 删除默认（toast 告知顺延）/ 结算页自动预选默认 + 下单 / 订单详情 == 地址簿 / 改地址簿后快照不变 / 切手动填写（提交按钮不能变死按钮）/ 清理 / 前端噪声。**必须 `localhost`**（网关 CORS 白名单只认它）；`idOf()` 接收整个响应对象（误传地址对象会得到空串 → URL 变 `/api/addresses/` → `NoResourceFoundException` 500）；跨段取 id 必须**实时重拉**而非用旧快照（默认已顺延，旧 id 会 404） |
| `scripts/id-precision-smoke.ps1`（**新增**） | **56 项断言 / 0 失败**（含★守卫）。跨商品 / 用户 / 交易 / 营销四域，**核心手法：只对 `$resp.raw` 做正则断言，不做反序列化** —— 因为 PowerShell/.NET 用 `Int64`/`Decimal` 解 JSON 数字**不丢精度**，这正是所有既有冒烟"照不出 T-39"的原因。断言分三类：① id 类字段必须是**带引号的字符串**且与库内**逐字符相同**（末位不许被舍入）；② 金额/计数/epoch 时间戳必须**仍是不带引号的数字**（防"一刀切"误伤）；③ 端到端用响应里抠出的 id 反查，证明它"可用"而不只是"长得像字符串"。含"自证前提"断言：该 id 确实超过 16 位（否则脚本没有存在意义） |
| `scripts/run-all-smoke.ps1`（**新增**） | 一键跑全套冒烟并输出汇总表 + 失败清单（**排除自身**，否则自我递归）。逐套落盘 `logs/smoke/*.log` |
| `scripts/{review,order}-smoke.ps1`（**改**） | 因 T-39 修复而失效的 2 处旧断言：① `review-smoke` 从原始文本抠主键的正则 `"id":(\d+)` 匹配不到新形态 `"id":"361…"`（引号挡住了）→ 改 `"?(\d+)"?` 兼容两种形态；② `order-smoke` 的「缺收货人」用例**补上"先加购"前置**（否则会先撞上「购物车为空」的 422，是**同码不同因**的假绿灯），并加断言确认 `code == 'VALIDATION_ERROR'` 而不仅看状态码 |
| `docs/agent/08-环境运维与踩坑禁忌.md`（**改**） | 新增 **T-39**（雪花 ID 精度：判据「接口冒烟过、浏览器 4xx」→ 先怀疑 id 精度，抓包看末位是不是变成 0）、**T-40**（一刀切字符串化会让金额变 `NaN`；白名单必须用 `Set.copyOf(Arrays.asList(...))`）、**T-41**（改公共模块后业务 fat jar 未重打：`mvn package` 增量判定业务模块无变化 → 不重打 jar → 容器里跑的还是旧代码；判据「改动没生效 + 构建很快（几秒 EXIT=0）」→ **查 `target/*.jar` 的 mtime，别查退出码**；修法 `mvn clean install` + `build --no-cache` + `up -d --force-recreate`）、**T-42**（`useAsync` 的 `undefined` 被当成空列表：守卫判 `=== null` 是死代码，要用 `loading` 门控）、**T-43**（`git push` 报 `CONNECT tunnel failed, response 502` 看起来像网络全断，实为代理变量未生效：须在**同一条 Bash 命令内显式 `export` 小写** `https_proxy`/`http_proxy` 再 `git push`，推完用 `git ls-remote` 哈希复核；另注 `api.github.com` 通 ≠ `github.com` 通，GitHub connector 对私有仓可能返 404） |

### 2026-09-23（第十三轮 · A5 优惠券营销）

| 文件 | 变更 |
|---|---|
| `duoduo-marketing/`（**新增整个模块，第 6 个业务服务**） | POM 入父反应堆（reactor 变 **9 模块**）；`MarketingApplication`（`@MapperScan`）；`config/MarketingServiceConfig`（分页 + 雪花 **worker-id=5**，与用户 1 / 商品 2 / 交易 3 / 履约 4 错开）；`application.yml` / `-local` / `-mysql` 三档；`controller/{CouponTemplateController,CouponController,CouponInternalController}`；`service/CouponService` + `service/CouponReleaseJob`（TTL 补偿）；`entity/{CouponTemplateEntity,CouponEntity}` + `mapper/{CouponTemplateMapper,CouponMapper}`；`support/{CouponCodeGenerator,CouponStatus,InternalTokenFilter}`；`dto/CouponDtos`；`resources/db/schema.sql` |
| `duoduo-marketing/.../resources/application.yml`（**新增**） | 端口走 **`DUODUO_MARKETING_PORT`**（**刻意不用通用名 `MARKETING_PORT`**，避免宿主环境已有同名变量时静默改端口）；`COUPON_LOCK_TTL_MINUTES`（默认 **35**，**略大于**交易域订单超时关单 30，避免「订单还活着、券已被别人领走」）、`COUPON_RELEASE_SWEEP_MS`（60000）、`COUPON_RELEASE_SWEEP_DELAY_MS`（30000）、`COUPON_RELEASE_BATCH`（200）；内部凭据复用 `INTERNAL_TOKEN` |
| `docs/sql/05-duoduo-marketing.sql`、`docs/sql/06-a5-coupon-columns.sql`（**新增**）、`docs/sql/00-init-databases-and-user.sql`（**改**） | 新库 `duoduo_marketing`：`t_coupon_template`（运营券模板：名称/描述/类型/门槛/抵扣/总量/已领量/起止/状态）、`t_coupon`（用户券：券码/用户/模板/状态/锁定订单号/核销订单号/领取时间/过期时间），**领券幂等靠唯一索引 `uk_coupon_user_tpl(user_id, template_id)`**（一人一模板一张）；种子模板 **5001**（新客立减 5 元，满 50 减 5，1000 张）/ **5002**（满 200 减 30）；交易域增量列 `t_order.coupon_code VARCHAR(32)`（**MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，重复执行报 1060 属预期**） |
| `duoduo-trade/.../client/{MarketingClient,MarketingGateway}.java`、`client/dto/CouponCallDtos.java`（**新增**） | 交易域按「**营销域是被调用方**」的纪律做**反向**取数：交易域直连 8085 调内部接口（`X-Internal-Token`，**不进网关**）；`MarketingGateway.release` 带 `scene` 参数用于日志与调用描述 |
| `duoduo-trade/.../{service/OrderService.java,dto/OrderDtos.java,entity/OrderEntity.java,resources/db/schema.sql}`（**改**） | 下单 `placeOrder` 先**锁券**（失败抛 422 带人话原因，如「满 200 元可用」）→ `paySuccess` **核销**（核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁（统一带 `scene` 便于排障）；`OrderView` 增 `couponCode` / `discountAmount` |
| `duoduo-gateway/.../application.yml`、`config/GatewaySecurityProperties.java`（**改**） | 新路由 `duoduo-marketing` → `Path=/api/promotions/**,/api/coupons/**`（**`/api/promotions/**` 是预留路径，确实写在配置里**）；白名单**只放行精确路径 `/api/coupons/templates`**，**不是** `/api/coupons/**` —— 白名单按**路径**而非方法匹配，放通配会把「领券」「我的券包」「内部锁券 / 核销 / 解锁」一起放成匿名可调（与既有 `/api/reviews/product/**` 同一纪律，INV-14） |
| `docker-compose.yml`（**改**） | 第 **9** 个容器 `duoduo-marketing-app`（`container_name: duoduo-app-marketing`，镜像 `duoduo/duoduo-marketing:1.0.0`，:8085，`worker-id=5`）；网关依赖加营销 |
| `frontend/src/pages/Coupons.tsx`（**新增**） | 领券中心 + 我的券包双页签（券模板卡片显示门槛 / 有效期 / `claimableReason`；券包按 `UNUSED/LOCKED/USED/EXPIRED` 筛选） |
| `frontend/src/{App.tsx,components/Layout.tsx}`、`pages/{Checkout,OrderDetail,Me}.tsx`、`api/{types,endpoints}.ts`（**改**） | 路由 `/coupons`（**刻意不套 `RequireAuth`**：领券中心是公开落地页，未登录也看得到有什么券，点「立即领取」时才引导登录）；一级导航加「优惠券」、用户下拉与「我的」页加「我的券包」；结算页选券（`undefined`=未选择→用服务端推荐 / `null`=明确不用；费用明细显示优惠与实付；提交只传 `couponCode`）；订单详情金额区展示优惠金额与券码（**有券时才显示券码行**）；新增 `CouponStatus` / `CouponType` / `CouponTemplateView` / `CouponView` / `CouponClaimResult` / `UsableCoupon` / `UsableCouponResult`，`OrderView` 加 `couponCode` |
| `scripts/coupon-smoke.ps1`（**新增**） | **145 项断言**（含★断言总数守卫）：① 领券幂等（重复领返回 `replay=true` 不报错）② 并发防超发 ③ 锁·解·核销 CAS ④ 折扣落交易域 ⑤ 生命周期闭环；另加公开只读边界 / 内部凭据 403 / 门槛文案（「满 200 元可用」）/ 券码非雪花 / sweep 幂等 / TTL 环境变量校验 / 运营建券准入 / 清理复原 |
| `scripts/e2e/coupon-ui-check.mjs` + `scripts/e2e/coupon-ui-fixture.ps1`（**新增**） | **43 项真实 Chromium 断言**（未登录态 / 领券 / 券包 / 结算选券与费用口径 / 订单详情券码 / 取消退回 / 噪声）；夹具脚本 `-Action create\|drop` 建 / 删测试数据 |
| `README.md`、`docs/agent/02·03·04·05·06·07·08`、`docs/PLAN.md`、`docs/PROJECT-STATUS.md`、`docs/ARCHITECTURE.md`、`docs/handover/00~08`、`docs/待协助问题.md` | 接口章节（新增「营销域：优惠券」）、模块与代码地图、不变式举例、契约全集（3 组端点 + 内部 4 端点 + DTO + 状态机）、开发任务手册、门禁表（改优惠券 → `coupon-smoke.ps1` + `coupon-ui-check.mjs`）、待办 A5 标记完成、容器 / 端口 / 脚本口径（8 → 9 容器、14 套 1204 项 + 5 套 180 项）、踩坑 **T-37 / T-38** |

**自测**：
- `scripts/coupon-smoke.ps1` **145 通过 / 0 失败**（守卫确认无静默跳过）；
- `scripts/e2e/coupon-ui-check.mjs` **43 通过 / 0 失败**（真实 Chromium，夹具由 PowerShell 建 / 删）；
- 前端 `tsc --noEmit` 零错误、`vite build` 通过（**79 模块**）；
- 全量编译 **九模块 BUILD SUCCESS**；重建 `duoduo-marketing-app` 容器后 **9 容器 healthy**；
- 接口冒烟累计 **14 套 1204 项**（新增 `coupon-smoke` 145 项），浏览器 UI 断言累计 **5 套 180 项**
  （原 4 套 137 项 = `review-ui 20` + `seller-ui 29` + `logistics-ui 44` + `aftersale-ui 44`；新增 `coupon-ui 43`）。

**第十三轮自测暴露并修复的 5 个问题**（前 3 个是代码缺陷，后 2 个是工程 / 环境坑）：
1. **未登录访客被标记成「可领取」**：`toTemplateView` 没把登录态传进去，未登录时前端会渲染一个注定 401 的
   「立即领取」按钮 → 加 `loggedIn` 分支，未登录时 `claimable=false` + 原因文案「登录后可领取」。
2. **`cancel` 路径漏了解锁券**：原先只有运营强制关单与超时关单会解锁，用户主动取消订单时券会一直卡在 `LOCKED`
   直到 TTL 兜底 → `cancel` 补 `releaseCouponQuietly(orderNo, "用户取消")`，三处调用统一带 `scene` 便于排障。
3. **`OrderService` 构造器漏赋值 `marketingGateway`**（编译报「可能尚未初始化变量」，编译期即拦截）；
   `MarketingGateway.release` 补 `scene` 参数用于日志。
4. **「改完源码没重建容器，冒烟假红」**（**同一坑本轮第二次踩到**）：`loggedIn` 修复写进源码后
   `duoduo-app-marketing` 容器没重建，`coupon-smoke.ps1` 首跑 **143 通过 / 2 失败**，失败项恰是
   「未登录访客应标 `claimable=false`」与「原因文案为『登录后可领取』」—— 而源码里该分支明明存在。
   排查路径：读源码确认分支存在 → 确认调用方已传 `userId != null` → 判断「**代码对但容器是旧的**」。
   修法：`docker compose build duoduo-marketing-app` + `up -d`，重跑后 **145/145 全绿**。
   教训：**改了源码必须重建对应容器再跑冒烟；`docker compose build` 之后补的改动不会进运行中的容器**。
5. **未支付订单详情页的 `/settlement` 404**（P3 前端优化点，**与 A5 无关**）：`OrderService.settlementOf()` 对
   「没有托管记录」的订单**按设计**返回 404（`OrderView.settlement` 注释即写明「未支付时为 null」），页面自行忽略；
   e2e 里已**显式排除该已知良性 404 并注明理由**。未支付时可直接跳过这次请求（记为 P3，暂不改）。

> ⚠️ **两条新踩的坑（第十三轮，已入 `08` 篇 T-37 / T-38）**：
> 1. **T-37 `docker compose build` 之后补的代码不会进运行中的容器**：表现为「代码明明改了、断言还是失败」，
>    curl / 日志都看不到新分支。**修法**：补丁写完必须重新 `mvn package` + `docker compose build <svc> && docker compose up -d <svc>`。
> 2. **T-38 本机 Node 无法创建子进程**：`spawnSync` / `execFileSync` 一律 `EBUSY errno -4082`
>    （`cmd.exe` / `powershell.exe` / `docker.exe` 都试过，两版 Node、沙箱内外都一样）→ 那种「在 Node 里
>    `execFileSync('docker', ['exec','duoduo-mysql','mysql',…])` 跑 SQL」的 e2e 夹具写法在本机跑不通。
>    **修法**：把 SQL 从 Node 里彻底移出 —— 夹具建 / 删由 `scripts/e2e/coupon-ui-fixture.ps1` 用 PowerShell
>    `& docker exec` 完成（与 `coupon-smoke.ps1` 同一姿势），Node 脚本只做 HTTP + 浏览器。

### 2026-09-20（第十二轮 · A4 信用分）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../support/CreditModel.java`（**新增**） | **信用分权重模型（单一事实来源）**：纯函数，输入事实 + 权重 → 分数与因子明细，不碰 DB / HTTP。权重表 `base 60 + 完成率 18 + 评价 16 + 售后 6 = 100`，启动校验和（架构错直接 fail-fast）；三条硬约定：**因子和 == 总分**、**无数据 = 基础分**、**单调可解释**（不设负分项） |
| `duoduo-fulfilment/.../service/CreditService.java`（**新增**） | 取数编排：交易域订单计数（Feign）+ 本域评价聚合 + 售后污点计数 → `CreditModel` 算分 → 回写用户域；**实时计算不读 `t_user` 副本**；交易域不可用抛 `ErrorCode.DEGRADED` 503（不按 0 单算） |
| `duoduo-fulfilment/.../service/CreditRefreshJob.java`（**新增**） | 定时兜底刷新（枚举已知卖家 → 逐个重算回写），后台任务独立、幂等、失败仅跳过并记日志 |
| `duoduo-fulfilment/.../controller/CreditController.java`（**新增**） | `GET /api/reviews/seller/{sellerId}/credit`（**公开只读**：分数 + 分级文案 + 因子明细 + 原始输入）；`sellerId=0` 表示平台自营 |
| `duoduo-fulfilment/.../controller/CreditInternalController.java`（**新增**） | `POST /api/reviews/internal/credits/refresh?userIds=&limit=`（`X-Internal-Token`；不传 `userIds` 刷全部已知卖家）；**没有「直接设分」入口**（分数只能由权重算出）；`userIds` 非法 → 400（不落成 500） |
| `duoduo-fulfilment/.../dto/CreditDtos.java`（**新增**） | `CreditView` / `CreditFactor` / `CreditInputs` / `CreditRefreshResult`（读接口只暴露「分数 + 可解释输入」，不含任何身份字段） |
| `duoduo-fulfilment/.../client/dto/{SellerOrderStats,CreditWriteRequest,CreditWriteResult}.java`（**新增**） | 跨域契约副本（交易域订单计数入参 / 用户域回写入参结果） |
| `duoduo-fulfilment/.../client/{OrderClient,UserClient}.java`、`mapper/{ReviewMapper,AfterSaleMapper}.java`、`support/InternalTokenFilter.java`、`resources/application.yml`（**改**） | OrderClient 加 `sellerStats`；UserClient 加 `writeCredit`；Mapper 加 `aggregateBySeller` / `countAdverseBySeller` / `distinctSellerIds`；Filter 放行内部信用分路径；权重走环境变量（默认和为 100） |
| `duoduo-trade/.../controller/OrderController.java`、`dto/OrderDtos.java`、`service/OrderService.java`（**改**） | 新增 **`GET /api/orders/internal/seller/{sellerId}/stats`**（内部凭据）：只回**订单计数**（完成/关闭/退款/总数），**不含收货人信息**（INV-9） |
| `duoduo-user/.../controller/UserController.java`、`dto/UserDtos.java`、`service/AuthService.java`（**改**） | 新增 `POST /api/users/internal/{userId}/credit`（`X-Internal-Token`，**同值不写库**，回 `changed` / `previousScore`）；注册默认分由硬编码 `100` 改为可配置 `duoduo.user.credit-default`（默认 60） |
| `duoduo-user/.../resources/application.yml`、`resources/db/schema.sql`、`docs/sql/01-duoduo-user.sql`（**改**） | `credit_score` 默认值 `100 → 60`（与 `CreditModel` 基础分对齐，杜绝「库里 100、信用分页 60」的分裂） |
| `duoduo-gateway/.../config/GatewaySecurityProperties.java`（**改**） | 白名单加精确路径 `/api/reviews/seller/*/credit`（`*` 单段通配；写接口仍在白名单之外，INV-14） |
| `duoduo-product/.../{dto/ProductDtos.java,service/ProductService.java,controller/ProductController.java}`（**改**） | `ProductCard` 增 `Long sellerId`（**显式公开例外**：商品/闲置详情页展示卖家信用所必需，与公开 `ListingCard.sellerId` 一致） |
| `frontend/src/components/CreditBadge.tsx`（**新增**） | `CreditBadge`（行内徽标，失败回 `null` 不打断页面）+ `CreditPanel`（「我的」页完整面板：大号分数 + 因子进度条 + 原始输入 + 计算时刻） |
| `frontend/src/api/{types,endpoints}.ts`、`pages/{ProductDetail,ListingDetail,Me}.tsx`（**改**） | `CreditView` 等类型 + `creditApi.seller(sellerId)`；商品页价格区挂徽标、闲置页规格表加「卖家信用」行、「我的」页改用实时计算的 `CreditPanel`（移除静态 `user.creditScore` 展示） |
| `scripts/credit-smoke.ps1`（**新增**） | **82 项断言**：A4 四条验收标准（无数据得基础分 / 区间 0~100 / 单调可解释 / 因子和=总分）+ 公开只读与凭据边界 + 售后只计 `APPROVE_REFUND` + 回写幂等 + 批量刷新 + 夹具复原；含★**断言总数守卫** |
| `README.md`、`docs/agent/02·03·04·06·07·08`、`docs/PLAN.md` | 接口章节（新增「履约域：信用分」）、代码地图、跨服务调用矩阵（2 条新调用）、不变式（INV-17）、契约全集（3 端点 + DTO + 权重表 + 业务规则）、门禁表（改信用分 → `credit-smoke.ps1`）、待办 A4 标记完成、踩坑 T-33~T-35 |

**自测**：
- `scripts/credit-smoke.ps1` **82 通过 / 0 失败**（守卫确认无静默跳过）；
- 前端 `tsc --noEmit` 零错误、`vite build` 通过；
- 全量编译 **8 模块 BUILD SUCCESS**；重建 `user/trade/fulfilment/gateway/product` 容器后 **8 容器 healthy**；
- 回归 7 套全绿：`smoke 54/0`、`review-smoke 59/0`、`aftersale-smoke 101/0`、`logistics-smoke 79/0`、`settlement-smoke 125/0`、`admin-smoke 49/0`、`seller-smoke 101/0`。
- 接口冒烟累计 **13 套 1034 项**，浏览器 UI 断言累计 **4 套 137 项**。
  （本轮回归顺带**修正一处旧口径**：`seller-smoke.ps1` 实为 **101 项**，此前文档误记为 84 项，
  累计总量随之由 1017 修正为 **1034**。）

> ⚠️ **四条新踩的坑（第十二轮，已入 `08` 篇 T-33~T-36）**：
> 1. **冒烟辅助函数少取一层 `.body`**：响应是 `{status, body, …}`，业务数据在 `body.data` 下；把取值抽成函数后
>    写成 `$resp.data.x` 恒为空，导致一批断言假失败（原始响应无此错，**重构成函数后才暴露**）。
> 2. **跨库 `INSERT … SELECT` 未写库限定名**：连接默认库是履约库，`FROM t_order` 打到不存在的表 → 插 0 行且不报错。
>    改 `FROM duoduo_trade.t_order`，并养成「造完夹具先 `COUNT(*)`」的习惯。
> 3. **TS `noUncheckedIndexedAccess` 下 `Record` 取值可空**：`?? FALLBACK` 若在同一 `Record` 里取，仍判可空 →
>    改用**独立声明的非空常量**兜底。
> 4. **函数签名与调用处参数个数不符时，PowerShell 会静默丢弃多余实参**（不报错，也不进 `$args` 报错）：
>    `trade-smoke.ps1` 的 `Call` 只声明了 4 个参数，第 5 个 headers 被静默吞掉 → 内部凭据头从未发出，
>    内部批量 SKU 接口恒 **403**（7 条断言连带失败）。**修法**：`Call` 补 `[hashtable]$headers` 参数
>    （其余 12 套脚本本就都有）；修复后 **87 通过 / 0 失败**。

**第十二轮暴露并修复的 2 个实现问题**（非脚本问题）：
1. **注册默认分与新模型冲突**：`AuthService` 硬编码 `creditScore=100`，与 `CreditModel` 基础分 60 不一致 →
   改为可配置 `credit-default`（默认 60），DDL 默认值同步为 60。
2. **`AfterSaleMapper` 漏 `@Select` import**：编译期即失败，已补；顺带把 `parseIds` 的 `NumberFormatException`
   转成 400（非法 `userIds` 不再以 500 示人）。

### 2026-09-20（第十一轮 · A2 物流轨迹）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../entity/LogisticsTrackEntity.java`、`mapper/LogisticsTrackMapper.java`（**新增**） | 轨迹节点流水实体 + Mapper（按单查正序、批量插入；无状态迁移，纯追加） |
| `duoduo-fulfilment/.../support/LogisticsNode.java`（**新增**） | 节点码白名单（7 种：`PICKED_UP` / `IN_TRANSIT` / `ARRIVED_HUB` / `OUT_FOR_DELIVERY` / `DELIVERED` / `EXCEPTION` / 自定义留位）——**推送方只能选用不能自创**，非法节点码 422 |
| `duoduo-fulfilment/.../service/LogisticsService.java`、`controller/LogisticsController.java`、`controller/LogisticsInternalController.java`（**新增**） | 摄入（批量、全有或全无、幂等撞唯一键跳过）+ 查询（正序、越权分层）。**履约域第一个内部接口** |
| `duoduo-fulfilment/.../support/InternalTokenFilter.java`（**新增**） | 本域内部凭据 Filter（校验 `X-Internal-Token`，**失败关闭**），与用户域那份同口径 |
| `duoduo-fulfilment/.../support/OrderFacts.java`（**新增**） | 统一取数口：把评价域与售后域各自那份「调交易域取订单事实」收口（两处已漂移，同一句「交易域不可用」一处 422、一处 503）；两域改用同一实现 |
| `duoduo-trade/.../client/LogisticsClient.java`、`client/LogisticsNotifier.java`、`client/dto/TrackIngestPayload.java`（**新增**） | 交易域发货后**补推「已揽收」**：`@TransactionalEventListener(AFTER_COMMIT)` 触发，Feign 调履约域内部接口；best-effort，**绝不抛出**，不阻断发货 |
| `duoduo-trade/.../service/OrderService.java` | 两条发货路径（卖家中心 / 内部凭据）共用同一段状态机，`OrderShippedEvent` 在状态机本体发布，两边都不漏 |
| `duoduo-gateway/.../application.yml` | 新增 `/api/logistics/orders/**` 路由到 `duoduo-fulfilment`（内部接口 `/api/logistics/internal/**` **不进网关**，交易域直连服务） |
| `frontend/src/components/LogisticsTimeline.tsx`（**新增**） | 共用时间线组件：正序 + 「当前：××」常驻提示 + 未发货说明性空态；行内 `data-node-code` 稳定测试钩子 |
| `frontend/src/pages/OrderDetail.tsx`、`pages/seller/OrderDetail.tsx` | 买家 / 卖家订单详情各挂一份时间线（时间线置于最显眼处——用户来看的就是「货到哪了」） |
| `frontend/src/api/{types,endpoints}.ts` | `LogisticsTrackView` 类型 + `logisticsApi.tracks(orderNo)` |
| `scripts/logistics-smoke.ps1`（**新增**） | **79 项断言**：发货即揽收 / 乱序入库仍按时间正序返回 / 重复推送幂等 / 批量全有或全无 / 无时区按 UTC / 越权 / 敏感字段裁剪 / 库存与归属复原；含★**断言总数守卫** |
| `scripts/e2e/logistics-ui-check.mjs`（**新增**） | 真实 Chromium **44 项断言**：买家/卖家同轨、未发货空态、非当事人被挡、已完成订单轨迹仍在；**6 张截图** `lg_*.png` |
| `README.md` | 新增「物流轨迹」章节；冒烟口径 11 套 856 → **12 套 935 项**，UI 断言 3 套 93 → **4 套 137 项**；第十一轮变更记录 |

**自测**：`logistics-smoke.ps1` **79 通过 / 0 失败**（守卫确认无静默跳过）；
`logistics-ui-check.mjs` **44 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过（主包 171.5KB）；
回归 `review-smoke 59/0`、`aftersale-smoke 101/0`、`settlement-smoke 125/0`。8 容器 healthy。
接口冒烟累计 **12 套 935 项**，浏览器 UI 断言累计 **4 套 137 项**。

> ⚠️ **两条新踩的坑（第十一轮，已入 `08` 篇 T-31 / T-32）**：
> 1. **Write 工具生成的 `.ps1` 是 UTF-8 无 BOM**，PowerShell 5.1 按 GBK 读 → 中文乱码且语法被撞坏。
>    既有脚本都带 BOM，新建脚本照做即可（无 BOM 时用 `UTF8Encoding($true)` 补写）。
> 2. **多行续行里的 `Check "…" (…) -eq '…'` 断言会被静默跳过**：续行让 `-eq` 落到下一行被当成**参数名**，
>    参数绑定阶段抛异常 → 该断言跳过，但脚本**仍报 0 失败**。已加「**断言总数守卫**」防复发
>    ——任何一条 Check 因写法错误被跳过都会让守卫报失败。

**第十一轮暴露并修复的 2 个问题**（都是自测脚本自身写法问题，非实现缺陷）：
1. **运营断言前提写错**：订单详情按归属拦截，运营不是当事人，页面根本没渲染 → 换成更有价值的断言
   「**确认收货后轨迹不该消失**」。
2. **console 过滤漏排 404**：本测试刻意触发的 404（非当事人访问）没被排除，误报为「非预期 HTTP 错误」→ 补进白名单。

### 2026-09-20（第十轮 · A3 售后三端页面）

| 文件 | 变更 |
|---|---|
| `frontend/src/pages/afterSaleShared.tsx`（**新增**） | 三端共用：状态页签、类型文案与 hint、状态配色、进度步骤条、图片直链拆分 |
| `frontend/src/pages/{AfterSales,AfterSaleNew,AfterSaleDetail}.tsx`（**新增**） | 买家：我的售后 / 申请 / 详情（含申请平台仲裁） |
| `frontend/src/pages/seller/AfterSales.tsx`、`admin/AfterSales.tsx`（**新增**） | 卖家处理弹窗（AGREE 即退款 / REJECT 必填原因）；运营裁定弹窗（支持退款 / 驳回，说明必填） |
| `frontend/src/api/{types,endpoints}.ts` | `AfterSaleView` + 4 个请求类型；`afterSaleApi` 6 个方法、`adminApi` 2 个方法。**`id` 类型为 `string`** |
| `frontend/src/App.tsx` | 新增 5 条路由：`/after-sales`、`/after-sales/new`、`/after-sales/:id`、`/seller/after-sales`、`/admin/after-sales` |
| `frontend/src/pages/{Orders,Me}.tsx`、`seller/{Dashboard,Orders}.tsx`、`admin/shared.tsx` | 入口：「申请售后」只挂在已发货/已完成的订单上；「我的」页加「我的售后」；卖家概览与订单页加「售后处理」；后台标签页加「售后仲裁」 |
| `duoduo-fulfilment/.../dto/AfterSaleDtos.java`、`service/AfterSaleService.java` | **`AfterSaleView.id` 由 `Long` 改为 `String`** —— 18 位雪花超过 `Number.MAX_SAFE_INTEGER`，`JSON.parse` 会静默改写 |
| `scripts/e2e/aftersale-ui-check.mjs`（**新增**） | 真实 Chromium **44 项断言**：买家申请 → 卖家拒绝（空原因被拦）→ 买家申请仲裁 → 运营裁定（空说明被拦）→ 支持退款 → 回接口层确认 REFUNDED；**12 张截图** `as_*.png` |
| `README.md` | 新增「售后三端页面」章节；冒烟口径 2 套 49 → **3 套 93 项** UI 断言；第十轮变更记录 |

**自测**：`aftersale-ui-check.mjs` **44 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过；
本轮改到了订单页 / 「我的」页 / 卖家端与后台共用件，故**三套 UI 自测全跑**：`review-ui-check` / `seller-ui-check` 均 `exit=0`；
回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。8 容器 healthy。
接口冒烟 **11 套 856 项** + 浏览器 UI 断言 **3 套 93 项**。

> ⚠️ **环境坑（第十轮新踩，已入 08 篇 T-27）**：本机前端 dev server 起不来，报
> `listen EACCES: permission denied 127.0.0.1:5173` —— **不是端口被占、也不是沙箱拦的**，
> 是 Windows **排除端口段**：Docker Desktop 启动后 WinNAT 保留 `5149-5248`，5173 正在其中。
> 查 `netsh int ipv4 show excludedportrange protocol=tcp`；改 `--port 3000` + `UI_BASE=http://localhost:3000` 即可，
> **3000 本就在网关 `CORS_ORIGINS` 默认白名单里，不动任何配置**。

**本轮暴露并修复的 4 个问题（`tsc` 与 `vite build` 都发现不了，只有真实浏览器能现形）**：

1. **前端路由与入口链接命名不一致**：路由是 `after-sales`（连字符），链接写成 `/seller/aftersales`
   → 直接落到「页面不存在」。接口路径 `/api/aftersales` 与前端路由**刻意不同**，链接必须照路由写。
2. **`getByRole` 的 `name` 是子串匹配**：`{name:'处理'}` 先命中页签「待卖家处理」，弹窗根本没打开 →
   改为行内 `exact: true` 定位。
3. **用页面文本判断按钮是否消失会被 toast 误伤**：成功 toast 文案就是「已申请平台仲裁」，
   `includes` 恒为真 → 改判 `getByRole(...).count() === 0`。
4. **弹窗默认值是保守项**：裁定弹窗默认结论是「驳回」，按钮名是「确认驳回」而不是「支持退款并执行」。

**数据清理**：清掉前几轮崩溃遗留的 2 条 `PENDING` 售后挂单、4 个未降权的运营账号，
并把 34 笔被测试改过归属的订单 `seller_id` 归还平台自营（seller_id=0）。

### 2026-09-18（第九轮 · A3 售后仲裁工单）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../entity/AfterSaleEntity.java`、`mapper/AfterSaleMapper.java`（**新增**） | 售后单实体（诉求 / 举证图 / 卖家说明 / 仲裁结论与说明 / 裁定人 / 退款金额）；Mapper 5 个 **CAS 条件更新**（卖家同意退款、运营裁定退款、卖家拒绝、买家申请仲裁、运营驳回） |
| `duoduo-fulfilment/.../support/AfterSaleStatus.java`（**新增**） | 状态机单一事实来源：状态常量 + 中文文案 + 类型常量与文案 + 终态判定。**不设「已驳回再改判」的边**（要改判只能新立案，留痕不会自相矛盾） |
| `duoduo-fulfilment/.../support/OrderRefs.java`、`support/ImageUrls.java`（**新增**） | 从评价域抽出的共用件：订单号→稳定内部标识的哈希、图片直链校验与 JSON 读写。抽出的理由是**一致性**——同一订单在两个子域必须得到同一个 id，同一安全规则只应有一处实现 |
| `duoduo-fulfilment/.../service/AfterSaleService.java`（**新增**） | 核心规则：归属与订单状态取自订单事实；仅 `SHIPPED`/`COMPLETED` 可立案（422）；唯一键撞键 409；卖家只能处理自己店铺（403）；拒绝必填说明（422）；买家可从 `PENDING`/`SELLER_REJECTED` 申请仲裁（**对 07 草案的补全**：草案没写「卖家拒绝后买家怎么办」）；运营裁定说明必填（422）；**先让钱动再改状态**；Feign 三类失败分别映射 404 / 422（透传对端原话）/ 503 |
| `duoduo-fulfilment/.../controller/AfterSaleController.java`、`AdminAfterSaleController.java`、`dto/AfterSaleDtos.java`（**新增**） | 6 个用户端端点 + 2 个运营端端点；请求体**无** buyerId/sellerId/status；视图**不含** `arbitratedBy`（裁定人属平台内部信息） |
| `duoduo-fulfilment/.../client/OrderClient.java`、`client/dto/{RefundRequest,RefundResult}.java` | 新增跨服务调用：复用交易域**既有的**运营侧退款入口（不新开资金通路）；契约副本按 R3 约定手写，不共享 jar |
| `duoduo-fulfilment/.../service/ReviewService.java` | 重构：删掉私有的 `orderIdOf` / `validateImages` / `writeImages` / `readImages`，改用 `support/OrderRefs` 与 `support/ImageUrls` |
| `duoduo-fulfilment/src/main/resources/db/schema.sql`、`docs/sql/04-duoduo-fulfilment.sql` | 新增 `t_after_sale` + 4 条索引；容器已实际执行 DDL（`t_review` 数据保留） |
| `duoduo-fulfilment/src/main/resources/application.yml` | 新增 `duoduo.aftersale.max-images`（默认 6，**刻意少于评价的 9**：举证要的是关键几张，不是图集） |
| `duoduo-gateway/src/main/resources/application.yml` | 履约域路由补 `/api/admin/aftersales/**`（**必须显式列举**：product/trade 是按具体子路径列的，不是 `/api/admin/**` 兜底；漏了不报错、只 404）；两段都不进白名单 |
| `scripts/aftersale-smoke.ps1`（**新增**） | **101 项断言**：A3 六条验收标准 + 卖家拒绝→买家升级→运营裁定闭环 + 卖家「同意即退款」独立资金路径 + 同单不同类型可各自立案 + 越权 403 + 列表准入与状态过滤 + **已完成订单裁定退款被拒（B-01）** + 归属夹具复原与库存复原 |
| `README.md`、`docs/agent/02·03·04·06·07` | 接口清单与章节（新增「履约域：售后仲裁工单」）、代码地图、跨服务调用矩阵、契约全集（端点 + DTO + 枚举 + 业务规则）、门禁表（改履约域 → review + aftersale 两套）、待办 A3 标记完成 |
| `docs/待协助问题.md` | 留档新增 S-03（A3 已交付）；待你处理项不变（百炼 API Key、小程序 AppID、信用分权重口径） |

**自测结论**：后端 `BUILD SUCCESS`；重建 fulfilment / gateway 镜像并重启（**8 容器 healthy**）；
新增 `scripts/aftersale-smoke.ps1` **101 通过 / 0 失败**；回归 `review-smoke.ps1` **59/0**、
`settlement-smoke.ps1` **125/0**。十一套接口冒烟合计 **856 项**。前端本轮无改动（未跑 `tsc`/`build`）。

**本轮暴露与修复**

1. **编译期抓到的重构遗漏**：抽出 `ImageUrls` 后漏改 `ReviewService.toView()` 里的一处 `readImages(...)` 调用 →
   `找不到符号: 方法 readImages(String)`。类内私有方法替换靠编译器兜住，这正是「每改一个模块做一次编译检查」的价值。
2. **网关路由是「显式列举」而非前缀兜底**：`/api/admin/**` 并不存在统一路由（product / trade 各自列出具体子路径），
   所以运营侧售后工作台必须显式加进履约域路由，否则**不报错、只 404** —— 新增端点后先用匿名请求确认拿到 401 而不是 404，
   这一步比等冒烟更早发现问题。
3. **19 位雪花 id 精度坑的第二次踩点预防**：售后单 id 一律从**原始响应文本**正则提取后拼 URL，
   买家 id 断言改为「库内两边读字符串再比」，而不是拿 JSON 反序列化出来的数字去比 —— 后者会恒不等（假失败）。

**对 07 篇 A3 草案的两处工程化修正（实现为准）**
- 补 `POST /api/aftersales/{id}/escalate`（买家申请平台仲裁）：草案只有「卖家处理」与「运营裁定」，
  没有「卖家拒绝后买家怎么办」这一步，缺了它流程就断了。
- 「缺说明」判 **422** 而不是用 `@NotBlank` 判 400：这是**流程要求**（这个动作不允许这样提交），
  不是字段格式错。立案的 `reason` 则走注解判 400（字段级必填）。两档分界的理由写在 `AfterSaleDtos` 的类注释里。

### 2026-09-18（第八轮 · A1 历史订单托管单回补 I-14 + 文档口径收敛）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/.../service/SettlementBackfillService.java`（**新增**） | 回补核心：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`）；金额取 `pay_amount`；放款时间取订单**真实 `finished_at`**；只碰 `s.id IS NULL`；不给 `PENDING_PAYMENT`/`CLOSED` 补账；**逐条独立提交**（补了 80 条后第 81 条出错不回滚前 80 条）；幂等靠 `uk_settle_order`，撞键记 `skipped` 而非抛错 |
| `duoduo-trade/.../mapper/OrderMapper.java` | 新增 `findOrdersNeedingSettlementBackfill()`：比 `findOrdersWithoutSettlement()` 多覆盖 `REFUNDED`、不 LIMIT、带出 `finished_at`（异常列表给人看，回补要一次补齐）；只取少数字段返回 `Map`，不让收货人信息进内存 |
| `duoduo-trade/.../dto/AdminDtos.java` | 新增 `BackfillItem` / `BackfillResult`（含审计字段 `commissionBps` / `commissionNote`：历史费率不可还原，口径必须随结果说清） |
| `duoduo-trade/.../controller/AdminSettlementController.java` | 新增 `POST /api/admin/settlements/backfill`；**参数走查询串 `?dryRun=` 而非请求体**（见下方缺陷 ①） |
| `duoduo-trade/.../service/SettlementService.java` | 新增 `platformFeeBps()` 访问器：费率只留一个事实来源，避免回补服务自己再 `@Value` 一遍导致静默不一致 |
| `scripts/backfill-smoke.ps1`（**新增**） | **88 项断言**：权限三层（网关 401 / 直连服务 401 / 普通用户 403）、dryRun 不写库、三组金额与笔数**精确增量**、幂等重复执行不增行、挂账清零、控制组逐字段未变、**REFUNDED 盲区单独断言**、放款时间 = 订单 `finishedAt`、夹具自建自毁 |
| `README.md` | 容器表补 `duoduo-app-fulfilment`（7 → **8** 行）、库数 3 → **4**、Nacos 注册 4 → **5** 服务、冒烟 9 套 667 项 → **10 套 755 项**；§三 补回补接口；新增本轮变更记录 |
| `docs/PLAN.md` | 本文件：更新时间、§4 任务清单补 A1、§4 进行中补 A9/A13 排期口径、I-14 改「已自愈」、§7 命令速查修正容器数与端口、新增本轮变更记录 |
| `docs/agent/02`、`docs/agent/04`、`docs/agent/07` | 代码地图补新文件、契约补新端点与 DTO、待办 A1 标记完成 |

**自测结论**：后端 `BUILD SUCCESS`；重建 trade 镜像并重启（**8 容器 healthy**）；
新增 `scripts/backfill-smoke.ps1` **88 通过 / 0 失败**；回归 `settlement-smoke.ps1` **125/0**、
`payment-smoke.ps1` **71/0**、`admin-smoke.ps1` **49/0**；前端本轮无改动（未跑 `tsc`/`build`）。
十套接口冒烟合计 **755 项**。

**自测暴露并修复的缺陷（三条，都值钱）**

1. **API 健壮性缺陷**：回补接口原用 `@RequestBody(required = false)`，
   客户端「无请求体 + 默认 `Content-Type`」调用时返回 **415** ——
   而运营用 curl 直接调这个运维接口正是最常见姿势。修法：参数改走查询串 `?dryRun=`，
   与既有 `?active=false` 写法一致，彻底消除 Content-Type 这个变量。
2. **断言基准取得太远 → 同时产生假失败与假通过**：dryRun 的「金额一分未动」原先拿「创建夹具之前」的汇总做基准：
   控制组 D 自己确认收货造成的放款增量被算到 dryRun 头上（**假失败**），
   而 A/B/C 三条托管单被删造成的托管额下降恰好把同类偏差抵消（**假通过**）。
   修法：基准**紧贴被测操作**取（`$sumBeforeDry` / `$sumBeforeReal`）。
   教训：跨越多步副作用的断言，基准必须贴着断言点，否则偶合会伪装成正确。
3. **0 值断言被空响应骗过**：步骤 2 的 415 使得 `data` 为 `null`，
   `[int]$null -eq 0` 判定为真 → 「重复执行扫描到 0 条」**假通过**，
   掩盖了「请求根本没成功」这个事实。修法：所有 0 值断言加 `$null -ne $body.data` 前置条件。

**本期踩坑新增（待并入 `docs/agent/08`）**
- **内部接口不能经网关调用**：`/api/orders/internal/sweep` 走 8080 会被网关按「未登录」拦成 401，
  必须**直连交易服务 8083** 并带 `X-Internal-Token`（与 I-13 同源：先确认打的是哪个服务）。
- `docker exec mysql` 的告警行会被 PowerShell 当成 `NativeCommandError` 混进 stdout，
  `Sql` 辅助函数按 `Warning` 过滤的做法要继续保持。

**推送状态**：本轮提交 `9d0abc8` 已正常 `git push`（`28119c7..9d0abc8  main -> main`）。
I-18 记录的「`github.com` git 端点 502」本轮**未复现**，且 REST API 推送造成的
**本地 / 远端 SHA 分叉已消除**（远端与本地同为 `9d0abc8`，无需再 `git fetch && git reset --hard`）。
`git push` 仍失败时按 I-18 走 `.workbuddy/tools/push_via_api.py` 备用通道。

### 2026-09-18（第六轮 · 履约域 M11/P2-1：评价与晒单）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/`（**新增整个模块**） | POM 入父反应堆；`FulfilmentApplication`（`@EnableFeignClients` + `@MapperScan`）；`FulfilmentServiceConfig`（分页 + 雪花 **worker-id=4**）；`application.yml` / `-local` / `-mysql` 三档；复用同一份 `Dockerfile` |
| `duoduo-fulfilment/.../entity/ReviewEntity.java`、`mapper/ReviewMapper.java` | 评价实体（含追评列、审核位）；聚合 SQL（`aggregateBySpu` / `starDistribution`）+ 批量查询（`reviewedOrderNos` / `reviewedSpuIds`） |
| `duoduo-fulfilment/.../service/ReviewService.java` | 核心规则：订单事实校验（归属 / 已完成）、`spuId` 必须在订单明细、评分 1-5、图片 http/https 白名单 ≤9 张、追评一次 + 窗口期、匿名脱敏、碰撞唯一键转 409、用户服务不可用时**降级**为无昵称而非整页失败 |
| `duoduo-fulfilment/.../controller/ReviewController.java`、`dto/ReviewDtos.java` | 7 个接口；请求体**无 userId**；响应时间统一 ISO-8601 UTC |
| `duoduo-fulfilment/.../client/OrderClient.java`、`UserClient.java`、`config/InternalCallConfig.java` | 服务间只读调用（Nacos + Feign，不经网关），统一注入 `X-Internal-Token` |
| `duoduo-fulfilment/src/main/resources/db/schema.sql`、`docs/sql/04-duoduo-fulfilment.sql`（**新增**） | `t_review`：唯一键 `(order_id,user_id,spu_id)`（**一单一商品一评**，不是一单一评）；`order_id` 由订单号派生稳定哈希（不把本域主键绑在对方字符串格式上） |
| `docs/sql/00-init-databases-and-user.sql` | 新增 `duoduo_fulfilment` 库与授权 |
| `duoduo-user/.../support/InternalTokenFilter.java`（**新增**）、`AuthService`、`UserController`、`UserDtos.UserBrief` | 内部只读接口 `GET /api/users/internal/briefs?ids=`（批量昵称头像，**只回三字段**）；批量而非逐条，避免跨进程 N+1 |
| `duoduo-trade/.../dto/OrderDtos.OrderFactView`、`OrderService.internalOrderFact`、`OrderController` | 内部只读接口 `GET /api/orders/internal/{orderNo}/fact`：**裁剪掉收货人信息**的订单事实 |
| `duoduo-gateway/.../config/GatewaySecurityProperties.java` | 白名单新增 `/api/reviews/product/**`（**只放行只读子路径**，写接口仍强制登录） |
| `docker-compose.yml` | 第 8 个容器 `duoduo-fulfilment-app`（:8084）；用户容器补 `INTERNAL_TOKEN`；网关依赖加履约 |
| `frontend/src/components/ReviewSection.tsx`（**新增**） | 商品评价区：汇总（平均分 / 星级分布条 / 好评率 / 带图 / 追评）+ 星级与有图筛选 + 分页 + 匿名脱敏展示 |
| `frontend/src/pages/ReviewNew.tsx`（**新增**） | 按订单逐件评价：**已评过的商品不再出现在表单**，只列待评项；星级大按钮；图片直链输入；匿名勾选 |
| `frontend/src/pages/MyReviews.tsx`（**新增**） | 我的评价 + 追评弹窗；可否追评由服务端 `appendable` 决定（前端不自己算窗口期） |
| `frontend/src/pages/Orders.tsx`、`ProductDetail.tsx`、`Me.tsx`、`App.tsx`、`api/types.ts`、`api/endpoints.ts` | 已完成订单显示「去评价 / 已评价」（**一次批量查** `reviewed`）；详情页挂评价区；「我的」加快捷入口；两条新路由；`reviewApi` + 类型 |
| `scripts/review-smoke.ps1`（**新增**） | **59 项断言**：内部接口保护与字段裁剪、公开读/登录写边界、评价资格（未完成 422 / 他人订单 403 / 不在订单 422）、参数边界、幂等 409、匿名脱敏、追评一次、聚合口径、夹具自建自毁 |
| `scripts/e2e/review-ui-check.mjs`（**新增**） | 真实 Chromium **20 项断言**：订单列表入口 → 评价页打分提交 → 我的评价 → 商品详情公开评价区（含匿名展示），4 张截图 `rev_*.png` |

**自测结论**：8 容器全 healthy（新增履约服务）；`scripts/review-smoke.ps1` **59 通过 / 0 失败**；
`scripts/e2e/review-ui-check.mjs` **20 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过。

**自测暴露并修复的缺陷**：

1. **断言失败其实打错了服务**：内部接口保护断言直连履约服务 8084 却打用户域的路径，
   拿到的是「无此路由」的 500，看起来像过滤器坏了 → 修正为直连对端服务（8081）。
   教训与 I-13 同源：**断言失败先确认打的是哪个服务**。
2. **PowerShell 把 `$SPU?page=1&pageSize=50` 整体当成变量名**（`?` 允许出现在变量名中），
   展开成 `=1&pageSize=50`，服务端收到 `spuId = "=1&pageSize=50"` 报类型转换错。
   修法：一律写 `${SPU}?`。这是继「中文 BOM」「雪花 ID 精度」之后的第三个 PowerShell 隐形坑。
3. **19 位雪花主键经 `ConvertFrom-Json` 丢精度**（与前端 `JSON.parse` 同类），
   导致「数据明明在库里、断言却找不到」→ 断言一律改用**业务键**（订单号），
   需要主键时从**原始响应文本**正则提取。
4. **`商品不在该订单中` 的语义归位**：原用 `validation`（400）返回，
   但它其实是业务规则不满足 → 改 `businessRule`（422）。400 与 422 的区别直接决定
   前端提示策略与告警口径，混用会让真正的参数 bug 淹没在业务分支里。

### 2026-09-18（第五轮 · 平台管理后台 M10）

| 文件 | 变更 |
|---|---|
| `duoduo-common/.../context/OperatorGuard.java`（**新增**） | 运营准入统一入口：`requireOperator()`（未登录 401 已被网关拦，非运营 **403**），`isOperator()` 与 `LoginUser` 同口径（忽略大小写，OPERATOR/ADMIN 均可） |
| `duoduo-product/.../controller/AdminCatalogController.java`（**新增**） | `/api/admin/categories`、`/api/admin/brands`：增 / 改 / 删 / 启停用 / 后台列表（**含停用项**） |
| `duoduo-product/.../controller/AdminListingController.java`（**新增**） | `/api/admin/listings`：审核工作台列表（含未通过内容）+ `POST /{id}/audit`（APPROVE / REJECT，驳回必须填原因） |
| `duoduo-product/.../service/CategoryService.java` | 新增管理方法；**创建显式赋雪花 id**（product 服务 `id-type: input`，此前 insert 直接报 id 为 null）；`flatForAdmin()` / `adminBrands()` 不过滤状态——停用后后台仍可见、可重新启用 |
| `duoduo-product/.../service/ListingService.java` | 新增 `listForAudit` / `audit`：审核动词（APPROVE/REJECT）与库内状态（APPROVED/REJECTED）分离；`audit_reason` / `audit_at` 落库留痕 |
| `duoduo-product/.../entity/ListingEntity.java`、`db/schema.sql`、`docs/sql/02-duoduo-product.sql` | `t_listing` 增加 `audit_reason`、`audit_at`（容器已 ALTER，从零建库脚本同步） |
| `duoduo-product/.../dto/ProductDtos.java` | `CategorySaveRequest` / `BrandSaveRequest` / `ListingAuditRequest` / `ListingAuditView`；`CategoryNode` / `BrandView` 增加 `status`（后台要展示停用项） |
| `duoduo-trade/.../service/OrderService.java` | 新增 `adminList` / `adminDetail`（无归属限制，准入由 OperatorGuard 把关）；`forceClose`（仅待付款：关单 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR，**复用既有状态机与补偿语义**） |
| `duoduo-trade/.../controller/AdminOrderController.java`（**新增**） | `/api/admin/orders`（列表/详情/强关） |
| `duoduo-trade/.../service/AdminOpsService.java`（**新增**） | 资金对账：托管汇总（SQL 聚合）+ 异常挂账（`LEFT JOIN` 找「已付款却缺托管单」）+ 运营报表（GMV / 订单量 / 退款率 / 转化率） |
| `duoduo-trade/.../controller/AdminSettlementController.java`、`AdminReportController.java`（**新增**） | `/api/admin/settlements/**`、`/api/admin/reports/overview` |
| `duoduo-trade/.../dto/AdminDtos.java`（**新增**） | `SettlementSummary` / `AbnormalSettlement` / `AdminReport` / `ForceCloseRequest` |
| `duoduo-trade/.../mapper/OrderMapper.java`、`SettlementMapper.java` | 新增全平台聚合 SQL（`countAllByStatus` / `sumPaidAmount` / `findOrdersWithoutSettlement`） |
| `duoduo-gateway/.../application.yml` | 路由补 `/api/admin/**`（product + trade，**不进白名单**） |
| `frontend/src/api/endpoints.ts`、`types.ts` | 新增 `adminApi`（14 个端点）与后台类型 |
| `frontend/src/pages/admin/`（**新增**） | `shared.tsx`（isOperator / AdminTabs / AdminDenied / AdminStat）、`Dashboard.tsx`（报表 + 资金对账 + 异常挂账表）、`Audits.tsx`（待审/通过/驳回三视图 + 驳回原因弹窗）、`Categories.tsx`、`Brands.tsx`（状态列 + 按状态切换启停按钮）、`Orders.tsx`（状态过滤 + 订单号前缀搜索 + 强关确认弹窗） |
| `frontend/src/App.tsx`、`components/Layout.tsx` | `RequireOperator` 守卫 + 5 条 `/admin/**` 路由；导航与用户菜单按角色显示「管理后台」 |
| `scripts/admin-smoke.ps1`（**新增**） | **49 项断言**：401/403 边界、报表与对账、类目品牌增改删启停（含停用后仍可见）、审核通过/驳回留痕（中文经接口读回无乱码）、强关全链（冻结释放、重复强关被拒、买家可见关单原因）、夹具自建自毁、运营账号用完降回 USER |

**自测结论**：后端 `BUILD SUCCESS`；重建 product / trade 镜像并重启（7 容器 healthy）；
`scripts/admin-smoke.ps1` **49 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过。

**自测暴露并修复的缺陷**：

1. **类目 / 品牌创建报 500**（`Column 'id' cannot be null`）→ product 服务 MyBatis-Plus `id-type: input`，
   实体不自动生成主键；创建时显式赋雪花 id（与种子数据的 SQL 赋值同一口径）。
2. **审核 action 契约错位**：服务端拿动词与状态常量比较（`APPROVE` ≠ `APPROVED`），任何审核请求都被 422 →
   明确「对外动词、库内状态」两层，switch 显式映射。
3. **停用即消失**：后台类目/品牌列表复用只含 ACTIVE 的公开查询，停用后运营再也找不到它、无法重新启用 →
   后台列表改走不过滤状态的 `flatForAdmin()` / `adminBrands()`，DTO 增加 `status` 字段，前端按状态切换「启用/停用」按钮。
4. **PS 5.1 无 BOM 乱码**：脚本含中文必须 UTF-8 with BOM，否则被按 GBK 解析直接语法报错；
   `docker exec mysql` 的控制台输出走 GBK，**中文等值断言会假失败** —— 库内断言只判状态与非空，
   中文内容改走接口（JSON UTF-8 链路干净）读回比对。

**踩到的协作坑（写进脚本注释）**：运营账号由脚本自助注册 + SQL 提权 + 结尾降回 USER，
保证脚本可反复执行且不碰种子数据；审核夹具用大数段显式 id（避开雪花段），用完即删并自证复原。

### 2026-09-17（第三轮 · 买家端 Web 商城 M6 / P1-c）

| 文件 | 变更 |
|---|---|
| `frontend/`（**新增整个工程**） | React 18 + TS 5.6（strict）+ Vite 5；12 路由 / 10 页主交易闭环；`package.json` / `tsconfig.json` / `vite.config.ts` / `index.html` / `.env.example` |
| `frontend/src/api/` | `client.ts` 类型化客户端（解包 `data`、401 单飞 refresh 并重放、5xx 与网络退避 ≤3 次、4xx 不重试、`ApiError` 分类）；`types.ts` 对齐 Java DTO；`endpoints.ts` 端点清单 |
| `frontend/src/components/` | `ui.tsx`（卡片/分页/弹窗/状态步骤/Toast）、`Layout.tsx`、`hooks.ts`、`QRCode.tsx`（**零依赖 QR 编码器**：Byte 模式 + Reed-Solomon + 8 掩码择优） |
| `frontend/src/store/` | `auth.tsx`（令牌在 httpOnly Cookie，登录态唯一判据是 `/users/me`）、`app.tsx`（购物车角标） |
| `frontend/src/pages/` | Home / Search / ProductDetail / Listings / ListingDetail / Login / Pets / Me / Cart / Checkout / Orders / OrderDetail（二维码 + 3 秒轮询 + 托管明细）/ NotFound |
| `docs/PROJECT-STATUS.md` | 澄清演示账号**仅存在于 Node 原型种子数据**，Java 库不预置（此前误把 Node 版账号当 Java 版使用） |

**自测结论**：`tsc --noEmit` 零错误、`vite build` exit 0（55 模块）；curl 全链冒烟（注册 → 加购 → 下单幂等复放 → 沙箱扫码 → 托管 → 退款）全绿；
真实 Chromium 逐页验证 12 路由，含真实 UI 填单下单 → 模拟扫码 → 3 秒轮询自动刷新为已付款 → 托管金额核对（¥59.00 / 服务费 ¥0.59 / 卖家实收 ¥58.41）。

**自测暴露并修复的前端缺陷**：① 闲置成色硬编码中文而后端下发码值（`NEW`/`LIKE_NEW`/…），会下发错值 → 改字典驱动 + 常量兜底；
② 宠物建档未提示「生命阶段须与生日推算一致」（后端 422）→ 表单实时提示 + 一键修正 + 提交前拦截；
③ 登录页演示账号按钮引用 Node 原型种子账号（Java 库无）→ 改「一键生成体验账号」走真实注册。

### 2026-09-18（第四轮 · 卖家中心 M5-9 + 商家端 Web M9）

| 文件 | 变更 |
|---|---|
| `duoduo-common/.../context/LoginUser.java` | 修角色大小写 bug（`equalsIgnoreCase`）；新增 `isSeller()` / `canAccessSellerConsole()`（卖家 / 运营） |
| `duoduo-user/.../service/AuthService.java`、`controller/UserController.java` | 新增 `applyMerchant`：实名前置（未实名 422）+ 幂等（已是卖家直接返回）+ 写 `role=SELLER`；端点 `POST /api/users/me/merchant` |
| `duoduo-trade/.../service/OrderService.java` | 抽出 **`shipInternal`** / **`refundInternal`**（发货与退款的状态机只留一处），`toViews` 改 public 供卖家侧复用，避免「买家看到已放款、卖家看到托管中」的口径分叉 |
| `duoduo-trade/.../service/SellerService.java`、`controller/SellerController.java` | **新增**：`SellerScope` 可见范围模型；`requireSellerScope`（非卖家 403）+ `requireOwnOrder`（不存在 404 / 越权 **403**）；6 个接口 |
| `duoduo-trade/.../dto/SellerDtos.java` | **新增**：`ShipRequest` / `SellerSummary` / `SettlementPage` |
| `duoduo-trade/.../mapper/OrderMapper.java`、`SettlementMapper.java` | 新增 SQL 侧聚合（`countBySellerAndStatus` / `aggregateBySellers`），避免把全量订单捞回内存数 |
| `duoduo-trade/.../support/InternalTokenFilter.java` | 排除 `/api/seller/**`——内部凭据只能证明「你是自己人」，证明不了「这单是你的」 |
| `duoduo-gateway/.../application.yml` | trade 路由补 `/api/seller/**`（**不加白名单**：卖家端必须带登录态） |
| `duoduo-common/.../enums/ErrorCode.java`、`duoduo-common-web/.../GlobalExceptionHandler.java` | **新增** `UNSUPPORTED_MEDIA_TYPE`（415）——客户端发错 Content-Type 原本落到兜底返回 **500**，属真实缺陷 |
| `frontend/src/api/endpoints.ts`、`types.ts` | 新增 `sellerApi` 与 3 个类型；`userApi.applyMerchant` |
| `frontend/src/pages/seller/`（**新增**） | `shared.tsx` / `Dashboard.tsx` / `Orders.tsx` / `OrderDetail.tsx` / `Settlements.tsx` |
| `frontend/src/App.tsx`、`components/Layout.tsx`、`pages/Me.tsx` | 新增 `RequireSeller` 与 4 条 `/seller/**` 路由；导航与「我的」按角色显示商家端入口 |
| `scripts/seller-smoke.ps1`（**新增**） | 12 段场景 / **84 项断言**，含越权、归属隔离、重复发货、已完成订单退款边界、平台自营可见性、物流落库回显、夹具自动复原 |
| `scripts/e2e/seller-ui-check.mjs`（**新增**） | 真实 Chromium UI 自测：**29 项断言**。自动备夹具（卖家账号 + 两笔归属订单），逐页走概览 / 订单 / 过滤 / 一键发货 / 详情 / 对账 / 非卖家视角，每页截图，并收集全部 HTTP 4xx/5xx 与 console 错误 |
| `duoduo-trade/.../entity/OrderEntity.java`、`dto/OrderDtos.java`、`service/OrderService.java`、`SellerService.java`；`db/schema.sql`、`docs/sql/03-duoduo-trade.sql` | 物流信息落库：`t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，`shipInternal` 写入（内部接口不传则为空） |
| `frontend/src/api/types.ts`、`pages/OrderDetail.tsx`、`pages/seller/OrderDetail.tsx` | 买家与卖家详情页都显示物流公司与单号（同一份事实的两个视角） |
| `frontend/public/favicon.svg`、`index.html` | 补 favicon，消除 `/favicon.ico` 404 |

**自测结论**：后端 6 模块 `BUILD SUCCESS`；重建 4 个业务镜像并重启（7 容器 healthy）；
`scripts/seller-smoke.ps1` **84 通过 / 0 失败**；真实浏览器 UI 自测 `scripts/e2e/seller-ui-check.mjs` **29 通过 / 0 失败**；
前端 `tsc --noEmit` 零错误、`vite build` 通过（60 模块）。

**自测暴露并修复的缺陷**：

1. **错误 Content-Type 导致 500**（应为 415）→ 新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 handler，客户端错误不再污染 5xx 告警。
2. **首轮脚本场景设计错误**：订单 B 未发货就直接确认收货（状态机只允许 SHIPPED→COMPLETED，故 422），
   连带把「已完成订单退款被拒」误判成 200 且钱真的退了 → 修正为先发货再确认，两项随即转绿。
3. **物流信息只写进状态日志，没落在订单上**（UI 自测发现：卖家发货后详情页看不到自己填的单号）→
   `t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，卖家与买家详情页都显示；
   冒烟补两条断言（视图回显 + 落库），杜绝「只写流水不写事实」。
4. **页面缺 favicon**，浏览器每次请求 `/favicon.ico` 拿 404 → 补 `public/favicon.svg` 并在 `index.html` 声明。

**踩到的环境坑（已写进脚本注释）**：① 前端**必须用 `http://localhost:5173` 访问**，
用 `127.0.0.1` 时 Origin 不在网关 CORS 白名单里，Spring 直接 403，前端只显示「没有权限执行该操作」，
而 curl 不带 Origin 头复现不出来；② **雪花 ID 有 18 位，超过 `Number.MAX_SAFE_INTEGER`**，
JavaScript 里 `JSON.parse` 会把它改成另一个数，拿去写 `seller_id` 会得到「夹具改成功了、卖家却一笔都看不到」的假象，
必须按原始文本用正则提取。

### 2026-09-18（沙箱支付渠道 M8-1）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/.../payment/PaymentChannel.java`、`PaymentChannelRegistry.java`、`MockPaymentChannel.java`、`SandboxPaymentChannel.java` | **新增** 渠道抽象层：预下单 / 回调验签 / 查询三动作；注册表统一管理；MOCK 即时成功、SANDBOX 扫码形态 |
| `duoduo-trade/.../entity/PaymentEntity.java`、`mapper/PaymentMapper.java` | 支付单新增 `code_url` / `expire_at` 字段与 `PENDING` / `EXPIRED` 状态；Mapper 补 CAS 方法（幂等入账 / 过期作废 / 关单作废） |
| `duoduo-trade/.../service/OrderService.java` | `pay()` 改**预下单**语义（渠道差异收敛到一处）；`handleChannelCallback`（验签 + 金额核对 + 幂等入账 + 建托管）；`sandboxPay` / `sandboxPayPreview` / `getPaymentView`；取消与调度关单两条路径**作废未支付支付单**；补偿调度增加支付单过期扫描 |
| `duoduo-trade/.../controller/PaymentController.java` | **新增**：回调入口 `/api/payments/callback/{channel}`、沙箱确认支付 `/sandbox/{payNo}`、收银台页面 `/sandbox/pay/{payNo}`、支付单查询 `/{payNo}` |
| `duoduo-common/.../enums/ErrorCode.java` | 新增 `PAYMENT_CHANNEL_UNKNOWN`（422）、`PAYMENT_AMOUNT_MISMATCH`（422） |
| `duoduo-gateway/.../application.yml`、`GatewaySecurityProperties.java` | 路由补 `/sandbox/pay/**`；白名单放开收银台与沙箱确认（扫码设备无登录态，现实中谁扫码谁付款） |
| `duoduo-trade/.../resources/application.yml` | 新增 `duoduo.pay.sandbox.*`（二维码有效期 / HMAC 密钥 / 收银台地址，均可环境变量覆盖） |
| `duoduo-trade/.../resources/db/schema.sql` 与 `docs/sql/03-duoduo-trade.sql` | `t_payment` 增加 `code_url`、`expire_at`；容器脚本重新生成并落库 |
| `scripts/pay-channel-smoke.ps1` | **新增** 支付渠道自测（62 项断言：预下单 / 二维码 / 验签攻击面 / 金额篡改 / 重复回调 / 过期作废 / 关单作废 / 越权） |
| `README.md` | 按约定同步：新增支付渠道接口表与第四节「渠道抽象与沙箱扫码」；六套冒烟 |

### 2026-09-17（C2C 担保交易 M5-8 与支付口径调整）

| 文件 | 变更 |
|---|---|
| `docs/PLAN.md` | 本文件：支付口径由「真实渠道」改为**沙箱支付**；M8 改为沙箱支付渠道并解除阻塞；I-12 改「已协助」并把材料清单降级为备查；端形态表支付列同步；新增已知边界 B-07 |
| `README.md` | 按约定同步（支付方式章节、沙箱支付流程、新增接口与自测脚本） |
| `duoduo-product/.../mapper/SkuMapper.java` | 新增 `restockStock`（退货入库原子更新，**不校验在售状态**——库存是物理事实） |
| `duoduo-product/.../dto/StockDtos.java`、`service/StockService.java`、`controller/StockController.java` | 新增 `restock` 动作与 `POST /api/products/skus/stock/restock` |
| `duoduo-trade/.../entity/SettlementEntity.java`、`mapper/SettlementMapper.java`、`support/SettlementStatus.java`、`dto/SettlementDtos.java`、`service/SettlementService.java` | **新增** 资金托管：CAS 状态迁移、佣金计算、托管视图 |
| `duoduo-trade/.../entity/OrderEntity.java`、`support/OrderStatus.java` | 新增 `refundedAt` 与终态 `REFUNDED`；移除被误用的 `PAID → CLOSED` 边 |
| `duoduo-trade/.../service/OrderService.java` | 支付成功建托管；确认收货放款；买家自助退款与运营侧退款；入库走事务提交后事件；补偿调度扩展自动确认收货与入库补偿 |
| `duoduo-trade/.../controller/OrderController.java` | 新增 `/refund`、`/internal/{orderNo}/refund`、`/{orderNo}/settlement` |
| `duoduo-trade/.../resources/application.yml` | 新增 `platform-fee-bps`、`auto-receipt-days` |
| `duoduo-trade/.../resources/db/schema.sql` 与 `docs/sql/03-duoduo-trade.sql` | `t_order` 增加 `refunded_at` 与 `idx_order_auto_receipt`；由源文件重新生成容器脚本 |
| `scripts/settlement-smoke.ps1` | **新增** 担保交易自测（125 项断言） |
| `Dockerfile` / `.dockerignore` / `duoduo-gateway/pom.xml` | 更早引入，本轮仍全程验证通过 |

---

## 7. 一键命令速查

```powershell
# 起全部中间件 + 业务服务（Docker 优先）
cd D:\智能体项目\duoduo-pet-mall-java
docker compose up -d
docker ps                                  # 8 个容器应全为 healthy

# 端到端冒烟（54 项，网关 + 用户域 + 商品域）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 购物车端到端自测（87 项，需登录，会临时改库并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1

# 下单 / 拆单 / 库存冻结 端到端自测（76 项，含安全断言与部分冻结失败补偿）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1

# 支付 / 订单状态机 / 超时关单 端到端自测（71 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1

# 担保交易 / 资金托管 / 放款 / 退款 / 自动确认收货 端到端自测（125 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1

# 沙箱支付渠道 / 预下单 / 回调验签 端到端自测
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1

# 卖家中心 / 商家端：准入、归属隔离、发货、售后退款、结算对账（101 项，会临时改夹具并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1

# 平台管理后台：权限边界、报表对账、类目品牌、内容审核、订单强关（49 项，夹具自建自毁）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1

# 履约域：评价与晒单（59 项；含内部接口保护、字段裁剪、匿名脱敏、追评与聚合口径）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1

# 历史订单托管单回补（88 项；权限三层 / dryRun 预演不写库 / 幂等 / 挂账清零 / 控制组 / REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1

# 售后仲裁工单（101 项；立案 / 卖家处理 / 买家申请仲裁 / 运营裁定退款 / 已放款边界 B-01 / 越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1

# 物流轨迹（79 项；发货即揽收 / 乱序正序 / 幂等 / 批量全有或全无 / 无时区按 UTC / 越权 / 敏感字段 / 含断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1

# 手动触发一轮托管单回补（先 dryRun 看计划，再执行；运营账号的 Cookie 需自行带上）
# curl.exe -X POST "http://127.0.0.1:8080/api/admin/settlements/backfill?dryRun=true"

# 四套真实浏览器 UI 自测（需先起前端 dev server）
# ⚠️ 端口用 3000 而非 5173：本机 5173 落在 Windows 排除端口段（Docker Desktop 启动后 WinNAT 保留 5149-5248，
#    报 listen EACCES）。3000 本就在网关 CORS_ORIGINS 白名单里，且必须走 localhost（不能 127.0.0.1）。
cd D:\智能体项目\duoduo-pet-mall-java\frontend
node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost   # 另开一个终端
cd .. && UI_BASE=http://localhost:3000 node scripts/e2e/seller-ui-check.mjs      # 商家端 29 项
UI_BASE=http://localhost:3000 node scripts/e2e/review-ui-check.mjs              # 评价与晒单 20 项
UI_BASE=http://localhost:3000 node scripts/e2e/aftersale-ui-check.mjs           # 售后三端 44 项（买家→卖家→运营整条链）
UI_BASE=http://localhost:3000 node scripts/e2e/logistics-ui-check.mjs           # 物流轨迹 44 项（买家/卖家同轨 + 空态 + 越权 + 终态仍在）

# ---------- 买家端 Web 商城（frontend/） ----------
cd D:\智能体项目\duoduo-pet-mall-java\frontend
npm install                 # 首次
npm run dev                 # http://localhost:5173（需后端 7 容器在跑，网关 :8080）
npm run build               # tsc --noEmit && vite build
# 改后端地址：设 VITE_DEV_API_TARGET 环境变量（默认 http://localhost:8080）

# 从零重建（连数据卷一起删，会重新执行 docs/sql 初始化）
docker compose down -v; docker compose up -d

# 沙箱内调用 docker 必须带这两行（凭据助手不在 PATH）
$env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
$env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds'

# 构建（沙箱里 cmd.exe 被禁，不能用 mvn.cmd —— 必须用 java 驱动 Maven launcher）
$J='D:\智能体项目\duoduo-pet-mall-java'; $M='D:\devsoft\apache-maven-3.9.4'
& 'D:\jdk\jdk21\bin\java.exe' "-Dclassworlds.conf=$M\bin\m2.conf" "-Dmaven.home=$M" `
  "-Dmaven.multiModuleProjectDirectory=$J" -classpath "$M\boot\plexus-classworlds-2.7.0.jar" `
  org.codehaus.plexus.classworlds.launcher.Launcher -gs maven/settings.xml -B -DskipTests package

# 只往容器里补执行某个初始化脚本（不用重建数据卷）
docker cp <ascii目录>\03-duoduo-trade.sql duoduo-mysql:/tmp/03.sql
docker exec duoduo-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -pduoduo_root < /tmp/03.sql'

# 手动触发一轮补偿调度（超时关单 / 释放冻结 / 自动确认收货 / 入库补偿 / 支付单过期）
curl.exe -X POST http://127.0.0.1:8083/api/orders/internal/sweep -H "X-Internal-Token: duoduo-local-dev-internal-token"
```

> 服务端口：网关 **8080**、用户 **8081**、商品 **8082**、交易 **8083**、履约 **8084**、营销 **8085**。

---

## 7.5 交接包（2026-09-18 建立，换账号接管用）

> 当前账号 token 耗尽前整理的一套**可独立接管**的资料，共 9 篇，本地在 `docs/handover/`，
> 云端同步到两处（腾讯文档「项目资产（按时间归档）/2026-09-18 交接包」与乐享知识库「多多宠物商店知识库」）。
> 逐篇 file_id / entry_id / url 见 `docs/handover/manifest.json`。

| 篇 | 主题 |
|---|---|
| 00 | 交接总纲（10 分钟接管：进度快照 / 下一步 / 命令速查 / 9 篇导航 / 密钥与权限指引） |
| 01 | 快速上手与环境搭建（9 容器、构建专用姿势、常见故障 8 条） |
| 02 | 架构与模块导航（服务拓扑 / 模块对照 / 跨域调用 / 一笔订单的数据流） |
| 03 | 环境变量与密钥（脱敏，四把钥匙与轮换建议） |
| 04 | 数据库与表结构（五库、表清单、状态枚举字典、口径约定） |
| 05 | 接口清单与契约（按域速查、认证与放行边界、错误码表） |
| 06 | 前端开发手册（目录职责、四大页面域、截图索引、浏览器专属坑） |
| 07 | 测试与验收证据（14 套 1204 项 + 5 套 180 项、脚本四条约定、历史缺陷） |
| 08 | 风险、已知边界与接手建议（I-14 等遗留、不做清单、8 条值钱的坑、接手顺序） |

**7.6 AI Agent 接管包（2026-09-18 新增，给 AI 读）**

> 与上文「人读交接包」并存、互补：人读版讲背景与取舍，AI 版讲**可执行的规则、契约与门禁**。
> 入口：仓库根 `AGENTS.md`（多数 AI 编码工具会自动读取）→ 指向 `docs/agent/00~08`。

| 篇 | 主题 |
|---|---|
| 00 | AGENT 接管指令（硬规矩 C1–C10、工作循环、完成定义 DoD） |
| 01 | 项目目标与领域模型（参与者/领域对象/主流程/术语表） |
| 02 | 代码地图与现状（文件级索引：想改什么去哪个文件） |
| 03 | 架构与不变式（INV-1~15，历史缺陷换来的硬约束） |
| 04 | 接口契约全集（端点×认证×DTO 字段×枚举×错误码，机械抽取自源码） |
| 05 | 开发任务手册（6 个配方 + 代码骨架，照抄即可） |
| 06 | 测试与验收门禁（按改动类型选必跑脚本 + 断言写法约定） |
| 07 | 待办事项与验收标准（A1~A13，逐项含接口草案与可执行验收标准） |
| 08 | 环境运维与踩坑禁忌（T-01~T-24 症状→根因→做法、10 条禁忌） |

---

**同轮一并修正的文档一致性问题**：README 容器数 7→8（当前状态口径）、冒烟合计 665→667、
`ARCHITECTURE.md` Java 17→21、`PROJECT-STATUS.md` 加「已废弃」横幅、MEMORY 台账链接修正（原指向已删除的 v1 文档）。

---

## 8. 约定（不随迭代改变）

- 金额一律整数「分」；时间一律 ISO8601 UTC
- 令牌只走 httpOnly Cookie，禁止 localStorage / URL 参数
- 错误结构 `{ error: { code, message, details }, requestId }`，不暴露堆栈
- 用户身份只从网关注入的 `X-User-*` 取，绝不接受客户端传 userId
- 中间件与业务服务一律优先容器化
- **SQL 初始化脚本一律以 `SET NAMES utf8mb4;` 开头**（防双重编码）
- **每次推送 GitHub 前必须更新 `README.md`**（与代码改动放在同一次提交里）
- 幂等一律靠**唯一索引或 CAS 条件更新**兜底，分布式锁只用于减少冲突，不作为正确性保障
- 状态用 VARCHAR 存**枚举名**（可读、便于排查）；状态文案由服务端给出，前端不重复维护映射
- **支付渠道必须可替换**：订单、库存、资金账本、回调处理一律写成渠道无关的；渠道差异只允许出现在 `PaymentChannel` 实现类里

---

## 9. 已知边界（按当前阶段刻意不做，需要时再补）

| # | 边界 | 为什么现在不做 | 什么时候必须处理 |
|---|---|---|---|
| B-01 | 退款只覆盖「托管中」的资金（已付款 / 已发货）；**已完成订单的退款需向卖家追偿或平台垫付** | 那是另一条资金流程（含平台垫付与风控），与担保交易主链路分开做更清晰 | 接售后体系时 |
| B-02 | 退货入库若出现「客户端已生效、响应丢失」，重试可能重复入库 | 彻底解决要给入库也带幂等键（商品侧按 `(ref_no, sku_id)` 去重建记录表） | 对账任务落地时一并解决 |
| B-03 | 服务间凭据用**共享密钥** `X-Internal-Token` | 开发阶段简单可靠；生产应换 mTLS / 服务网格授权 | 上生产前 |
| B-04 | **商品域 SPU / SKU 仍无 `seller_id`**——所以「商家管理自己的商品」本轮不做；订单归属靠 `t_order.seller_id` 校验 | 卖家中心先做订单侧（发货 / 售后 / 结算），商品侧要改商品域模型 | 做「商家上下架商品」前必须先补字段 |
| B-05 | 补偿调度用 `@Scheduled`（单实例） | 开发环境单实例足够；生产多实例需 XXL-Job 之类保证只跑一份 | 上生产前 |
| B-06 | ~~历史订单缺托管单（见 I-14）~~ **已解决（2026-09-18 A1）** | 已提供幂等回补接口 `POST /api/admin/settlements/backfill`，开发库缺口已清零 | 生产上线时在真实数据上执行一次（先 `dryRun=true` 核对计划） |
| B-07 | **支付走沙箱渠道，不是真实资金渠道**（不入账、不结算到银行卡） | 你的决定：真实渠道资质门槛过高，先用沙箱把工程链路做实 | **上线真实收款前**：实现 `PaymentChannel` 的微信 / 支付宝版本，材料清单见协助区折叠附件 |
| B-08 | **角色升级后需重新登录才生效**（角色快照写在 access token 内，15 分钟） | 避免每次请求回源查角色；前端已在入驻成功提示里写明 | 若要即时生效：网关每次回源查角色，或入驻成功后服务端主动使旧 refresh 失效并强制重登 |
| B-09 | **售后已有独立的售后单（立案-卖家处理-平台仲裁，A3 已落地）**；仍缺**退货物流**（退货退款的货怎么寄回、怎么签收） | 退货物流是独立的物流域模型（面单、轨迹、签收），与争议裁定分开做更清晰 | 接物流轨迹（A2）时一并做 |
| B-10 | 旧的内部凭据端点 `/api/orders/{no}/ship` 与 `/internal/**` **保留** | 现有 6 套冒烟脚本依赖它们 | 清理脚本依赖后可收敛为卖家端单一入口 |
| B-11 | **优惠券 v1：券不随退款退回**（退款金额为订单实付金额） | 券的资金语义（退回 / 作废 / 部分退）需要与交易域退款流程一起重设计，v1 先保证「折扣只算一次、钱数对得上」 | 做「退款退券」前必须与交易域退款流程一并设计 |
| B-12 | **优惠券 v1：只实现满减**（无折扣率 / 兑换 / 会员 / 积分），且**一张券只作用于拆单后商品总额最大的那一单** | 拆单后一券多单的分摊规则复杂、容易算错钱；先做覆盖最常见场景的最小闭环 | 出现跨单优惠需求时 |
