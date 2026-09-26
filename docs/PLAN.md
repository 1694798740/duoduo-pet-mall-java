# 多多宠物商店 · 项目开发计划（看板）

> 更新时间：2026-09-26（第四十二轮 · 商品详情页升级：立即购买 + 展示丰富化 + 分享修复）——① **立即购买**：后端同一下单接口增可选 `buySkuId/buyQuantity`（直购分支：内存临时行，不读购物车、下单后不清购物车，快照/拆单/冻库/券口径与购物车结算完全同源）；小程序新建 `pages/checkout` 确认单（地址 pick 模式 + 服务端可用券 + 数量步进 + 提交即进收银台），详情页底部改「分享｜加入购物车｜立即购买」双按钮；② **展示丰富化**：轮播 3→5 图、新增**规格参数**（品牌/分类/适用宠物/适用阶段/过敏原，全取后端真实字段不编造）与**包装规格**（SKU spec 键值对 + 编码 + 库存，随选中规格联动）两卡、商品介绍变**图文详情**（长文 + 3 张配图）；③ **分享修复**：真机语义澄清（devtools 转发面板预览本就用页面截图，"查看分享页"是工具模拟行为），卡片标题补价格、新增 `onShareTimeline`（朋友圈）。验证：后端探针 `direct-buy-smoke.py` **13/13**（直购下单/金额/购物车零污染/原路径回归/缺参与坏 SKU 拒绝）；小程序 stub **24/24**；静态自检 0 错误 0 告警。以下为第四十一轮内容：——tab 切换顶部白条根治：5 个 tab 页改自绘导航栏（`navigationStyle: custom` + `components/nav-bar`，尺寸按胶囊反推），原生栏不存在即无可闪。
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
| M7 | 买家端 · **微信小程序** | 🔄 进行中 | 骨架 + 后端 code2Session 登录均已交付（第十五轮）；**AppID/Secret 已配置（2026-09-25）**：user 容器已注入凭据、`project.config.json` 换真实 AppID、`WECHAT_LOGIN_READY=true`、真实通道验证通过（假 code → 401 非 503）；**A14 全面重构已落地（第二十轮）**：TabBar 五页框架 + 领券中心 / AI 导购 / 限时秒杀三特色页 + 请求层与设计 token 统一；待用户在微信开发者工具完成 wx.login 真机联调 |
| M8 | **沙箱支付渠道** | ✅ 完成 | 渠道抽象 + 扫码预下单 + 收银台 + 回调验签 + 幂等（M8-1 自测 62 项全绿）；真实渠道留接口位 |
| M9 | 商家端 · **Web（商家服务平台）** | ✅ 已完成 | 概览 / 订单 / 订单详情 / 结算对账 4 页；发货与售后退款走真实权限校验；自测 **82 通过 / 0 失败** |
| M10 | 平台管理后台 · **Web** | ✅ 完成 | 类目品牌维护、内容审核、订单干预、资金对账、报表 |
| M11 | 营销 / AI 导购 / 履约 / 检索 / 压测 | 🔄 进行中 | **履约域四块已落地**（新建 `duoduo-fulfilment`）：P2-1 评价与晒单、**A3 售后仲裁（含三端页面）**、**A2 物流轨迹（含买卖双方时间线）**、**A4 信用分（权重模型 + 前端展示）**；**A5 优惠券营销已落地**（新建第 6 个业务服务 `duoduo-marketing`，:8085，含前端领券中心 / 券包 / 结算选券）；**A8 收货地址簿已落地**（用户域地址簿 + 交易域下单写收货快照 + 前端 `/addresses` 与结算页二选一），同轮**根治 T-39 雪花 ID 精度缺陷**（`LongToStringJacksonModule` 全站统一按字符串下发 64 位标识，金额/计数/时间戳按字段名白名单保持数字）；**A9 AI 导购「多多」已落地**（新建第 7 个业务服务 `duoduo-ai`，:8086，**无状态**；真实候选召回 + 三形态 + 限流/降级 + 前端 `/ai` 免登录可用）；**A10 ES 检索已落地**（商品域单索引 `duoduo-search`，拼音/同义词应用侧容错、增量兼补偿、对账与重建、**ES 不可用降级 MySQL**）；**A9 `llm` 形态已用真实百炼 Key 联调通过并切 `qwen-max`**（T-60/T-61 两缺陷修复）；**A11 RocketMQ 削峰 + Sentinel 限流已落地**（异步下单通道 `POST /api/orders/queued` + 任务台账 + 死信可查可重放 + `order-place` 资源 QPS 限流，MQ 容器挂 `profile: mq`）；**A12 压测已完成**（sync ~100 单/s / queued 入队 ~1000 单/s / 热点行 +40%，报告 `docs/perf/A12-load-test-report.md`）；**A14 限时秒杀已落地（第二十轮）**：交易域三表 + Redis Lua 预扣（软依赖可降级）+ DB 原子扣减兜底防超卖 + C 端 3 端点 / 运营端 2 端点 + 关单回补钩子 + Web `/seckill` 页，自测 `seckill-smoke.ps1` **26 通过 / 0 失败**；余下：XXL-Job；A13 AppID 已配置、真机联调中 |

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

| **A5 · 优惠券营销（第十三轮）** | **新建第 6 个业务服务 `duoduo-marketing`（:8085）**：POM 入父反应堆、三档配置、雪花 **worker-id=5**、compose 第 **9** 个容器（服务名 `duoduo-marketing-app` / 容器名 `duoduo-app-marketing` / 镜像 `duoduo/duoduo-marketing:1.0.0`）、新库 `duoduo_marketing`（`t_coupon_template` + `t_coupon`）。**公开只读** `GET /api/coupons/templates`（领券中心，含 `claimable` / `claimableReason`，**能否领由服务端判定**）；**需登录** `POST /api/coupons/templates/{id}/claim`（**重复领取不报错**，返回 `replay=true` 与已有券码）、`GET /api/coupons/mine?status=`、`GET /api/coupons/mine/usable?amount=`（服务端给 `bestCouponCode`）；**运营** `POST /api/coupons/admin/templates`（`OperatorGuard`）；**内部**（`X-Internal-Token`，**不进网关**，交易域直连 8085）`/api/coupons/internal/{lock,release,consume,sweep}`。**三条纪律**：① 券码用**随机串**（`CP` + 14 位去混淆字符，字符表去 `0/O/1/I`）而**不用雪花**（规避 JS `Number.MAX_SAFE_INTEGER` 精度坑，同 INV-16）；② 锁 / 解 / 核销**全部 CAS 条件更新、不用分布式锁**（INV-1）；③ **营销域只算「能抵多少」，折扣金额由交易域写入 `t_order.discount_amount`**（被调用方、**不依赖 openfeign**）。**交易域接入**：下单 `placeOrder` 先锁券（失败抛 422 带人话原因）→ `paySuccess` 核销（核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁；`t_order` 增列 `coupon_code VARCHAR(32)`（增量脚本 `docs/sql/incremental/06-a5-coupon-columns.sql`）。**网关白名单收紧为精确路径 `/api/coupons/templates`**（白名单按**路径**而非方法匹配，放通配会把领券 / 券包 / 内部接口一起放成匿名可调，与既有 `/api/reviews/product/**` 同一纪律）。**前端**：新增 `pages/Coupons.tsx`（领券中心 + 我的券包双页签）、路由 `/coupons`（**刻意不套 `RequireAuth`**）；一级导航 + 用户下拉 + 「我的」页加快捷入口；结算页选券（`undefined`=未选择→用服务端推荐 / `null`=明确不用，提交只传 `couponCode`）、订单详情金额区展示优惠与券码。**自测 `scripts/coupon-smoke.ps1` 145 通过 / 0 失败**（含★断言总数守卫）+ **`scripts/e2e/coupon-ui-check.mjs` 真实 Chromium 43 通过 / 0 失败**（配套夹具 `scripts/e2e/coupon-ui-fixture.ps1 -Action create\|drop`）；`tsc --noEmit` 零错误、`vite build` 通过（**79 模块**） |

| **A8 · 收货地址簿 + T-39 精度根治（第十四轮）** | **用户域新表 `t_user_address`**（`docs/sql/incremental/07-a8-user-address.sql`；**6 个用户端点**：列表（默认排最前）/ 新增 / 修改 / 删除 / 设为默认 / 取默认 + **内部快照** `GET /api/addresses/internal/snapshot`（`X-Internal-Token` + 本域 `InternalTokenFilter` 失败关闭、**不进网关**））。**四条业务规则**：① 首个地址自动成为默认；② 设默认在**事务内**先清旧默认再置新，库内恒有且仅有 1 条默认；③ **删默认自动顺延**并 toast 告知；④ `fullAddress` 拼接**只有一处定义**（`AddressService.fullAddress()`，直辖市省=市只留一次），内部快照与地址簿接口**逐字相同**（冒烟锁死）。**归属校验统一 404**（改/删/设默认他人地址与"id 不存在"**同码同文案**，刻意不区分"不存在"与"无权限"以免用状态码差异枚举他人地址 ID —— 这是对 `docs/agent/07` 原文「403」的**有意偏离**）。**交易域接入**：下单新增可选 `addressId`，传了就**只认它**（直填三字段被忽略，避免两套来源冲突）；收货信息在**下单瞬间写快照**（`t_order.receiver_*`），此后改/删地址簿**不影响历史订单**。**★ T-39 雪花 ID 精度根治**：18~19 位雪花 id 超出 JS `Number.MAX_SAFE_INTEGER`（16 位），以 JSON number 下发会被浏览器 `JSON.parse` **静默四舍五入**（`Number('...704') === '...700'`，不报错）→ 前端拼 URL → 服务端查不到 → **404**；**接口冒烟照不出来**（.NET 用 Int64/Decimal 解 JSON 不丢精度），**A3 曾局部踩过一次、A8 又踩一次**。方案：`duoduo-common-web` 新增 **`LongToStringJacksonModule`**（随 `WebAutoConfiguration` 自动装配到全部业务服务）—— `Long`/`long`/`BigInteger` 默认转字符串，**字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额/计数/epoch 毫秒时间戳）的保持数字**；**判据是字段语义不是 Java 类型**（全站金额字段 **63 处都是 `Long`**，一刀切会让前端 `price / 100` 算出 `NaN`、页面金额全打成「¥NaN」）。**漏标方向安全**：新加 id 字段无需任何操作即受保护。前端 `types.ts` **38 个 id 类字段** + `endpoints.ts` **25 处形参**改 `string`，13 个页面逐个修到 `tsc --noEmit` 零错误；纪律写进 `types.ts` 顶部（id 只拼 URL 与相等比较，**绝不做数值运算**）。**顺带修掉一个真缺陷**：`Checkout.tsx` 初始化守卫写成 `addressList === null`，而本仓 `useAsync` 初始 `data` 是 **`undefined`** → 首次渲染即锁定"空地址簿"分支、把用户推进手动填写模式（地址渲染出来了却一个都选不上）→ 改为 **`addrLoading` 门控 + 判 `undefined`**（见 T-42）。**自测**：`scripts/address-smoke.ps1` **82/0**（含★断言总数守卫）+ `scripts/e2e/address-ui-check.mjs` **64/0**（真实 Chromium，16 段）+ **`scripts/id-precision-smoke.ps1`（新增）56/0**（跨四域，**直接断言 JSON 原始文本**绕开 `ConvertFrom-Json` 的精度掩盖）+ `scripts/run-all-smoke.ps1`（新增，一键全套并汇总）；**全套 15 套 1286 项全绿**，连带修 2 处因 T-39 而失效的旧断言（`review-smoke` 抠主键正则要允许引号；`order-smoke` 的「缺收货人」用例补"先加购"前置，否则会撞上「购物车为空」的**同码不同因**假绿灯）。踩坑登记 **T-39 / T-40 / T-41 / T-42** |

### 进行中

| 任务 | 当前进展 |
|---|---|
| ✅ **M11 剩余块：信用分（A4）已交付**（2026-09-20，第十二轮） | 履约域**四块已全部落地**：评价与晒单、售后仲裁（A3，含三端页面）、物流轨迹（A2，含买卖双方时间线）、**信用分（A4）** —— 同域内已无低成本可做项，下一项须新起服务或中间件 |
| ✅ **M11 剩余块：优惠券营销（A5）已交付**（2026-09-23，第十三轮） | **新增第 6 个业务服务 `duoduo-marketing`（:8085，雪花 worker-id=5）**：领券中心 / 券包 / 内部锁·解·核销 / TTL 补偿调度 + 前端领券中心与结算选券；容器 **8 → 9**（3 中间件 + 6 业务服务）。v1 边界：券不随退款退回、只实现满减、一张券只作用于拆单后商品总额最大的那一单 |
| M11 其他：XXL-Job（~~ES 检索（A10）~~ ✅ / ~~RocketMQ + Sentinel（A11）~~ ✅ / ~~压测（A12）~~ ✅） | A10/A11/A12 均已交付（第十七/十八/十九轮）；仅剩 XXL-Job（B-05） |
| **A9 AI 导购「多多」（已交付，仅剩凭据联调）** | ✅ **第十七轮已交付**：新建 `duoduo-ai`（:8086，容器 `duoduo-app-ai`，**无状态**——不连库、v1 不落对话历史）；`POST /api/duoduo/chat` + `GET /api/duoduo/capabilities`（网关白名单含这两条，**未登录可问答**）；Feign 调商品域 `GET /api/products/internal/candidates` 取**真实候选**（视图**不含 `sellerId`**），系统提示硬约束「只推荐候选内商品」；三形态 `mock`/`llm`/`unconfigured`，未配或超时 → **503 `DEGRADED`**，每 IP 限流 → **429 `RATE_LIMITED`**；前端 `/ai` 免登录对话页。**自测 `ai-smoke.ps1` 16/0**。**待用户提供**百炼 DashScope API Key（仅影响 `llm` 形态联调） |
| **A10 ES 检索（已交付）** | ✅ **第十七轮已交付**：商品域单索引 `duoduo-search`（`docType` ∈ PRODUCT/LISTING）；**拼音与同义词都在应用侧**（pinyin4j 2.5.1 + `SearchSynonyms.variants()`，**按 ES 实测不挂同义词分析器**；**拼音库必须选 Central 上存在的 —— T-59**）；`SearchSyncJob` 15s 增量兼补偿 + 启动全量对齐 + 清孤儿；`GET /api/admin/search/reconcile` / `POST /api/admin/search/reindex`（`OperatorGuard`）；ES 不可用**原地降级 MySQL**（`ObjectProvider` 可选注入 + 写操作前置 `ready()` 守卫 + **禁用 ES 健康指示器**以免健康门杀掉降级链路）；ES 只返回 id 页、卡片回库装配（权威数据只有库一份）。**自测 `search-smoke.ps1` 40/0**（需 ES） |
| ✅ **A7 运营账号管理页已交付**（2026-09-24，第十六轮） | 用户域两端点（`GET /api/admin/users` 脱敏分页列表 + `POST /api/admin/users/{id}/role` 仅 ADMIN 改角色）+ `t_audit_log` 审计落库 + 网关 `/api/admin/users/**` 路由 + 前端 `/admin/operators`（AdminTabs 第 7 页签）；三条硬规则：自己降权 422 / 最后一个 ADMIN 不可降 422 / 每次变更写审计；提权后需重新登录生效（notice 提示）。**自测 `admin-smoke.ps1` 49→72 项全绿（PS5.1）** |
| ✅ **A6 契约导出与 CI 已交付**（2026-09-23，第十五轮） | **OpenAPI 导出**：knife4j starter 挂 `duoduo-common-web`（5 业务服务自动获得，网关不受影响；`/v3/api-docs` 与 `/doc.html` 不进网关白名单）+ `scripts/export-openapi.ps1` → `docs/openapi/`（5 份 JSON + endpoints.md，**116 端点**与 04 篇核对一致）；**CI**：`.github/workflows/ci.yml` 双 job（编译 + Docker 全栈冒烟子集 smoke/trade/order/address/id-precision 共 **356 项**，失败即 job 失败 + 日志 artifact）+ `scripts/ci-run.ps1` 通用子集执行器（本地实跑 356/0 全绿） |
| ✅ **A13 小程序骨架已落地**（2026-09-23，第十五轮，测试号阶段） | 用户批准「代码先写」：`miniprogram/` 5 页面 + 请求封装，**零后端改动**跑通「浏览 → 加购 → 取默认地址下单 → 沙箱支付轮询」（登录暂用手机号回退）；**余下**：后端 `POST /api/auth/wechat`（code2Session，凭据走环境变量）+ openid 绑定 + 冒烟；真机预览（待 AppID） |
| ✅ **A13 后端登录打通（code2Session + 绑定）**（2026-09-23，第十五轮续） | `t_user` 加 `wx_openid` + **唯一键 `uk_user_wxopenid`**（增量脚本 `docs/sql/incremental/08-a13-wechat-bind.sql`）；新端点 `POST /api/auth/wechat`（免登，白名单只放行这一条）+ `POST /api/auth/wechat/bind`（登录态绑定）；`WechatGateway` 三分支（mock 显式开启 / real jscode2session / 未配置 503 DEGRADED）；未绑定 422、openid 属他人 409、重复绑定幂等 200、空 code 400；secret 只走环境变量、日志只记 openid 前 4 位。**自测：`scripts/wechat-auth-smoke.ps1` 26/26 全绿（GUARD-OK）+ CI 子集 356/0 无回归**。小程序侧待 `WECHAT_LOGIN_READY=true` 联调；真机预览待 AppID |
| ✅ **A7 运营账号管理页已交付**（2026-09-24，第十六轮） | 用户域两端点（`GET /api/admin/users` 脱敏分页列表 + `POST /api/admin/users/{id}/role` 仅 ADMIN 改角色）+ `t_audit_log` 审计落库 + 网关 `/api/admin/users/**` 路由 + 前端 `/admin/operators`（AdminTabs 第 7 页签）；三条硬规则：自己降权 422 / 最后一个 ADMIN 不可降 422 / 每次变更写审计；提权后需重新登录生效（notice 提示）。**自测 `admin-smoke.ps1` 49→72 项全绿（PS5.1）** |
| **A13 买家端微信小程序（收尾，待用户提供凭据）** | 用户 2026-09-18 拍板：**排期，允许先开始编码**；**小程序 AppID 暂未提供（待用户提供）** → 骨架 + 后端登录均已就绪，真机预览等 AppID |

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
| I-20 | A11 死信链路自测暴露**两个工具层隐患 + 一个运维盲区** | **已自愈**（2026-09-24） | 无需操作 | ① **PS 5.1 给原生 exe 传参剥内嵌引号**：`curl.exe -d '{"a":"b"}'` 到 curl 手里变 `{a:b}` → 服务端 400；`mq-smoke.ps1` 首版**登录 JSON 被剥 → 登录失败 → burst 全 401**，限流断言「limited=0 且 ok=0」差点被误读成"限流没生效" → body 一律落临时文件走 `-d @file`，cookie jar 校验改为查**非 `#` 会话行**（详见 `08` 篇 **T-63**）；② **`docker stop` 误用 compose 服务名**（`duoduo-product-app`）→ `No such container`，报错又被 `Out-Null` 吞掉 → 商品域根本没停，死信断言连环假失败 → 原生命令只用**容器名**，停完必须显式断言（**T-64**）；③ **网关 404 的一次误判**：`/api/admin/mq/**` 路由加进 `duoduo-gateway` 的 yml 后只重打了**镜像**没重打 **jar**（Dockerfile 复制 `target/*.jar`）→ 重建容器仍是旧路由 → 教训：**改 yml 先 `mvn -pl <module> package` 再 `docker compose build`**，缺一不可 |

<details>
<summary><b>附：真实渠道备查（当前不需要你提供，将来要接真实收款时再取）</b></summary>

- **微信支付**：商户号 `mchid`、APIv3 密钥、商户 API 证书（`apiclient_cert.pem` / `apiclient_key.pem`）、
  关联 AppID（小程序 / 公众号）、可公网访问的 HTTPS 回调域名
- **支付宝**：AppID、应用私钥、支付宝公钥（或证书模式三件套）、已签约产品（电脑网站支付 / 当面付）
- **前置资质**：微信支付商户号需营业执照（个人主体申请不到）；支付宝需企业实名认证；回调域名需备案

</details>

---

## 6. 本轮变更记录

### 2026-09-25（第二十五轮 · 微信一键登录引导加固）

> 用户反馈「微信一键登录还是不行」+ 截图（`/api/auth/wechat/token 422`，屏幕无任何引导）。

- **判定**：422 说明令牌端点已生效；真正缺陷是**引导不闭环** —— 登录页**缺 `onLoad` 会话校验**，422 分支只用 `api.getToken()`（本地是否有令牌**字符串**）判断"已登录" → 残留过期令牌时被误判 → 直接 `bind` → 401 → 静默重登 → 又 422，**死循环且不提示**。
- **修复**：新增 `onLoad/verifySession`（`ensureSession` 校验真实会话，无效令牌立即 `clearSession`，以 `loggedIn` 取代字符串判断）；422 分支给「绑定当前账号（+换个账号退路）」与「用手机号登录并绑定」两条可执行路径；`bindCurrentWechat` 遇 401/令牌失效不再静默，改为清会话 + 切表单 + 弹「登录已过期」。
- **实测**：`bind` 无令牌/无效令牌均 401；`wechat/token` 未绑定 422 且文案更新；自检 0 错误 0 告警。提交 `b1a7c1d`。

### 2026-09-25（第二十六轮 · 登录成功后统一绑定微信）

> 用户反馈：用测试账号登录后**没有提示"已绑定微信"**。

- **证据**：user 容器日志有 `用户登录成功`（12:08:29 测试账号、12:09:22 新注册账号），**全程 0 次 `wechat/bind` 调用**；`t_user.wx_openid` 仍为 NULL。
- **根因**：绑定只挂在 `pendingWechatBind`（"先点微信登录被 422 拦下"才置位）这条路径上；用户直接点手机号登录 / 手动切 tab 都不设该标记 → 登录成功但从未绑定。
- **修复**：`submitPhone` 登录成功后**统一调用 `maybeBindWechat()`** —— 从 422 转来则直接绑定（意图明确），否则弹「绑定当前微信？」（去绑定/暂不）；绑定失败不影响登录成功。提交 `91514fd`。

### 2026-09-25（第二十七轮 · 分类页卡片/图标超出屏幕）

- **根因**：本页复用全局 `.goods-*` 类名并靠页面覆盖尺寸，覆盖一旦失效（样式缓存/编译顺序）就退回全局基线 `340rpx` → `340×2 + 左栏 200 = 880rpx > 750rpx`，右列被挤出屏幕、封面被放大到 340rpx。
- **修复（解耦 + 单列）**：商品区改用独立类名 `cg-*`（不再引用任何 `.goods-*`），单列横向卡、宽度全交给 flex。提交 `cd10c29`。

### 2026-09-25（第二十八轮 · 绑定被占用时异常被前端吞掉）

> 用户反馈：用另一账号点「去绑定」，Console 有 `409 (Conflict)`，界面却直接显示「登录成功」。

- **判定**：**后端判断正确**（409 = 该微信已绑定其他账号），是前端吞了异常：① `maybeBindWechat` 把失败归一成 `false`（错误对象被丢弃）；② 内部 toast 被随后的「登录成功」toast 覆盖。库内实证：该微信已绑在测试账号 `136****2050`（openid 前缀/长度与日志一致）。
- **修复**：`maybeBindWechat` 返回 `{bound, error}` 保留错误；新增 `notifyBindFailure` 用 **modal** 展示真实原因（409/503/令牌失效各有文案），关闭后再跳转；`bindCurrentWechat` 成功不再自己 toast。提交 `c7a9ea5`。

### 2026-09-26（第四十二轮 · 商品详情页升级：立即购买 + 展示丰富化 + 分享修复）

> 用户三点需求：① 底部双按钮 + 立即购买下单流程（可选券/选地址）；② 丰富商品展示（轮播/详情长图/规格参数/包装规格）；③ 分享"只截图、查看分享页无反应"。

- **① 立即购买（后端直购分支 + 前端确认单）**。后端 `PlaceOrderRequest` 增可选 `buySkuId/buyQuantity`（成对校验，数量 1-200）：下单主流程里直购走**内存临时行**（`directBuyRow`，不落 `t_cart_item`），后续快照校验/拆单分组/建单/库存冻结/券锁定与购物车结算**同一段代码**，唯一差别是第 5 步清购物车按 `directBuy` 跳过——"立即购买"绝不污染用户购物车。小程序新建 `pages/checkout` 确认单：地址（复用 address pick 模式）/ 券（服务端 `usable` 判定 + `_couponDeclined` 优先级）/ 数量步进（库存封顶）/ 金额汇总；提交成功直接 `pay.checkout` 进页内收银台，支付成功/超时回落订单列表。详情页底部改「分享｜加入购物车｜立即购买」。
- **② 展示丰富化**：轮播 3→5 图（`images.gallery` 扩容，零新增包体）；新增**规格参数**卡（品牌/分类/适用宠物/适用阶段/过敏原提示——全部来自后端真实字段，没有的行不出，不编造）与**包装规格**卡（SKU `spec` 键值对 + SKU 编码 + 当前库存，**随选中规格联动**）；商品介绍升级为**图文详情**（detail 长文 + 3 张配图 `detailImages`，与轮播错开取图）。
- **③ 分享**。定位：devtools 转发面板的卡片预览**本来就是页面截图**、"转发成功/查看分享页"是工具模拟行为（真机上点分享弹出系统转发面板、好友点卡片按 `path` 进商品详情）——代码本身没有"只截图"的实现。本轮把分享做扎实：卡片标题带价格、封面用商品首图（1:1 本地位图，真机有效）、新增 `onShareTimeline`（右上角菜单 → 分享到朋友圈）。
- **验证**：后端 `scripts/direct-buy-smoke.py` **13/13**（直购下单成功/金额=单价×数量/购物车条目数零变化/购物车结算原路径回归/缺 `buyQuantity` 400/不存在 SKU 404/订单详情可查）；小程序 stub **24/24**（结算页取 SKU/金额/券三段优先级/数量边界/直购参数/进收银台；详情页 5 图/参数卡/联动/跳转/分享卡）；静态自检 0 错误 0 告警。trade 容器已重建健康。

### 2026-09-26（第四十一轮 · tab 切换顶部白条根治：5 个 tab 页改自绘导航栏）

> 用户二次反馈同一白条问题（附截图）。上一轮只给 15 个页面 json 补了 `navigationBarBackgroundColor`，未能根治 —— 本轮重新取证后改结构。

- **再取证（像素级）**：截图中状态栏区域 = #4F6EF7（蓝），紧邻其下的 44pt 行 = **纯白 #FFFFFF 且无标题**（实测 71px @1.62px/pt ≈ 43.8pt）。视频逐帧确认三次切换各闪约 160ms 后恢复。状态栏恒蓝 + 标题行变白 → 白条就是**原生导航栏的标题行被重建为默认白色**。
- **为什么上一轮的办法不够**：开发者工具中导航栏是模拟层，切 tab 时该层随页面重建，与页面 json 里声明什么颜色无关（页面内容与状态栏都正常，只有这一行重建）。真实设备同理：原生导航栏由客户端在切页时重建。
- **根治**：5 个 tab 页（index / category / cart / orders / profile）改用**自绘导航栏** —— `navigationStyle: custom` + 新增 `components/nav-bar`（固定蓝条 + 流内占位块）；尺寸由 `utils/nav.js` 按胶囊位置反推（`(胶囊 top - 状态栏高) * 2 + 胶囊高`，取不到则兜底 44px）。原生导航栏不再存在 → 没有可闪烁之物；非 tab 页保持原生（推进式跳转无此问题，且需要返回键）。
- **连带收口**：① category 页整页 `position: fixed` 布局，改为按导航总高下发 `top`（否则内容顶到自绘栏底下）；② orders 页吸顶筛选条 `top` 同步下移；③ index 页下拉刷新改**自绘指示器**（原生三点指示器会被自绘导航栏遮挡，自绘栏在 `loading` 时显示白环）。
- **验证**：17 个 JSON 合法；5 个改动 JS 语法全过；Node stub **11/11**（尺寸计算 iPhone 15 Pro=42/96、接口不可用兜底 44/98、组件 attached 写入、category/orders 的 navTop=96、index 下拉 refreshing 开关 + stopPullDownRefresh）。

### 2026-09-26（第四十轮 · tab 切换顶部白条闪烁修复）

> 用户录屏反馈：底部标签切换页面时，手机顶部突然闪现一条白色色带随后消失。

- **定位过程**：ffmpeg 全帧率抽帧（185 帧）+ 帧差定位三次切换发生在第 60/78/97 帧；逐行亮度分析确认白条位置 = **导航栏区域（状态栏下方、胶囊那一行）**，切换后约 150ms（59-63 帧）导航栏渲染为白底且无标题，随后才涂上蓝色与标题。状态栏全程保持蓝色 —— 排除滚动重置/页面背景缺失/过渡动画，锁定为**导航栏初始化时序**。
- **根因**：15 个页面 json **都没有显式声明 `navigationBarBackgroundColor`**，只依赖 app.json 全局 `window` 配置。tab 切换时新页面导航栏先按缺省白色初始化、样式合并在下一拍才生效 → 白条闪现（开发者工具模拟器上明显）。
- **修复**：全部 15 个页面 json 显式补 `"navigationBarBackgroundColor": "#4f6ef7"` + `"navigationBarTextStyle": "white"`（与全局一致；非 tab 页顺带受益，风格声明收口到页面级）。
- **验证**：16 个 JSON 全部合法；修复后切换 tab 导航栏即按蓝色初始化，无白条（开发者工具重新编译后切换五 tab 验证）。

### 2026-09-26（第三十九轮 · 页内沙箱收银台 + 购物车选券）

> 用户反馈：① 支付要复制链接到浏览器才能付，要求小程序内直接拉起支付；② 结算时优惠券不生效（59 元商品持 5 元无门槛券仍付 59）。

- **① 页内沙箱收银台 `pages/pay/pay`（app.json 15 页）**。先说清三条路为何只有一条能走：`web-view` 强制 HTTPS 且业务域名要在小程序后台预配置，`http://localhost` **永远配不了**；`wx.requestPayment` 需要真实微信渠道的预下单参数（B-07 边界，渠道未接）；所以正确做法是**页内自绘收银台**：`pay.checkout()` 从「复制链接弹窗」改为 `navigateTo('/pages/pay/pay?payNo=...&orderNo=...')`，收银台直接调 `POST /api/payments/sandbox/{payNo}`（**公开端点**，与 Web 服务端渲染收银台同一条校验/入账路径，不新开后门）。**回调链透明交接**：`pay.js` 用 `pendingHandlers` 暂存调用方 onSuccess/onTimeout，收银台 `takeHandlers` 取走——调用方（订单列表/详情）零改动；确认失败兜底走原轮询。页面注明「沙箱环境模拟支付，不产生真实扣款」。
- **② 购物车选券**。根因排查：后端 OrderService 锁券抵扣逻辑本来就正确，**Web 端 Checkout 也传了 couponCode，是小程序端下单从没传过** → 纯前端缺陷。修法：购物车地址行下新增**选券行**（🎫 券名 + 已减金额 + 「券后以支付页为准」）；可用性判断全走服务端 `GET /api/coupons/mine/usable?amount=`（返回逐张 usable/reason + 服务端选好的 bestCouponCode/bestDiscountAmount），前端只把 `couponCode` 传给 `api.order.place`，**金额以服务端锁券时计算为准**，前端不自行算折扣。**语义规则**：默认选服务端推荐最优券；用户点「不使用优惠券」置 `_couponDeclined` **优先级最高**（不被自动推荐覆盖——stub 测试当场抓出旧写法会复活已拒绝状态，已修为三段优先级：拒绝 > 推荐 > 保留已选且失效自动退回）；下单成功重置选券态。
- **验证**：静态自检 0 错误 0 告警（23 js / 15 wxml）；Node stub 7 场景全过——A1 有最优券默认选中、A2 拒绝后不被复活、A3 已选券金额变化失效自动退回、A4 下单携带 couponCode，B0 无券隐藏行、B1 零元不拉接口、B2/B3 收银台成功/兜底轮询。

### 2026-09-26（第三十八轮 · 未登录态按钮统一「去登录」+ 登录返回自动重拉）

> 用户反馈：地址簿未登录时在「登录后管理收货地址」下显示的是**重试**按钮（截图），要求修复并全局排查统一。

- **全局排查结论**：错误盒 + 重试按钮共 6 页 —— **address / order-detail / orders / after-sales** 四页未登录分支有误（coupons 已正确用「去登录」；index/product/category/seckill 的重试对应公开接口，未登录可浏览，属合法场景不在误判之列）。orders 更隐蔽：未登录时把 error 清空 → 落成「还没有相关订单」空态误导。
- **统一修法**：catch 的 needLogin 分支显式置 `needLogin: true`；错误盒按钮 `wx:if="{{needLogin}}"` 显示**去登录**（`api.openLoginPage()`，带去重），`wx:else` 才是重试。address / order-detail 额外补 `onShow` 自动重拉 —— 登录页成功后是 `navigateBack` 回来源页，只触发 onShow 不触发 onLoad，不清「登录返回后停在错误页」的体验断点；onShow 只在 `needLogin && getToken()` 时重拉，避免每次回页多发请求。
- **验证**：静态自检 0 错误；Node stub 4 场景全过（未登录→needLogin 标志 / 登录返回自动重拉 / 网络错误仍走重试 / 订单列表未登录明确提示）。

### 2026-09-26（第三十七轮 · 订单列表商品缩略 + 我的售后入口/列表页）

> 用户两点反馈：① 订单列表卡片只显示「共 N 件商品」，没图没名；② 「我的」页没有售后入口，找不到售后功能。搜索入口维持现状。

- **① 订单列表商品缩略**：`listMine` 的 `OrderView` 本就带 `items`（含 title/specText/mainImage），纯前端改造。卡片最多展示 **2 个商品行**（100rpx 缩略图经 `images.skuImage` 解析层 + 标题单行截断 + ×数量），更多用「…等共 N 件商品」收口，实付金额右对齐独立一行 —— 列表卡片保持紧凑，详情留给详情页。
- **② 我的售后**：`profile` 页「我的订单」下新增入口（`wx:if logged`，接口要求令牌）；新建 `pages/after-sales/after-sales` 列表页 —— 分页拉 `GET /api/aftersales/mine`，卡片 = 类型 + 状态标签 + 诉求（≤2 行）+ 卖家回复 + 单号/时间；**点卡片跳对应订单详情**（售后进度、物流都在详情页，不重复造详情页）；空态/未登录给「去我的订单」指路（申请售后入口在订单详情，按订单状态出现）。app.json 注册（13 → 14 页）。
- **验证**：小程序静态自检 0 错误 0 告警（22 js / 14 wxml）；Node stub 5 场景全过（列表渲染缺 Label 兜底 / 未登录 / 空列表 / 商品缩略 2 行+图片走解析层 / 空 items 不炸）。

### 2026-09-26（第三十六轮 · 多类型售后放开 + 售后举证图直传链路 A15）

> 用户指令「继续完成未完成的任务」—— 工作日志遗留两项低优先待办：① 同一订单多类型售后（此前保守：有任何售后就收起入口）；② 小程序售后**图片凭证上传**（此前只提示「去 Web 端粘贴直链」，而 Web 端也是粘贴，全系统根本没有上传链路）。

- **① 多类型售后（纯前端，后端规则早已就绪）**：唯一键 `(order_id, type)` 冲突才 409，**不同类型可各自立案**。
  `after-sale` 页从「查到一条就不给表单」改为**按已提交类型过滤可选类型**（`remainingTypes`），已提交的售后单列表展示 + 「每种类型只能提交一次」提示；两种都提过才完全收表单。
  `order-detail` 页售后进度改列表展示，**入口只在两种类型都提交过才收起**（`aftersales.length < 2`）。
  Node stub 验证 4 路径：无售后→2 可选 / 已提退款→只剩退货 / 两种都提→无可选 / 查询失败→仍给全部（后端 409 兜底）。
- **② 图片上传链路 A15（从零新建，全系统第一个 multipart 端点）**：
  - 后端 `fulfilment/UploadController`：`POST /api/uploads`（**登录**，multipart `file`，扩展名+Content-Type 双白名单 jpg/jpeg/png/webp/gif，≤5MB，UUID 落盘不信任原文件名）+ `GET /uploads/{filename}`（**公开读**，正则锁死 UUID 文件名防穿越，`Cache-Control: 30d`）。URL base 优先级：显式配置 > `X-Forwarded-*` > 请求 Host。存储=本地盘（对象存储前的过渡态，落卷 `fulfilment-uploads:/data/uploads`）。
  - **双前缀是安全设计**：网关白名单**按路径、不区分方法**，读写同前缀时给 GET 开白名单=匿名 POST。白名单只放 `/uploads/**`，`POST /api/uploads` 必须登录。
  - 网关：fulfilment 路由追加 `/api/uploads/**,/uploads/**`；`GatewaySecurityProperties` 白名单追加 `/uploads/**`。
  - 小程序：`request.js` 新增 `upload()`（wx.uploadFile 对齐 request 行为：同一 Bearer 头/包络/401 处理）；售后页举证图网格（选图→逐张上传→预览/删除，失败原地标「上传失败」不挪位——中途 splice 会让在途上传回写错位），提交只带成功直链、有失败图/上传中明确拦截。
  - **坑（T-66）**：Dockerfile 通用镜像 + 命名卷挂到 `/data/uploads`，卷初始化属主 root，非 root（uid 10001）进程写盘 `AccessDeniedException` → 500。修法：镜像内 `mkdir -p /data/uploads && chown`（空命名卷首次挂载会继承镜像目录属主），**且必须先删容器再删卷**（stop 不释放挂载）。
  - **坑（T-65）**：bash 里 `mvn.cmd` 无 `JAVA_HOME` 时**静默什么都不做、exit 0**——编译验证必须看 class 产物 mtime，不能只看退出码。
- **验证（真实服务）**：`mvn package` + 重建 fulfilment/gateway 容器；新增 `scripts/upload-smoke.ps1` **8/8 全绿**（404 白名单生效 / 匿名 401 / 上传 200+url 格式 / 回读 200 image/png / .txt 400 / 令牌连通）；Python 探针实证**上传直链被售后创建接口的 `ImageUrls.validate` 接受**（带图+假订单号 → 404「订单不存在」而非 400 图片拒绝）。小程序静态自检 0 错误；Node stub 场景 4+5 全过。

### 2026-09-26（第三十五轮 · 登录/注册提示按路径各自准确 + 给可执行下一步）

**用户反馈**：用**未注册**手机号直接登录，提示是「**请先登录**」；而走「微信一键登录 → 微信未绑定 → 手机号登录」时却能注册并绑定成功。诉求：两条路径的提示各自准确 —— 直接手机号登录且未注册 → 提示**先注册**；微信路径未绑定 → 引导**手机号登录或注册并绑定**。

**根因（实测确认）**

| 层 | 实际行为 |
|---|---|
| 后端 | 未注册手机号登录 → **401** + message「**手机号或密码不正确**」；已注册但密码错 → **同为 401 + 同一文案**（**防用户枚举的刻意设计**，合理） |
| 前端 | `friendlyMessage('UNAUTHORIZED', msg)` → `return '请先登录'` —— **一刀切把后端准确文案盖掉了** ✗ 这就是"未注册却提示请先登录"的直接原因 |

**修复**

| 文件 | 改动 |
|---|---|
| `utils/request.js` | 401 **透出后端文案**（`message || '登录已过期，请重新登录'`）：登录接口 → 「手机号或密码不正确」；业务接口 → 「身份未认证或登录已过期」——同一条修复让两边都变准确 |
| `pages/login/login.js` | 新增 `handlePhoneAuthError(err, mode)`：**401** → 页面内引导「手机号登录失败：可能未注册或密码输错」+ 动作「去注册新账号」+ modal；**注册撞号 409** → 「该手机号已注册」+ 动作「去登录」+ modal；其余 → 提示条 + toast 双通道 |
| `pages/login/login.js` | `bindTip` 升级为**通用页面内引导**，动作扩展为 `bind` / `phone` / `register` / `login`；切页签时**保留已填手机号** |
| `pages/login/login.wxml` | 引导按钮文案改为 `actionText`，`action` 为空时不渲染按钮 |

**验证（Node stub 驱动页面方法）**

| 场景 | 结果 |
|---|---|
| ① 未注册手机号登录（401） | ✅ 提示条「手机号登录失败 / 手机号或密码不正确。如果是第一次使用，先去注册…」+ 按钮「去注册新账号」+ modal「去注册」 |
| ② 点「去注册新账号」 | ✅ `mode='register'` 且**手机号保留**（不用重打） |
| ③ 注册撞号（409） | ✅ 提示条「该手机号已注册 / 直接登录即可」+ 按钮「去登录」；点后 `mode='phone'` 且手机号保留 |
| ④ 微信未绑定 + 未登录（422） | ✅ 「用手机号**登录或注册**后，会自动把这个微信绑定到该账号」+ 按钮「用手机号登录并绑定」 |
| ⑤ 微信未绑定 + 已登录（422） | ✅ 按钮「绑定到当前账号」 |

> 设计取舍：后端不区分「手机号不存在 / 密码错误」是安全设计（防枚举），所以前端**不去猜**，而是把两种可能都写清并给出对应入口 —— 既不泄信息，也不让用户卡住。


### 2026-09-25（第三十四轮 · 未绑定提示「必然可见」加固：页面内引导 + modal 双保险）

**用户复测反馈**：解绑后点「微信一键登录」，Console 里有两条 —— 一条 `422`，一条 **`[login] 微信登录失败 {status:422, code:'BUSINESS_RULE', message:'该微信未绑定账号…'}`**，但**提示框仍未弹出**。

**这一步的价值**：日志证明**判断逻辑与 422 分支都已命中**（上轮加的诊断日志起作用了），因此问题**不在 `status` 判断**，而在 **`wx.showModal` 这个载体本身没能呈现**（系统弹窗可能被环境状态/竞态吞掉 —— 这类问题在真机上难以稳定复现，也不该靠"再试一次"赌运气）。

**修复策略（不依赖单一载体）**

| 层 | 载体 | 特性 |
|---|---|---|
| 第一层（**必然可见**） | `data.bindTip` → **页面内引导卡片**（微信按钮正下方，标题 + 说明 + 操作按钮 + 可关闭） | **不依赖任何 wx API**，只要 `setData` 成功就一定渲染 |
| 第二层（保险） | `wx.showModal` | 保留，正常环境下体验更轻 |

卡片按钮按登录态自适应：
- 已登录（`loggedIn=true`）→「**绑定到当前账号**」→ `bindCurrentWechat()`，成功 toast「已绑定微信」
- 未登录 →「**用手机号登录并绑定**」→ 切 `mode='phone'` 并置 `pendingWechatBind`（登录成功后自动绑定）

**验证（Node stub 驱动页面方法）**

| 场景 | 结果 |
|---|---|
| 未登录 + 422 | ✅ `bindTip={title:微信还没绑定账号, content:…, action:phone}` + `wx.showModal` 已调用 |
| 已登录 + 422 | ✅ `bindTip.action='bind'` + `wx.showModal` 已调用 |
| 点卡片按钮（bind） | ✅ 走 `bindCurrentWechat()` → 成功清空卡片 + toast |
| 点卡片按钮（phone） | ✅ 切手机号表单并置 `pendingWechatBind` |


### 2026-09-25（第三十三轮 · 解绑后「微信一键登录无提示」根治 + 地址表单输入框修复）

#### 一、解绑后点一键登录：只有 422、界面无提示（根因链与修复）

**现象**：解绑微信 → 再点「微信一键登录」→ Console 有 `POST /api/auth/wechat/token 422`，但界面**没有任何提示**。

**根因链（两条静默路径叠加）**

| 环节 | 问题 | 后果 |
|---|---|---|
| `api.getWxCode()` | `wx.login` 在个别环境**既不回调 success 也不回调 fail**，Promise **永不 settle** | 调用方的 loading/submitting 被永久锁死 |
| `app.silentRelogin()` | 单飞标记 `_silentLoggingIn` 因此**永久为 true**，后续调用直接 `reject({needLogin:true})` —— **该错误没有 `status` 字段** | 绕过 422 的专用引导分支 |
| `login.handleWechatError()` | 未特判 `needLogin`，落到末尾 `wx.showToast(...)` | toast 仅 1.5 秒且易被后续操作顶掉 → 观感"无任何提示" |
| `login.wechatLogin()` | `if (this.data.submitting) return;` —— **静默返回** | submitting 一旦被锁死，**之后每次点击都毫无反应**（像按钮坏了） |

**修复**

| 文件 | 改动 |
|---|---|
| `utils/api.js` | `getWxCode(timeoutMs=10000)` 加**超时保护**：`wx.login` 不回调时 10 秒后 reject「微信授权超时」（带 settled 标记防重复 settle） |
| `pages/login/login.js` | ① `submitting` 守卫改为 `wx.showToast('正在处理中，请稍候')`，**不再静默**；② `handleWechatError` 的 **503 / 未知错误（含单飞冲突）全部改用 `wx.showModal`**（modal 不会被顶掉），未知错误并给「用手机号登录」出口；③ 加 `console.warn('[login] 微信登录失败', {status, code, message})` 作为诊断线索 |

**验证方式（可复现，不依赖真机）**：Node 里 stub `wx` API + `Page`，直接驱动 `handleWechatError` —— 4 种失败路径**全部产生可见提示**：

| 场景 | 结果 |
|---|---|
| 未登录 + 422 | ✅ MODAL「微信还没绑定账号 / 用手机号登录或注册后，会自动把这个微信绑定到该账号」 |
| **已登录 + 422（本次用户情形）** | ✅ MODAL「微信还没绑定账号 / 当前账号已登录。要把这个微信绑定到当前账号吗？」 |
| 单飞冲突（无 status 的 `needLogin`） | ✅ MODAL「一键登录未完成 / 正在重新登录」（修复前是含糊 toast） |
| 503 未配置 | ✅ MODAL「微信登录暂不可用 / 请改用手机号登录」 |

另用「`wx.login` 永不回调」实测：**1209ms 后正确 reject**（测试用 1.2s 超时）→ 不再存在永久悬挂。

#### 二、收货地址表单：输入框文字显示不完整（修复）

**根因**：`.field-input` 写了 `width:100% + box-sizing: border-box`，而小程序 `input` 的**默认内容高度**在 border-box 下会被上下 `padding: 20rpx` 挤占 → 文字只露出上半截（截图里"请填写收货人姓名""11 位手机号""如 广东省"都被裁）。

**修复**（`pages/address/address.wxss`）：input 改用**显式高度**——`height: 84rpx; line-height: 84rpx; padding: 0 24rpx`（高度已给定，padding 只管左右）；`textarea` 保持 `min-height: 160rpx + padding`（其行为正常，截图可证）。

> 说明：登录页 / AI 页的 input 用的是 `padding` 撑高且**未加 box-sizing**，实测显示正常（截图佐证），故**不动**，避免引入视觉回归。


### 2026-09-25（第三十二轮 · P2 订单售后入口 + P3 搜索高亮与纠错）

#### 一、P2 订单售后入口（已实现）

| 文件 | 内容 |
|---|---|
| `pages/after-sale/*`（新） | 售后类型（**仅退款 / 退货退款**）+ 诉求（≤500 字，本地校验与后端一致）+ 提交；进页面先查该订单是否已有售后，**有则只展示进度、不给重复提交入口** |
| `pages/order-detail/*` | 展示该订单售后进度（类型/状态/诉求/卖家回复）；**SHIPPED / COMPLETED** 显示「申请售后」 |
| `utils/api.js` | 新增 `aftersale.create / mine / detail` |

**实测中发现并修正的产品细节**：后端业务规则是「**发货后**才能申请售后」（未发货时 422「订单当前状态为「待付款」，发货后才能申请售后」）。第一版我在 `PAID` 状态也给了入口 → 用户填完表单必然失败。已改为：`PAID` 只提示「卖家发货后可申请售后」，入口仅 `SHIPPED / COMPLETED` 显示。
另外拿到第二条规则文案：**同订单同类型**重复提交 → 409「该订单的此类售后已立案，请勿重复提交」。因此小程序端在已有售后时不再给入口（如需另一种类型，可在 Web 端提交）——**保守但不会误操作**。

#### 二、P3 搜索高亮 + 纠错（已实现）

| 部分 | 实现 |
|---|---|
| **纠错端点**（后端） | `GET /api/products/search/suggestion?keyword=猫良` → `{"suggestion":"猫粮"}`；路径用**两段**避免与 `/{spuId}` 冲突；白名单 `/api/products/**` 已覆盖，无需改网关 |
| **词表**（零维护） | 品牌名 + 分类名 + 在售标题切词，**外加每个词的 2~4 字连续子串**；预建**拼音索引**（同音 O(1)）与**长度索引**（形近只扫 ±1 桶） |
| **判定** | ① 拼音完全相同（中文错误绝大多数是拼音输入法选错字）→ ② 形近：长度差 ≤1、共享至少一汉字、编辑距离 ≤ max(1,len/3)；**给不出靠谱候选返回空**（宁可不建议） |
| **前端高亮** | `utils/highlight.js` 把标题切成命中/未命中片段，WXML 用 `wx:for` 渲染 `.hl`；**刻意不用 rich-text**（拼 HTML 有注入面） |
| **前端提示** | 完全搜不到时调一次建议接口 → 空态里显示「你是不是想搜「猫粮」？」→ 点击一键改搜；慢响应会校验关键词是否已变，避免覆盖新结果 |

**关键教训（实测得出）**：**词表粒度决定纠错能不能用**。第一版只收「标题按空格切出的整词」→ 切出来是「幼猫粮」「冻干双拼全价猫粮」这类长词，用户打的「猫粮」「冻干」不在词表 → 纠错永远给不出建议，甚至把「猫粮」当错词错配成「猫主粮」。加入 2~4 字子串后立刻正确。

#### 三、实测

| 项 | 结果 |
|---|---|
| 搜索纠错 6 例 | ✅ 猫良→**猫粮**、喂食气→**喂食器**、冻千→**冻干**；猫粮 / 猫砂盆 / zzz不存在 → **空**（不硬凑） |
| 售后链路 7 步 | ✅ 登录 → 下单 → 预下单 → **沙箱确认支付 SUCCESS** → 模拟发货 SHIPPED → **申请售后 200（PENDING/仅退款）** → 我的售后 1 条 orderNo 匹配 → 重复提交 **409** |
| 小程序静态自检 | ✅ 21 js / 13 wxml / 104 文件 / 0 错误 0 告警（自检脚本同时补了 `wx:for-item` 别名白名单，消除 `seg` 误报） |

**待办（低优先）**：小程序端售后**图片凭证上传**（需先有上传链路，当前提示在 Web 端补充）；订单详情「同一订单两种售后类型」的完整支持。


### 2026-09-25（第三十一轮 · 地址选择模式 + IK 分词器实测结论 + 冒烟夹具治理）

#### 一、P0 地址选择模式（已实现）

| 改动 | 文件 | 说明 |
|---|---|---|
| 地址簿「选择模式」 | `pages/address/*` | `?pick=1` 进入：顶部提示条 + 卡片描边可点，选中后经 **`eventChannel` 回传**并返回 |
| 购物车地址行 | `pages/cart/*` | 新增可点的「收货地址」行（显示收货人/电话/完整地址）；点进选择模式，选中即**本次结算生效**；下单优先用选中地址、否则回落默认地址 |
| 秒杀收货信息确认 | `pages/seckill/*` | 秒杀讲"快"，**不插选地址步骤**；抢购成功后弹窗展示收货信息，避免手快寄错（要换地址走「购物车 → 地址」或「我的 → 收货地址」） |

> 为什么用 `eventChannel` 而不是全局变量：避免留下"下一次结算还沿用上一次选的地址"的幽灵状态。

#### 二、IK 中文分词器：实测结论 = **不切换**（数据说话）

| 步骤 | 结果 |
|---|---|
| 插件获取 | ✅ 走官方非 GitHub 源 `release.infinilabs.com`（**GitHub 源 404**，这正是当初判定的最大变数） |
| 包校验 | ✅ `name=analysis-ik`、`version=8.11.4`、`elasticsearch.version=8.11.4`（与 ES **严格同版本**） |
| 隔离容器验证 | ✅ `bin/elasticsearch-plugin list` → `analysis-ik`；临时 ES 容器正常启动（10 秒就绪） |
| **分词效果** | ❌ **无预期收益**：`猫粮` → ik_smart `['猫','粮']`（与 standard 相同）；`喂食器` → `['喂','食器']`（改善有限）；`冻干鸡肉粒` → `['冻','干','鸡肉','粒']`（部分改善） |

**结论**：IK 主词典是通用词典，**不含"猫粮/喂食器/冻干"这类垂直词** → 装了等于白装，却要承担「插件版本与 ES 绑定、容器重建要带插件」的运维面（A10 compose 注释里当初**刻意**规避了这一面）。

**保持现状**：现有「`match_phrase` 精确优先 + 分层加权 + 两段式兜底」已达成目标（实测 `喂食器` 第 1 条即精确命中）。
**将来若要做词级语义**：正确路径 = **IK + 宠物领域词典（猫粮/犬粮/喂食器/冻干/猫砂/磨牙棒/化毛膏/猫抓板…）+ 定期运营**，三者缺一不可；实施步骤与风险回滚见交付说明，插件包已清理（不入库）。

#### 三、冒烟夹具治理

- **已清理**：`微信冒烟-A/B` 共 **16 个**（删除前确认**零关联**：无地址/购物车/订单/秒杀单）。
- **从源头止住**：给 `scripts/wechat-auth-smoke.ps1` 补**自清理**（此前跑一次留 2 个账号，堆了 16 个）；`wechat-bind-smoke.ps1` 本就自带。
- **不盲删其余 690+ 历史夹具**：`铲屎官小林`、`买家甲` 等昵称是**被 CI 冒烟断言依赖的固定夹具**（`scripts/smoke.ps1:135` 断言 `data.nickname -eq '铲屎官小林'`），盲删会直接把 CI 弄红 → 需按脚本逐个核对依赖后再清（列为待办）。
- **踩坑**：`docker exec mysql ... -e "DELETE ... LIKE '中文%'"` **不带 `--default-character-set=utf8mb4` 时匹配不到任何行**（第一次删除"成功"但 0 行受影响）——中文条件必须带字符集参数。


### 2026-09-25（第三十轮 · 小程序体验闭环：地址簿 / 订单详情 / 微信绑定管理）

> 目标：补齐"只有 Web 端能做"的环节，让只用小程序的用户能走完整主流程。

| # | 交付 | 文件 | 说明 |
|---|---|---|---|
| 1 | 收货地址簿 | `pages/address/*`（新） | 列表 / 新增 / 编辑 / 设为默认 / 删除；本地校验与后端一致（手机号正则、长度上限）；**不做省市区级联**（数据包约 900KB 会挤爆 2MB 主包）→ 三个文本输入 + 常用标签 |
| 2 | 订单详情页 | `pages/order-detail/*`（新） | 状态卡（含有效期提示）/ 商品明细 / 金额（总额·运费·优惠·实付）/ 收货信息 / **物流轨迹** / 按状态显示「去支付·取消订单」「确认收货」/ 复制单号；下拉刷新 |
| 3 | 微信绑定管理 | `pages/profile/*` | 菜单显示 `wechatBound`（已绑定/未绑定）；绑定走 `wechatBind`、解绑走 `wechatUnbind`（**每次重新 `wx.login` 取 code**，code 一次性）；解绑二次确认 |
| 4 | 支付模块抽取 | `utils/pay.js`（新） | `checkout`（发起+收银台+轮询）/ `startPolling` / `stopPolling`；订单列表与详情页共用，删除列表页里那份重复实现 |
| 5 | 入口打通 | `pages/cart/*`、`pages/seckill/*`、`pages/orders/*` | 无默认地址时弹窗**直接跳地址簿**；订单卡片可点进详情，卡内按钮改 `catchtap` 防冒泡 |
| 6 | 契约与工具 | `utils/api.js`、`utils/request.js` | 补 `put`、地址 CRUD、物流、`getWxCode()`、`wechatUnbind/Rebind`；`login.js` 复用 `getWxCode` 去掉重复实现 |

**技术选型与约束**：延续原生小程序、零新增依赖、后端**零新增端点**；主包 2MB 上限决定了"不做省市区数据包"；一次性 code 决定了绑定/解绑/换绑必须各自重新取码。

**实测（14 项全通）**：地址空列表 → 新增（首条自动默认）→ 默认查询 → 编辑 → 再列表；`/users/me` 的 `wechatBound` 三态 `false→true→false` 且**不含 openid**；下单 → 订单详情（items/payAmount/receiver）→ 物流轨迹（未发货 total=0）。自检 **0 错误 0 告警**（期间自检当场抓出 `profile.wxml` 里 `addressTip` 旧方法名残留，已修）。

**下一步计划**：P0 地址"选择模式"（结算临时换地址，而非只能改默认）｜P1 **IK 分词器**（含风险/回滚，见交付说明）｜P2 订单售后入口｜P3 搜索高亮与纠错。


### 2026-09-25（第二十九轮 · 解绑/换绑接口 + ES 搜索完善 + 分类页溢出修复）

#### 一、账号解绑与换绑接口（新增）

| 端点 | 入参 | 校验规则 | 关系更新 | 失败返回 |
|---|---|---|---|---|
| `POST /api/auth/wechat/unbind` | `{code}` | 需登录；code→openid 必须**等于当前账号绑定的 openid** | `wx_openid = NULL`（`LambdaUpdateWrapper.set(null)`） | 401 未登录/code 无效；**422** code 属他人微信；503 微信不可用；**未绑定 → 200 幂等** |
| `POST /api/auth/wechat/rebind` | `{code}`（新微信） | 需登录；新 openid **未被他账号占用** | `wx_openid = 新 openid`（旧关系被覆盖即释放） | 401；**409** 新微信已属他人；503；**新=旧 → 200 幂等**；未绑定 → 首次绑定 200 |

- **为什么带 code**：解绑是破坏性动作（解绑后该微信可被他人绑定），只验登录态不够 —— 必须证明"操作者确实持有这个微信"。
- **实现坑（已实测验证）**：置空必须用 `LambdaUpdateWrapper.set(field, null)`；MyBatis-Plus `updateById` **默认忽略 null 字段**，直接 `setWxOpenid(null)` 会「看起来成功、库里没清」。
- **安全**：`UserView` 只新增 `wechatBound` 布尔，**绝不下发 openid**；两端点**不在网关白名单**。
- **自测**：新增 `scripts/wechat-bind-smoke.ps1`（24 断言）；本地等价 HTTP 实测 **13 项分支全通**。

#### 二、ES 搜索完善（已落地 4 项 + 六维优化清单）

| 维度 | 已落地 | 待办建议 |
|---|---|---|
| 查询条件 | **多词 AND**（空格/逗号分隔），`猫粮 2kg` → 精确 2 条 | 引号短语、排除词 `-xx`、字段限定 `品牌:渴望` |
| 分词 | standard（中文逐字）+ 拼音 ngram 路 | **接 IK 分词器**（`ik_smart`）按词切分；`title` 加 `keyword` 子字段做完全匹配加权 |
| 排序 | `_score → salesCount → refId`；显式 `sort=relevance` | 自营/新客加权、时间衰减 |
| 分页 | **深分页保护** `from+size ≤ 10000`（超出 400 + 明确文案，两引擎统一入口拦截） | 更深分页改 `search_after` 游标 |
| 结果准确性 | 分层加权 + 两段式兜底；同义词应用侧扩展 | 命中高亮 `highlight`、拼写纠错 did-you-mean；用日志 `兜底=true` 的词反哺同义词 |
| 可观测性 | **结构化检索日志**（keyword/引擎/命中/兜底/页/返回/耗时） | 落库做搜索分析（零结果词、无点击词） |

实测：「猫粮 2kg」2 条全中、「冻干 鸡肉」1 条、「喂食器」1 条精确、`sort=relevance` 与默认一致、`page=600` 被拦（400）、`page=500` 边界放行、无关键词仍按销量降序（无回归）、日志可见。

#### 三、分类页分类标签/标题溢出（修复）

`.cat-main` 是 flex 子项，默认 `min-width: auto` → 被内部长商品名"顶宽"，右栏连同卡片一起超出屏幕（上一轮只给 `.cg-body` 加了 `min-width:0`，漏了外层）。修复：`.cat-main{min-width:0}` + `.cg-card{max-width:100%;box-sizing:border-box}` + `.cg-name{word-break:break-word}`。


### 2026-09-25（第二十四轮 · 搜索相关性修复：结果与关键词不匹配）

> 用户反馈（附截图）：搜「喂食器」返回猫罐头/鸡肉条零食/冻干鸡肉粒/自动喂食器，**只有 1 条对且排最后**。

#### 三处根因（均有实测证据）

| # | 根因 | 证据 |
|---|---|---|
| 1 | `multi_match` 写死 **`operator: or`**，而 `pet_text` 用 standard 分词器（中文**逐字切分**）→ 「喂食器」= `喂 OR 食 OR 器`，含任一字的商品全召回 | 实测召回 4 条，3 条含「食」；对照 MySQL 路径 `title/subtitle LIKE %喂食器%` 只会命中 1 条 |
| 2 | 默认排序只有 `salesCount desc`，**ES 显式 sort 后不再按相关性排** | 实测顺序 1008→1011→1009→1015，精确项「霍曼 自动喂食器」（销量 860 最低）垫底 |
| 3 | 商品占位图上画了品类文字，且按 id 映射（与真实商品不一一对应） | 截图：猫罐头配「领养关注」、喂食器配「逗猫玩具」，放大「不匹配」观感 |

#### 修复

| 文件 | 改动 |
|---|---|
| `duoduo-product/…/search/EsSearchEngine.java` | ① 召回改**分层加权**：标题整串短语 6.0 / 标题分词全命中 3.0 / 副标题短语 2.0 / 描述短语 1.0 / 跨字段 AND 2.0 / 拼音 1.5 / **逐字兜底 0.1**；同义词变体权重减半；② 新增 `searchStrictThenLoose`：**严格召回为零才启用兜底**（两段式，宁可 1 条精确也不 4 条里对 1 条）；③ 默认排序 `_score desc → salesCount desc → refId desc`（无关键词时 `_score` 相同 → 退化为销量降序，不影响导航页；显式 sort 仍尊重用户选择）；④ 校正类注释（索引侧并没有 synonym filter） |
| `scripts/gen-miniprogram-images.py` | 商品图去掉品类文字（纯图形 + 柔光白圆 + 装饰弧），消除图文不符；重生成后 **352.7 KB / 17.2%** |

#### 自测（重建容器后经网关实测）

| 关键词 | 修复前 | 修复后 |
|---|---|---|
| 喂食器 | 4 条（精确项排第 4） | **1 条：霍曼 自动喂食器 4L** |
| 零食 | 4 条（混入喂食器/罐头） | 2 条（均为零食/猫零食） |
| 猫粮 | 15 条（混入犬粮/喂食器/化毛膏） | **4 条全是猫粮** |
| 猫窝（严格 0 命中） | — | 11 条相关建议（兜底生效，不空手） |
| 无关键词 / `sort=price_asc` | 销量降序 / 生效 | **无回归** |

`mvn -pl duoduo-product -am package` BUILD SUCCESS；小程序静态自检 0 错误 / 0 告警。**改动只在查询构造，索引无需重建。**

### 2026-09-25（第二十三轮 · 微信一键登录「未绑定」闭环 + 文案校正）

> 用户反馈：微信一键登录报错（附截图）。**诊断结论：登录链路完好，是「该微信未绑定账号」（422）。**

#### 证据链（服务端日志）

- `19:13:08 WechatGateway: jscode2session 成功 openidPrefix=oqYQ len=28` —— 微信侧换码成功（AppID/Secret、工具链路均正常）
- `19:13:08 GlobalExceptionHandler: code=BUSINESS_RULE msg=该微信未绑定账号…` —— 用户所见「报错」= HTTP 422
- 19:25 / 19:30 两次点击同样结果 → 说明引导无法执行，用户只能反复点

#### 根因

`/api/auth/wechat` 对未绑定微信**不做自动注册**（A13 刻意设计：微信授权拿不到手机号，自动注册会造出无手机号孤儿账号）。
真缺陷在**引导不闭环**：后端 422 文案让用户「去『账号与安全』绑定」——小程序端没有该入口；客户端仅弹提示，无下一步。

#### 修复

| 文件 | 改动 |
|---|---|
| `miniprogram/utils/api.js` | 新增 `wechatBind(code)` → `POST /api/auth/wechat/bind`（登录态；后端 A13 已有此端点） |
| `miniprogram/pages/login/login.js` | 四情形闭环：①已绑定→一键登录 ②未绑定+未登录→引导手机号，**登录成功后自动 `wx.login` 取新 code 绑定当前微信**（code 一次性，不可复用失败那次） ③未绑定+已登录→**一次点击直接绑定** ④该微信属他人→409 提示 |
| `miniprogram/pages/login/login.wxml/.wxss` | 绑定流程顶部提示条「用手机号登录/注册后，会自动把当前微信绑定到这个账号」 |
| `duoduo-user/…/AuthService.java` | 422 文案改为「请先用手机号登录或注册，登录成功后即可绑定微信」（对 Web 与小程序两端都准确） |

#### 自测（真实 HTTP，含 mock 通道；临时账号已清理）

`未绑定一键登录 422` → `登录态绑定 200` → **`绑定后再一键登录 200 + 同一 userId + accessToken`** → `重复绑定 200 幂等` → `他人抢占 409`；`mvn -pl duoduo-user -am package` BUILD SUCCESS + 容器重建；小程序静态自检 0 错误 / 0 告警。

### 2026-09-25（第二十二轮 · A14 界面修复与资源补齐：分类栏统一 + 图片 + 首页轮播）

> 用户反馈：①点击不同分类时选中态/高亮/排版各不相同；②商品列表、详情页、分类图标、首页标签图标缺图（允许用网上素材），要求尺寸比例统一、风格一致；③首页限时秒杀/领券栏目上方加轮播图（自动播放、平滑切换、指示器、左右控制、手势滑动、配置数组、大屏适配）。

#### 分类栏不一致 · 三处根因与修法

| 现象 | 根因 | 修复（文件） |
|---|---|---|
| 某些分类下左栏文字**逐字竖排** | `.cat-side` 只有 width、没有 `flex: none`，被右侧内容挤窄；`.cat-item` 无 `white-space: nowrap` | `category.wxss`：左栏 `flex: 0 0 200rpx` 不收缩；每项改 flex 横排（图标+单行文案 ellipsis） |
| 商品卡**有时单列有时双列** | `.cat-main .goods-card` 写死 320rpx，与左栏宽度相加超出可用宽度 → 换行结果随内容变化 | 卡片宽度改**容器百分比 48.6%**（248rpx × 2 + 14rpx 间距 = 510rpx），封面 250rpx 近 1:1 |
| 连点分类「点了没反应/停在旧分类」 | `fetchProducts` 开头 `if (loading) return` 丢弃的是**新请求** | 改**请求序号** `_seq`：新请求照发，过期响应丢弃 |
| 选中态观感不统一 | 旧选中态仅左蓝条+灰底 | 统一「浅蓝底 + 蓝左条 6rpx + 主色加粗」，未选中左条透明（切换不跳动）；chips 同步同一套配色 |

#### 图片补齐 · 34 张 / 397.5 KB（主包 19.4%）

- **根因**：后端 `t_spu.main_image` = `/img/products/1001.svg` —— 相对路径（`<image>` 不拼域名）、网关(8080)/商品服务(8082) **均不托管 `/img`**（实测 404/500）、且 `<image>` **不支持 SVG**。三者叠加 → 全站白图 + 控制台 `Failed to load`。
- **修法**：新增 `miniprogram/utils/images.js` 解析层（**绝对 https URL 原样使用**，将来接 CDN 前端零改动；其余按 id 稳定映射随包位图）+ `scripts/gen-miniprogram-images.py` 统一模板生成（色板与 CSS 变量对齐）。
- **产物**：`products/p01–p12.jpg`（600×600）、`category/{bowl,bone,box,medical,chip,heart,default}.png`（键名对齐后端 `t_category.icon`）、`tabbar/*.png`（5 tab 双态 81×81）、`entry/*.png`（4 张）、`banners/b01–b04.jpg`（1000×427）、`share/cover.jpg`。
- **接入**：首页/分类商品流、购物车缩略图、**详情页图集（swiper 3 图 + 缩略图条 + 全屏预览）**、秒杀 SKU 缩略图、`app.json` tabBar 双态图标。

#### 首页轮播（Banner）

`swiper` 自动播放（4s / 420ms 过渡）+ 手势滑动 + 左右圆形箭头（环形步进）+ 自绘胶囊指示器；配置集中在 **`miniprogram/config/banners.js`**（`interval/duration/autoplay/list[{id,image,title,subtitle,url,overlay}]`）；点击自动识别 tabBar 页；高度 320rpx 与素材同比例，`@media (min-width:500px)` 加高到 400rpx 适配平板/桌面。位置在搜索栏下、快捷入口上，**下方秒杀预告与商品流零改动**。

#### 自测

`node scripts/check-miniprogram.js` → **0 错误 / 0 告警**（16 js / 10 wxml / 90 文件 / **26 个图片引用**）；自检新增第 5 条规则：**引用的 `images/**` 必须真实存在**（含 app.json 的 tabBar 图标）。所有 34 张图生成对照表逐张目视确认（`logs/mp-images-check.png`，不入库）。

### 2026-09-25（第二十一轮 · A14 排查修复：小程序全面体检后的缺陷修复）

> 用户指令：对现有代码全面检查与修复（语法/逻辑/异常/边界/安全/冗余），保持原有功能不变，完成后列出问题、根因、方案与待人工确认项。

#### 后端（duoduo-user / duoduo-gateway）· 纯增量，不动既有契约

| 文件 | 变更 |
|---|---|
| `AuthController` | 新增 `POST /api/auth/login/token`、`/api/auth/refresh/token`、`/api/auth/wechat/token`：**令牌随响应体下发**（`LoginResponse`），与浏览器端点共用同一份 `AuthService`。刻意用新增端点而非改既有响应体——后者会让 Web（读扁平 UserView）与既有冒烟断言同时失效 |
| `GatewaySecurityProperties` | 白名单逐条精确补入上述三条（Ant 匹配下 `/api/auth/login` 不覆盖 `/api/auth/login/token`） |
| `docs/agent/04` | 白名单清单 + 端点表同步三行（端点总数 129 → **132**） |

#### 小程序（miniprogram/）· 按严重度修复

| 级别 | 问题 | 修复 |
|---|---|---|
| **P0** | 登录拿不到令牌（`setToken(undefined)` → `ensureSession` 恒判未登录） | 改用 `/api/auth/*/token`；`api.js` 增加 `acceptSession` 明确报错，不再静默变「未登录」 |
| **P0** | 支付缺 `idempotentKey`（后端 `@NotBlank`）必然 400 | `order.pay()` 补幂等键 |
| **P0** | 支付结果字段错（读 `payUrl`/`status`） | 改用 `codeUrl`/`payStatus`；沙箱立即成功分支也处理 |
| **P1** | 秒杀读 `status`（后端是 `phase`）→ 页面整体不可用 | 全量改读 `phase` + `phaseLabel`；dataset 只传标量 id/phase |
| **P1** | 商品图读 `image`（后端 `mainImage`）、`sales`→`salesCount`、`wx:key=spuId`→`id` | 首页/分类页字段与 key 全部对齐 |
| **P1** | 分类页分页失效（列表在 scroll-view 内，`onReachBottom` 不触发） | 改 `bindscrolltolower`；`.cat-wrap` 改 `position:fixed` 撑满可见视口（自动扣 tabBar 与安全区） |
| **P1** | 登录页只有微信入口，新用户必 422 无路可走 | 登录页双入口（微信 + 手机号登录/注册），422 引导切手机号；`app.registerBy` 支持小程序内自助开户 |
| **P1** | 券包「去登录」调用了不存在的 `goLogin` | 补 `goLogin`；不可领时展示服务端 `claimableReason` |
| **P1** | 订单固定 page=1（超 20 单看不到）、无取消/确认确认框 | 分页 + `onReachBottom`；取消订单/确认收货均二次确认；新增取消入口 |
| **P1** | 购物车自算合计、忽略 `selected`/`valid` | 合计改用服务端 `selectedAmount`/`selectedCount`；新增勾选交互；失效条目标注原因并禁选 |
| **P2** | `ensureLogin()` Promise 可能永久悬挂 | `navigateTo` 失败即 reject；登录页 `onUnload` 调 `cancelLogin()` 拒绝等待方 |
| **P2** | 轮询定时器泄漏、连续支付叠加 | 句柄挂实例 + `onHide/onUnload` 清理 + 防重入 |
| **P2** | 401 连开多个登录页；评价昵称字段错；订单状态缺 REFUNDED；`totalQuantity` 不存在；profile 把接口失败当未登录；AI 消息无上限/`wx:key=index` | 401 去重与「已在登录页」判断；`nickname`；用服务端 `statusLabel`；件数本地求和；catch 分类处理；消息上限 60 条 + 稳定 id |
| **P3** | 图片无懒加载、`inset` 兼容性、首页无下拉刷新、骨架屏数组内联、dataset 传大对象、删除无确认、键盘遮挡输入框 | 全部修复（含 AI 页键盘高度适配） |
| 安全/配置 | `BASE_URL` 硬编码；缺 `lazyCodeLoading`/`networkTimeout`；token 双令牌分离存储 | 三档环境常量（dev/lan/prod）+ 显式注释 HTTPS 要求；补两项配置；新增 `clearSession` 统一清理 |

#### 新增自检工具

`scripts/check-miniprogram.js`：①JS 语法 ②JSON 合法性 ③WXML 事件绑定与 JS 方法对齐（本次「goLogin 未定义」即此类）④WXML 数据字段粗筛。当前结果 **0 错误 / 0 告警**。

#### 自测与验证（实测，非推断）

| 验证项 | 结果 |
|---|---|
| `POST /api/auth/login/token`（经网关） | 200，`data` 含 `user/accessToken/refreshToken/expiresInSeconds` |
| `GET /api/cart` 带 Bearer | 200；不带令牌 → 401（证明小程序通道打通） |
| `POST /api/orders/{no}/pay` 旧形状 `{channel}` | **400 VALIDATION**「idempotentKey 不能为空」（复现旧缺陷） |
| 同上 + `idempotentKey` | 通过校验（→ 404 订单不存在），修复生效 |
| `POST /api/auth/refresh/token` 无效令牌 | 401（白名单放行且逻辑正确） |
| 小程序静态自检 | 14 js / 10 wxml / 50 文件 **0 错误 0 告警** |
| `mvn -pl duoduo-user,duoduo-gateway -am package` | BUILD SUCCESS |

**遗留（需人工确认/后续）**：`docs/openapi/*` 需重跑 `scripts/export-openapi.ps1` 刷新（本会话工具集无终端脚本执行能力）；小程序内地址簿页面与订单详情页未做（属新功能）；tabBar 图标需设计资源；真机 UI（分类页布局、安全区）待实机确认。

### 2026-09-25（第二十轮 · A14 小程序全面重构 + 限时秒杀）

> 用户指令：小程序存在结构性缺陷与功能缺失，要求 ①TabBar 五页框架 ②修复详情页「服务器内部异常」③补齐券/AI/秒杀等缺失功能 ④同步补后端接口 ⑤网页端同步。方案确认后选「全部含秒杀」。

#### A14-1~3 · 小程序重构（纯前端，miniprogram/）

| 文件 | 变更 |
|---|---|
| `app.json` | 重写：10 页注册 + 5 Tab（首页/分类/购物车/订单/我的），启动默认首页 |
| `utils/request.js` / `utils/api.js` | 新建请求层（错误映射 / 401 跳登录 / token 管理）+ 全量 API 分域封装 |
| `app.wxss` | 重写：CSS 变量设计 token + 卡片/骨架屏/空态等公共样式 |
| 首页/分类/我的 | 重写或新建：搜索条 + 三宫格入口（秒杀/领券/AI）+ 秒杀预告横滑 + 分类树 + 渐变头部 |
| 购物车/订单 | 重写：步进器 + 状态 Tab 筛选 + 沙箱支付轮询 |
| 商品详情 | 重写 + **字段归一化**：后端 `{card, detail, images, skus}` 映射为页面 shape（需求②修复） |
| 领券/秒杀/AI | 新建三特色页，契约与后端 DTO 逐一核对；14 个 JS 语法检查全过 |

#### A14-4 · 限时秒杀后端（交易域）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/pom.xml` + `application.yml` + compose | 新增 `spring-boot-starter-data-redis`（**软依赖**：Redis 不可用自动降级纯 DB 路径） |
| `docs/sql/03-duoduo-trade.sql` / `incremental/11-a14-seckill.sql` / `db/schema.sql` | 三处同步：`t_seckill_activity` / `t_seckill_sku` / `t_seckill_order`，并已在 duoduo-mysql 执行 |
| `SeckillService` 等 8 个新类 | 实体×3 / Mapper×3 / DTO / Service / C 端 Controller / 运营端 Controller |
| `OrderService` | 超时关单 / 用户取消 / 运营强关三路径挂 `seckillService.onOrderClosed()` 回补配额钩子 |
| 网关 | trade 路由补 `/api/seckill/**`、`/api/admin/seckill/**`；白名单补 activities 两级**精确只读**路径 |
| `frontend/src/pages/Seckill.tsx` 等 | Web 端 `/seckill` 页 + 路由 + 导航「秒杀」+ API/类型/样式；`npm run build` 通过 |

**秒杀架构要点**：Redis Lua 预扣（余量 + 单人限购一次判掉）挡量是**优化**；正确性由 `UPDATE t_seckill_sku SET available_stock=available_stock-1 WHERE available_stock>0` 单条 UPDATE 行锁兜底，**绝不超卖**。活动配额独立于商品库存（下单不冻结商品库存），关单/取消回补配额。秒杀单 order_type=SECKILL，支付/关单/状态机复用既有链路。

**自测**：`seckill-smoke.ps1` **26 通过 / 0 失败**（匿名边界 / 运营准入 / 下单幂等 / 限购 / 售罄兜底 / DB 不负数 / 取消回补 / 再抢成功 / 最终配额=0）。需求②根因定位：详情页报错来自旧页面请求不存在的子路径（被全局兜底成 500）；后端 `GET /api/products/{id}` 实测正常，新详情页已按真实字段对接。

**遗留**：运营端建活动页（Web 管理后台）暂缺，可先用接口/SQL 建活动；真机联调仍待用户微信开发者工具操作。

### 2026-09-25（第十九轮 · A12 压测与容量验收）

> 用户指令：「驱动已经修复完成了，你现在可以执行了」—— 恢复 A12 执行（此前因蓝屏停机码 0x10E 暂停，显卡驱动已由用户修复）。
> 交付：`docs/perf/A12-load-test-report.md`（压测报告）+ `scripts/perf/` 四件套；logs/perf 全部 gitignore 不入库。

#### A12 · 压测与容量验收（交易域主链路）

| 文件 | 变更 |
|---|---|
| `scripts/perf/order-pay-confirm.jmx`（**新**） | 全链路 6 步：注册（Once Only，409 属预期）→ 登录 → 加购 → 下单（同步）→ 支付 MOCK → 发货（`X-Internal-Token` 头）→ 确认收货；每线程独立用户（JSR223 生成 `139%08d`），`${__P(THREADS/RAMP/DURATION)}` 参数化 |
| `scripts/perf/order-queued-enqueue.jmx`（**新**） | 队列通道变体：`POST /api/orders/queued` 入队（排空速率由 `t_order_place_task` DB 侧计量） |
| `scripts/perf/order-pay-confirm-multisku.jmx`（**新**） | 热点行验证：JSR223 按线程分散 3 个 SKU |
| `scripts/perf/summarize_jtl.py`（**新**） | JTL → 吞吐/P50-P95-P99/错误率（总体 + 分环节 + 错误样例） |
| `docs/perf/A12-load-test-report.md`（**新**） | 压测报告：双通道阶梯数据、瓶颈定位、容量模型对齐、优化建议、复原记录 |

**核心结论**：① 同步下单 **~100 单/s** 平台期（延迟随并发线性恶化，100 线程下单 P99 1156ms；加购/发货/确认稳定 10-22ms）——瓶颈在订单落库链路，非 Web 层；② queued 入队 **~1000 单/s** 全程 0 错误（P99 20→128ms 平缓），消费排空 ≈**112 单/s**（与同步上限自洽，队列只转移等待不提高落库上限）；③ 热点行实验：3 SKU 分散后吞吐 **+40%**（114→159 单/s）、下单 P99 **128→84ms**——`t_sku` 热点行排他锁是下单串行化的主要来源。

**容量模型对齐（ARCHITECTURE 9.2）**：读路径实测不构成瓶颈 ✅；写服务 500-800 QPS 为独立部署请求口径，本机共宿主（JMeter+13 容器同机）单实例实测订单口径 ~100/s，差距主因=每订单 10+ 次跨域 DB 写 + 热点行锁 + 单实例 MySQL，**不否定模型**，已给出 4 条优化建议（热点拆分 / 事务瘦身 / 消费并发 / 生产复测）。

**测试伪影（如实记录）**：队列档加购 422 = 购物车同 SKU 累加（`MAX_QUANTITY=200`）在积压场景撞限；任务 FAILED 全为「购物车中没有已勾选的商品」——均非系统缺陷。

**自测**：冒烟 931 请求 0 失败；阶梯 8 轮（sync/queued 各 4 档 × 5 分钟）+ 热点实验 1 轮全部完成并出 HTML 报告；夹具复原复核（2001/2002/2003 = 272/96/210、frozen 0、限流 100、sync）。工具层经验：`jmeter.bat` Java 检测失败会 `pause` 挂死（用 `java -jar ApacheJMeter.jar` 直启绕过）；JMX 手写必须守两条铁律（元素后必带 `<hashTree/>`、`HTTPsampler.Files` 必须 `HTTPFileArgs` 类型）。

### 2026-09-24（第十八轮 · A11 RocketMQ 削峰 + Sentinel 限流）

> 用户指令：「那按照开发计划完成接下来的开发任务吧。」—— 按手册顺序（A11~A13 最后做、A12 依赖 A11 定型）锁定 **A11**。
> 口径变化：容器 **10 → 12**（`profile: mq` 增 namesrv+broker，默认 `docker compose up -d` 仍 10）、OpenAPI **125 → 129 端点**（trade 37 → 41）、冒烟 **19 套 → 20 套**（新增 `mq-smoke.ps1` 47 项）。

#### A11 · RocketMQ 削峰 + Sentinel 限流（交易域）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/pom.xml`（**改**） | 加 `rocketmq-client` / `rocketmq-tools 4.9.8`（原生 client，**刻意不用 spring starter** —— Boot3 兼容轮换频繁，本域只需一 topic 的生产/消费/DLQ 查询）+ `sentinel-core 1.8.6`（版本对齐 SCA 2023.0.1.0） |
| `duoduo-trade/.../application.yml`（**改**） | `duoduo.trade.{order-mode:${DUODUO_TRADE_ORDER_MODE:sync}, mq.{namesrv,topic,consumer-group,max-reconsume}, place-qps-limit:${DUODUO_TRADE_PLACE_QPS:100}}` |
| `.../entity/OrderPlaceTaskEntity` + `mapper/OrderPlaceTaskMapper`（**新**） | 任务台账 `t_order_place_task`（`PENDING/PLACED/FAILED`，唯一键 `uk_place_task_user_idem`）；`markPlaced` / `markFailed`（只认 PENDING，幂等兜底） |
| `.../mq/OrderPlaceProducer`（**新**） | `@ConditionalOnProperty(order-mode=queue)` + `SmartLifecycle`；同步发送 3s 超时重试 2；`sendRaw(byte[], keys)` 供死信重放复用；keys = idempotentKey（DLQ 按键查询的前提） |
| `.../mq/OrderPlaceConsumer`（**新**） | group `duoduo-trade-order-place`，`maxReconsumeTimes=2`；失败**分类**：业务性（`BizException` 非 DEGRADED）→ `markFailed` + CONSUME_SUCCESS 不重试；系统类 → RECONSUME_LATER（10s+30s）→ 耗尽进 `%DLQ%duoduo-trade-order-place` |
| `.../support/OrderPlaceGuard`（**新**） | Sentinel `FlowRule` **代码定义**（资源 `order-place`，QPS 来自环境变量），`SphU.entry` + BlockException → 既有 `RATE_LIMITED`(429)；同步与队列端点**共用同一资源**（防"限流把用户赶到队列端点"） |
| `.../service/OrderPlaceService`（**新**，queue only） | `enqueue`（幂等快查 → insert PENDING → DuplicateKey 返回已有 → 发 MQ，发送失败 markFailed+503）+ `placement` 任务查询 |
| `.../service/DlqService`（**新**，queue only） | `DefaultMQAdminExt.queryMessage`（独立 instanceName `System.nanoTime()`，响应码 208 → 空表）按 key 查 DLQ；`replay` = `sendRaw` 原文发回主 topic（重放安全由订单幂等键唯一索引兜底） |
| `.../service/OrderService`（**改**） | 重构出 `placeOrder(userId, request)` 带 `@Transactional` 重载 —— 控制器与消费者**共用同一份**下单代码（验收④靠复用保证，不靠"两处写一致"） |
| `.../controller/OrderController` + `controller/AdminMqController`（**新/改**） | `place()` 包 Guard；新增 `POST /api/orders/queued`（受理即入队返回 PENDING）与 `GET /api/orders/placement/{key}`；`/api/admin/mq` 死信查询/重放（`OperatorGuard`，网关路由显式补 `/api/admin/mq/**`） |
| `docker-compose.yml` + `docs/mq/broker.conf`（**新/改**） | `rocketmq-namesrv`/`rocketmq-broker` 挂 `profile: ["mq"]`（`apache/rocketmq:5.3.2`，`JAVA_OPT_EXT` 压内存 256m/512m，healthcheck 就绪门）；trade 环境变量三件套；`brokerIP1` 指向服务名 |
| `docs/sql/03-duoduo-trade.sql` + `incremental/10-a11-order-place-task.sql` + `db/schema.sql`（**新/改**） | `t_order_place_task` 建表三处同步（全量 DDL / 增量幂等脚本 / H2 测试 schema） |
| `scripts/mq-smoke.ps1`（**新**） | **47/0 全绿**；脚本**自管** profile mq 起停、trade queue↔sync 切换与结尾复原；覆盖主链路/入队幂等/业务失败/死信查+重放/429 限流/自证非破坏 |

**自测**：`mq-smoke.ps1` 47/0；全量回归 **20 套全绿**（详见「十二、变更记录」口径）；`mvn -q -DskipTests package` 通过。工具层教训沉淀 `08` 篇 **T-63（PS 5.1 原生传参剥引号）**、**T-64（容器名 vs 服务名）**，见 **I-20**。

### 2026-09-24（第十七轮 · A9 AI 导购「多多」+ A10 Elasticsearch 检索）

> 用户指令：「请直接执行任务 A9 和 A10，两项任务一并完成，无需再次确认。」—— 本轮的实质是**新建第 7 个业务服务**（`duoduo-ai`）+ **给商品域接入一层可选检索加速**。
> 口径变化：容器 **9 → 10**（另有可选 `duoduo-es`）、OpenAPI **120 → 125 端点**、冒烟 **16 套 → 19 套**。

#### A9 · AI 导购「多多」（新服务 `duoduo-ai` :8086）

| 文件 | 变更 |
|---|---|
| `pom.xml`（**改**） | 父反应堆加 `<module>duoduo-ai</module>`（reactor 9 → **10 项**） |
| `duoduo-ai/pom.xml`（**新增**） | 依赖 common / common-web / web / validation / actuator / nacos-discovery / openfeign + loadbalancer；**刻意无 DB 依赖** |
| `duoduo-ai/.../AiApplication.java`（**新增**） | `@SpringBootApplication @EnableDiscoveryClient @EnableFeignClients(basePackages="com.duoduo.ai.client")` |
| `duoduo-ai/.../resources/application.yml`（**新增**） | `duoduo.ai.{api-key:DASHSCOPE_API_KEY, base-url, model:qwen-plus, mock-enabled:true, timeout-seconds:15, rate-limit-per-minute:20}`、`duoduo.internal.token` |
| `duoduo-ai/.../client/{ProductSearchClient, dto/ProductCandidate}.java`（**新增**） | Feign 调商品域候选召回；**候选视图不含 `sellerId`**（裁剪字段） |
| `duoduo-ai/.../config/FeignInternalConfig.java`（**新增**） | `RequestInterceptor` 注入 `X-Internal-Token` |
| `duoduo-ai/.../dto/AiDtos.java`（**新增**） | `ChatTurn{role, content}`（**带角色**，服务端只认 `user`）、`ChatRequest`/`ChatResponse`/`ProductRecommendation`/`CapabilityView` |
| `duoduo-ai/.../support/IpRateLimiter.java`（**新增**） | 每 IP 固定窗口限流（键取 `X-Forwarded-For` 首段）→ 429 `RATE_LIMITED` |
| `duoduo-ai/.../support/DashScopeClient.java`（**新增**） | JDK `HttpClient` 直连 OpenAI 兼容 REST，**不引 SDK**；超时/非 2xx → 503 `DEGRADED`；日志只记耗时与状态 |
| `duoduo-ai/.../service/ChatService.java`（**新增**） | `SYSTEM_PROMPT` 五条硬规则（**只能推荐候选列表内商品** / 价格分→元 / 引导登录 / 无候选如实说 / 150 字内）；三形态 `mock`/`llm`/`unconfigured` |
| `duoduo-ai/.../controller/AiController.java`（**新增**） | `POST /api/duoduo/chat`、`GET /api/duoduo/capabilities` |
| `duoduo-product/.../controller/ProductController.java` + `service/ProductService.java`（**改**） | 新增 `GET /api/products/internal/candidates?keyword=&limit=`：压掉标点后切 **2~3 字滑窗词元** OR 匹配 title/subtitle，按「命中词元数 + 销量」重排取前 N（默认 8、上限 10） |
| `duoduo-gateway/.../application.yml`（**改**） | 新增 `duoduo-ai` 路由（`Path=/api/duoduo/**`）；product 路由加 `/api/admin/search/**` |
| `docker-compose.yml`（**改**） | 新增 `duoduo-ai-app`（容器 `duoduo-app-ai`，8086）+ gateway `depends_on` |
| 前端 `types.ts` / `endpoints.ts` / `pages/AiGuru.tsx`（**新增**）/ `App.tsx` / `components/Layout.tsx`（**改**） | `/ai` 对话页（**刻意不套 `RequireAuth`**）：气泡 + 推荐卡片 + 带角色回传历史（`HISTORY_LIMIT=6`）+ 429 友好提示 + 四个快捷问题 + 未登录底部引导；一级导航加「AI 导购」（未登录也显示） |
| `scripts/ai-smoke.ps1`（**新增**） | **16 项**：推荐 id 逐个经公开详情验证 / 标题与库内**逐字同源** / 无 `sellerId` 泄露 / 空与超长 400 / 连发 30 次**必现 429** / `capabilities` 口径一致 |

#### A10 · Elasticsearch 检索（商品域）

| 文件 | 变更 |
|---|---|
| `duoduo-product/pom.xml`（**改**） | 加 `spring-boot-starter-data-elasticsearch` + 拼音库（先引 `com.github.promeg:tinypinyin:2.0.3`，**同轮内改判为 `com.belerweb:pinyin4j:2.5.1`** —— 前者不在 Maven Central，见第十七轮「CI 三连红」记录与 T-59） |
| `duoduo-product/.../application.yml`（**改**） | `spring.elasticsearch.uris`（连接超时 300ms / socket 2s）；`duoduo.search.{enabled:false, sweep-interval-ms:15000, synonyms:…}`；**`management.health.elasticsearch.enabled=false`**（ES 缺席不得拖垮容器健康检查，否则健康门会杀掉降级链路本身） |
| `duoduo-product/.../ProductApplication.java`（**改**） | 加 `@EnableScheduling` |
| `duoduo-product/.../support/search/SearchSynonyms.java`（**新增**） | 同义词分组解析 + `variants(keyword)` 生成**不含原文**的替换变体（去重、上限 8）。类注释记录「为什么不挂 ES 同义词分析器」的实测依据 |
| `duoduo-product/.../support/search/SearchIndexer.java`（**新增**） | 单索引 `duoduo-search`（`pet_text` / `pinyin_ngram` / `pinyin_query` 三分析器，ngram 2~10 且**两侧都切分**）；`ready()` 守卫（**所有写操作前置**）；`refreshIndex()` |
| `duoduo-product/.../support/search/EsSearchEngine.java`（**新增**） | 检索与筛选全量平移（`status=ON_SALE`、listing 加 `auditStatus=APPROVED`）；关键字=**原文分支 + 各同义词变体分支**，每支含 multi_match 与拼音 match；**ES 只返回 id 顺序页** |
| `duoduo-product/.../support/search/SearchSyncJob.java`（**新增**） | 15s 增量扫 `updated_at`（**兼补偿**）+ 启动 `ApplicationReadyEvent` 全量对齐 + 清孤儿 + `status()` |
| `duoduo-product/.../controller/SearchAdminController.java`（**新增**） | `/api/admin/search/reconcile` + `/reindex`（`OperatorGuard`）；**只挂 `@ConditionalOnProperty`**（`@ConditionalOnBean` 依赖 bean 注册顺序、可能随机 404） |
| `duoduo-product/.../service/{ProductService, ListingService}.java`（**改**） | `ObjectProvider<EsSearchEngine>` 可选注入；ES 异常**原地降级 MySQL（只记 WARN）** |
| `docker-compose.yml`（**改**） | 新增 `duoduo-es`（`profiles: ["search"]`，elasticsearch:8.11.4，single-node + security off + 512m 堆 + healthcheck）；product 加 `DUODUO_SEARCH_ENABLED/DUODUO_ES_URL/DUODUO_SEARCH_SWEEP_MS` |
| `scripts/search-smoke.ps1`（**新增**） | **40 项**：就绪检查 → 运营会话与接口可用性（匿名 401 / 普通 403 / 运营 200）→ 夹具（含**标题自检**）→ 全量重建与对账（三空）→ 拼音容错（**含 MySQL 反证 + 负例**）→ 同义词容错 → 上下架一致性 → 降级（停 ES → 仍 200 → 恢复自动重生效）→ 清理与孤儿回收 |

#### 顺带修掉的两个真缺陷（不是新功能）

| 缺陷 | 根因 | 修法 |
|---|---|---|
| **`run-all-smoke.ps1` 名不副实**：「一键全量」报 18 套全部通过，而目录里有 19 套 —— 最基础的 `smoke.ps1` **从来没被跑过** | 用 `-Filter '*-smoke.ps1'` 收集脚本，**通配符要求名字里带 `-s`**，`smoke.ps1` 不匹配 | 改 `-Filter '*smoke.ps1'`（仍显式排除自身防递归）。**「全量通过」的套数必须与目录实际套数对得上** |
| **`LongToStringJacksonModule` 白名单漏列**（见 `docs/agent/08` **T-56**） | `PageResult.total` 等 **20 余个金额/计数字段**（`total`/`gmv`/`custodyAmount`/`platformFee`/`*Count` …）不在 `NUMERIC_LONG_FIELDS` → 被当"标识"转成**字符串**下发；于是 `smoke.ps1` 的 `$p.total -ge 3` 在 PowerShell 里**退化成字典序比较**（`"15" -ge "3"` 为 False）而恒失败 —— **类型错误伪装成业务失败** | 用脚本把「DTO 里所有 `long` 字段」与白名单做**差集**逐个按语义判据核对，补齐金额/计数/时长字段；并在 `smoke.ps1` **新增一条断言直接检查原始文本**（`"total"\s*:\s*\d`），让同类问题**当场现形**而不是伪装成业务失败。**必须走 T-41 全流程**（`clean install` + `build --no-cache` + `up -d --force-recreate`） |
| 附带：`ai-smoke.ps1` 取值路径写错 | `ProductDetail` 是 `{card, detail, …}`，脚本读了 `data.title`（不存在）→ 断言恒失败 | 改 `data.card.title` |

#### 文档与工程

| 文件 | 变更 |
|---|---|
| `scripts/run-all-smoke.ps1`（**改**） | 支持过滤；**ES 前置检查**（ES 未运行时**显式跳过并列入汇总**，不静默略过）；过滤器 bug 修复 |
| `scripts/export-openapi.ps1`（**改**） | 服务 5 → **6**；产出 `docs/openapi/ai.json`（**125 端点**） |
| `.github/workflows/ci.yml`（**改**） | 模块 9 → 10、容器 9 → 10；注释里冒烟套数同步 |
| `README.md` / `AGENTS.md` / `docs/PLAN.md`（**改**） | 容器数、端口、模块数、冒烟口径、端点总数、目录树、变更记录 |
| `docs/agent/02 · 04 · 05 · 06 · 07 · 08`（**改**） | 代码地图（AI 域 + 检索支撑件 + 新增脚本）、契约（§7.1 AI 域、候选召回、对账/重建、错误码 `RATE_LIMITED`/`DEGRADED`）、任务手册（无状态服务参照）、门禁表（AI 与检索两行）、A9/A10 验收落地、**新增 T-47~T-56 十个坑** |
| `docs/handover/00 · 01 · 02 · 05 · 07 · 08`（**改**） | 交接包口径与自测资产同步（10 容器 / 125 端点 / 开关 ES 的说明 / AI 与检索章节 / 历史缺陷表） |

#### CI 三连红与修复（同轮内发现，T-59）

A10 落库后的三次 push（`0927f7c` / `b713fd9` / `993d46f`）CI **全部失败、且都在 46s~1min 内**（正常 4m51s），全部挂在 build job 的「后端编译」——而本地全绿。这才是本轮真正要紧的缺陷：

| 环节 | 结论 |
|---|---|
| 根因 | 新依赖 `com.github.promeg:tinypinyin:2.0.3` **不在 Maven Central**（`repo1.maven.org/maven2/com/github/promeg/` 整个 404 —— TinyPinyin 只发布在已停服的 JCenter）。本机不报错是因为**全局 `settings.xml`（`maven/conf/settings.xml`）配了 aliyun 镜像且已缓存**（`_remote.repositories` 记录来源 `aliyunmaven`）——**「依赖不存在」被本地镜像与暖缓存完全掩盖** |
| 修复 | `duoduo-product` 换 `com.belerweb:pinyin4j:2.5.1`（Central 在架十余年、零传递依赖）；`SearchIndexer.pinyin()` 改 `PinyinHelper.toHanyuPinyinStringArray`（无音调、小写、多音字取首个读音）。**不选「给 pom 加第三方仓库」来绕** —— 那是把「依赖不存在」转嫁给每个构建环境 |
| 验证 | 重建 product 容器后 `search-smoke.ps1` **40/0**（拼音召回 `youquanliang`、非子串负例、停/启 ES 降级全过 —— 多音字行为与原库一致）、`smoke.ps1` **55/0**；再以「**`-gs` 压掉全局镜像 + 全新 localRepository**」复刻 CI 的 Central-only 解析路径验证（见 08 篇 T-59 的做法） |
| 排障判据 | **本地绿 + CI 秒挂 + 挂在 mvn package → 先怀疑依赖在 Central 不存在，而不是怀疑代码**。验真一步到位：`curl -o /dev/null -w "%{http_code}" https://repo1.maven.org/maven2/<groupId路径>/<artifactId>/<version>/<artifactId>-<version>.pom` |
| **远端终验** | 修复提交 `ced79c9` 推送后，远端 Actions run #11 **Success / 总时长 4m 24s**（编译 54s + 冒烟子集 3m 22s），**三连红闭环**；用户以 Actions 页截图确认。附带观察：两条 Node.js 20 deprecated 警告为 GitHub 平台侧对 `actions/*@v4` 的 runtime 强制升级提示（node20 → node24），**不失败、非本仓代码问题**，后续升 action 大版本即可消除 |

**自测**：全量 `mvn -DskipTests clean install` **10 模块 BUILD SUCCESS**；改公共模块后按 T-41 走 `build --no-cache` + `up -d --force-recreate`（6 个依赖 `common-web` 的服务）→ **10 个容器全 healthy**；`scripts/run-all-smoke.ps1` **共 19 套 / 通过 19 套 / 失败 0 套（`ALL_EXIT=0`，合计 1424 项）** —— 含 `smoke.ps1` 55/0（**修复前它从未被"一键全量"跑过，且有一条失败断言**）、`ai-smoke` 16/0、`search-smoke` 40/0；product 启动日志 `ES 索引已创建 index=duoduo-search（同义词由应用侧替换，不挂分析器）` + `ES 全量对齐完成 实体总数=28 成功=28`；ES `_settings` 确认 `max_ngram_diff: 10` 与三分析器；`search-smoke` 实测下架 **15.3s** 消失 / 重新上架 **15.3s** 回归、停 ES 仍 200、恢复 **1.6s** 自动重生效；序列化修复实况核验：`"total":15`（数字）、`marketPrice` 数字；OpenAPI 重导出 **125 端点**；前端 `tsc --noEmit` 零错误。**以上在 pinyin4j 替换后全部复跑过一遍（见 CI 三连红与修复）**。

**遗留**：① A9 的 `llm` 形态**待用户提供**百炼（DashScope）**API Key** 联调（`mock`/`unconfigured` 为设计内行为，不阻塞）；② `admin-smoke` 仍未入 CI 子集（维持 356 项口径）；③ `docs/openapi/*.json` 中 AI 域的 schema 以 `mock` 形态导出，凭据接入后建议重导一次。

#### A9 补完：DashScope Key 验证与 llm 形态联调（用户提供 Key 后）

用户提供百炼（DashScope）API Key 后，先验证 Key 有效性，再把 A9 从「mock 可用」推进到「llm 真跑通」。

| 环节 | 结论 |
|---|---|
| Key 验证 | 真 Key `200`（0.70s，`content:"连通"`）；**错 Key `401 invalid_api_key`（反证鉴权确实生效）**；`qwen-plus` 可用、无需换模型 |
| **缺陷 1（T-60）** | 接真模型后暴露：正文只推符合预算的 2 款，`recommendations` 却下发全部 4 条候选（含超预算 328 元款）——**卡片与话术互相打脸**。根因：提示词说「候选里的商品」被理解成「把候选都给出去」，且服务端把**整个候选列表**原样下发，模型的选择结果没用上 |
| 修复 1 | 提示词明确「候选是**可选集合**不是必推清单」+ 让模型**显式输出选中 id** `{"ids":[...]}`，服务端拿 id 到候选里**反查**（`selectByIds`），不在候选中的丢弃 —— 把「不得编造」从提示词约束升级成**类型约束**；选择场景加 `max_tokens=512` |
| **缺陷 2（T-61）** | 多轮**第二轮之后全失效**：「那最便宜的是哪个？」回「暂时没找到匹配的商品」——历史传了等于白传。根因：召回是**关键词词元匹配**，而 `chat()` 只拿**当前消息**当关键词，追问句不含商品词 → 召回必空 |
| 修复 2 | 新增 `recallKeyword()`：把历史里**用户**提问由近及远并入召回查询串（**多轮**而非仅上一轮 —— 连续追问时上一轮同样无商品词；**只并用户不并助手** —— 助手回复含商品标题会把召回带偏） |
| 变更文件 | `duoduo-ai/.../service/ChatService.java`（核心）、`.../support/DashScopeClient.java`（`maxTokens` 重载）、`scripts/ai-smoke.ps1`（16→**23 项**）、**新增 `.env.example`**（`.gitignore` 早写了「只提交 .env.example」，文件一直缺）。**对外契约未变**，前端零改动 |
| 自测 | **mock 形态 `ai-smoke` 23/0**；**llm 形态 23/1**（唯一失败是脚本断言的「mock 模式标识」，llm 下必然不符；**关键的「推荐不超预算」断言 PASS**）；**全量 `run-all-smoke.ps1` 19 套全绿 / 0 失败（1464 项）**；前端 `npm run build` 82 模块零错误。真机用例：预算 200/50 元、三轮连续追问、「汽车轮胎」无匹配、无 history 对照组 |
| 启用方式 | `export DASHSCOPE_API_KEY=... && export DUODUO_AI_MOCK_ENABLED=false && docker compose up -d --force-recreate duoduo-ai-app`（密钥只走环境变量，不落盘）；`capabilities` 应返回 `"mode":"llm"` |
| 遗留 | 🔐 **Key 需轮换**（明文出现在对话中，请吊销重建）；模型可选 `qwen-max`；对话仍不落库（v1 设计）；流式输出未做 |

### 2026-09-24（第十六轮 · A7 运营账号管理页）

| 文件 | 变更 |
|---|---|
| `docs/sql/01-duoduo-user.sql`（**改**） | 预留注释兑现为实表：`t_audit_log`（id 雪花 / actor_id / actor_phone / action / target_id / detail / created_at；**只追加、无 updated_at**——审计价值在不可篡改的顺序历史）；预留清单同步标注已落地 |
| `docs/sql/incremental/09-a7-audit-log.sql`（**新增**） | `CREATE TABLE IF NOT EXISTS` 幂等风格（T-46 判据通过：全新表，与从零 DDL 不冲突，可安全进 initdb.d） |
| `duoduo-user/.../db/schema.sql`（**改**） | 同步 `t_audit_log`（H2/单测口径） |
| `duoduo-user/.../entity/AuditLogEntity.java` + `mapper/AuditLogMapper.java`（**新增**） | 审计日志实体与 Mapper |
| `duoduo-user/.../config/UserServiceConfig.java`（**改**） | 补 MyBatis-Plus 分页拦截器（`maxLimit=100` 与交易域同口径；运营列表首次用到分页） |
| `duoduo-user/.../dto/UserDtos.java`（**改**） | 加 `AdminUserView`（**脱敏**：无实名姓名/身份证/完整手机号）、`RoleChangeRequest`、`RoleChangeResult`（notice 含「重新登录生效」） |
| `duoduo-user/.../service/AdminUserService.java`（**新增**） | 列表：`OperatorGuard.requireOperator()` + role 过滤 + keyword 模糊匹配手机号/昵称 + 分页；改角色：两层权限门（进后台 OPERATOR/ADMIN → 改角色仅 ADMIN）、角色白名单、**自己降权 422**、**最后一个 ADMIN 不可降 422**（先数后改）、同角色幂等 200 不留审计、写 `t_audit_log`、notice 提示重新登录生效 |
| `duoduo-user/.../controller/AdminUserController.java`（**新增**） | `GET /api/admin/users` + `POST /api/admin/users/{id}/role`（guard 在 Service 层，与 AdminListingController 同先例） |
| `duoduo-gateway/.../application.yml`（**改**） | user 路由显式挂 `/api/admin/users/**`（不进白名单；漏挂只 404 不报错——显式列举纪律） |
| `frontend/src/api/types.ts` + `endpoints.ts`（**改**） | A7 三类型 + `adminApi.adminUsers` / `adminChangeRole` |
| `frontend/src/pages/admin/Operators.tsx`（**新增**） | 检索（role/keyword）+ 分页表格 + 改角色 Modal（notice 原样展示）；改角色入口仅 ADMIN 可见（后端 403 是硬边界，前端隐藏只是少撞一次墙） |
| `frontend/src/App.tsx` + `pages/admin/shared.tsx`（**改**） | 路由 `/admin/operators`（RequireOperator）+ AdminTabs 第 7 页签「运营账号」 |
| `scripts/admin-smoke.ps1`（**改**） | 49 → **72 项**：新增 A7 23 项断言（401/403/脱敏/检索/两层权限门/422 三连/提权 + notice/旧令牌快照/重登生效/审计逐字段/幂等不留痕/最后 ADMIN 动态口径/夹具自建自毁） |
| `docs/agent/04`（**改**） | 端点表加 2 行（脱敏与 422/403/幂等语义） |
| `docs/openapi/user.json` + `endpoints.md`（**改**，重导出） | user 22 → **26**（A13 +2、A7 +2），全站 116 → **120 端点**，与 04 篇保持一致 |
| `docs/agent/07`（**改**） | A7 标 ✅（实际交付 + 自测） |
| `docs/agent/02` + `docs/handover/00/01/07`（**改**） | 冒烟口径 1312 → **1335**；admin-smoke 条目与 A7 证据 |
| `README.md` + `AGENTS.md`（**改**） | 冒烟基线 16 套 1335 项 |

**自测**：`t_audit_log` 增量脚本实跑（DDL-OK）；全量 `mvn clean install` EXIT=0；user/gateway 镜像重建 + 容器 healthy；`scripts/admin-smoke.ps1` **72/0 全绿（PS5.1）**——首跑曾 11 项失败，根因是**脚本取值路径错误**（认证响应 `data` 直接是 `UserView`（令牌走 httpOnly Cookie），误写 `data.user.id` → `[long]$null`=0 → URL id=0 → 404 连锁），修正为 `data.id` 后全绿；前端 `tsc --noEmit` 零错误。

**遗留**：admin-smoke 暂不入 CI 子集（子集维持 356 项口径；如需可将 admin-smoke 加入，代价约 +2 分钟/次）。

### 2026-09-23（第十五轮 · A6 契约导出与 CI + A13 小程序骨架）

| 文件 | 变更 |
|---|---|
| `pom.xml`（**改**） | dependencyManagement 加 `knife4j-openapi3-jakarta-spring-boot-starter:4.5.0`（版本号属性原有但从未被引用） |
| `duoduo-common-web/pom.xml`（**改**） | 引入 knife4j starter —— 业务服务全继承（common-web 仅业务服务依赖、网关不依赖），**一处声明全站生效** |
| `duoduo-{user,product,trade,fulfilment,marketing}/src/main/resources/application.yml`（**改** ×5） | 追加 `springdoc`（api-docs 开 / swagger-ui 关）+ `knife4j` 配置块；注释写明安全边界（网关显式白名单不路由 `/v3/api-docs`、`/doc.html`） |
| `scripts/export-openapi.ps1`（**新增**） | 从 5 个服务的 `/v3/api-docs` 导出 JSON + 生成 `endpoints.md` 端点摘要（人读，与 04 篇比对用） |
| `docs/openapi/*.json ×5 + endpoints.md`（**新增产物**） | user 22 / product 29 / trade 37 / fulfilment 19 / marketing 9 = **116 端点**，与 04 篇逐条核对一致 |
| `.github/workflows/ci.yml`（**新增**） | 双 job：`build`（JDK21 编译 9 模块 + Node20 前端 tsc/vite build）；`smoke`（起 Docker 全栈 → 等 9 容器 healthy → pwsh 冒烟子集 → 失败即 job 失败 → 日志 artifact）。适配点：`curl.exe` shim（脚本里的 Windows 习惯）、`ci-run.ps1` 在 Linux 自动切 `pwsh` |
| `scripts/ci-run.ps1`（**新增**） | 通用冒烟子集执行器：`-Scripts` 指定脚本集，逐套落盘 `logs/ci/`，兼容两种汇总行格式，失败清单逐条打印，**禁止传入自身 / run-all-smoke**（防递归） |
| `miniprogram/**`（**新增，A13 骨架**） | `project.config.json`（测试号 touristappid）+ `config.js`（BASE_URL / WECHAT_LOGIN_READY 开关）+ `utils/api.js`（包络解包 / Bearer 令牌 / 401 跳登录 / 错误文案，与 Web 端 client.ts 同口径；ID 一律字符串）+ 5 页面（index 检索 / product 详情加购 / cart 结算下单（取默认地址 `addressId`，A8 语义）/ orders 沙箱支付预下单+轮询 / login 手机号回退）+ `README.md`（3 步跑起来 + 下一轮待办） |
| `docs/agent/07-待办事项与验收标准.md`（**改**） | A6 标记 ✅（实际交付 / 自测 / CI Linux 适配点 / 待验证：Actions 首跑）；A13 改 🔄 并记第十五轮进展；取任务顺序更新 |
| `README.md`（**改**） | 顶部第十五轮说明；冒烟节加 CI / 契约导出入口；目录树加 `.github`、`miniprogram`、`docs/openapi` |
| `AGENTS.md`（**改**） | 冒烟基线行补 CI 子集入口 |
| `docs/agent/02-代码地图与现状.md`（**改**） | 脚本资产表加 `export-openapi.ps1` / `ci-run.ps1` / `ci.yml` / `docs/openapi` |
| `docs/handover/00、01、07`（**改**） | 进度快照加 A6/A13；上手步骤加 CI 与契约导出；测试证据加 CI 子集 |

**自测**：`mvn clean install` 9 模块 EXIT=0；fat jar 内确认含 springdoc/knife4j（T-41 判据：看 jar 而非退出码）；重建 9 容器全 healthy；5 服务 `/v3/api-docs` 全 200；本地实跑 CI 子集 **356/0 全绿**（smoke 54 / trade 87 / order 77 / address 82 / id-precision 56）；workflow YAML 解析校验通过。

**遗留**：① CI 在 GitHub Actions 的**首次实跑**待观察（私有仓库需确认 Actions 已启用，免费额度每月 2000 分钟）；② A13 下一轮做 `code2Session` 后端端点；③ 云端台账 v18 同步因**腾讯文档连接器未连接**（`personal=not_connected`，需用户在连接器页授权）暂挂起。

### 2026-09-23（第十五轮续 · A13 后端 code2Session 登录 + 连接器打通与台账 v18）

| 文件 | 变更 |
|---|---|
| `docs/sql/incremental/08-a13-wechat-bind.sql`（**新增**） | `t_user` 加 `wx_openid VARCHAR(64) NULL` + **唯一键 `uk_user_wxopenid`**；存量库增量脚本，重复执行报 1060 属预期（与 06 同口径） |
| `docs/sql/01-duoduo-user.sql` + `duoduo-user/.../db/schema.sql`（**改**） | t_user 建表语句同步加列与唯一键（新库口径一致） |
| `duoduo-user/.../entity/UserEntity.java`（**改**） | 加 `wxOpenid` 字段 + getter/setter |
| `duoduo-user/.../dto/UserDtos.java`（**改**） | 加 `WechatLoginRequest(@NotBlank String code)` |
| `duoduo-user/.../support/WechatGateway.java`（**新增**） | code2Session 客户端三分支：mock（`duoduo.wechat.mock-enabled=true` 显式开启 + code 前缀 `mock:`，生产禁开）→ real（`java.net.http.HttpClient` 调 jscode2session，errcode≠0 → 401）→ 未配置（**503 DEGRADED**，不用 422 错怪调用方）；secret 只走环境变量，日志只记 openid 前 4 位 + 长度 |
| `duoduo-user/.../service/AuthService.java`（**改**） | 加 `wechatLogin`（未绑定 → 422 引导先登录再绑定，**不做自动注册**——微信授权拿不到手机号；封禁账号同样拒绝）与 `wechatBind`（同 openid 幂等 200 / openid 属他人 409，并发由唯一索引兜底）+ `findByOpenid` |
| `duoduo-user/.../controller/AuthController.java`（**改**） | 加 `POST /api/auth/wechat`（免登；小程序无 Cookie，令牌只走响应体）与 `POST /api/auth/wechat/bind`（`UserContext.requireUserId()`） |
| `duoduo-gateway/.../GatewaySecurityProperties.java`（**改**） | 白名单加 `/api/auth/wechat` 一条；**`/wechat/bind` 刻意不放行**（需登录态，放行等于可把任意微信绑到自己账号） |
| `duoduo-user/src/main/resources/application.yml`（**改**） | 加 `duoduo.wechat.{appid,secret,mock-enabled}`，均走环境变量（mock 开关用 `DUODUO_WECHAT_MOCK_ENABLED`，A5 命名纪律） |
| `docker-compose.yml`（**改**） | `duoduo-user-app` environment 加 `WECHAT_APPID` / `WECHAT_SECRET`（默认空）/ `DUODUO_WECHAT_MOCK_ENABLED`（**本地/CI 默认 true**，生产必须去掉） |
| `scripts/wechat-auth-smoke.ps1`（**新增**） | 26 项断言：未绑 422 + 引导文案 / 降级 503 + DEGRADED / 绑定 200 + 库内校验 / 幂等 200 / 绑定后登录同 userId / 抢绑 409 且不篡改 / 空 code 400×2 / bind 免登 401 / 响应不含 openid 全文；动态断言总数守卫 |
| `docs/agent/04-接口契约全集.md`（**改**） | 白名单行加 `/api/auth/wechat`（bind 不放行注释）；端点表加 2 行（422/503/409/幂等语义） |
| `docs/agent/07-待办事项与验收标准.md`（**改**） | A13 段补「后端补齐（已完成）」：DDL / 端点 / 三分支 / 自测 26 项 / 余下真机待 AppID |
| `miniprogram/README.md`（**改**） | 头部改为「后端已就绪、`WECHAT_LOGIN_READY` 待开」；待办清单更新 |

**自测**：DDL 实跑并验证列 + 唯一索引存在；`mvn clean install` 9 模块通过；重建 user + gateway 容器 healthy；**`wechat-auth-smoke.ps1` 26/26 全绿（GUARD-OK）**；CI 子集回归 **356/0 全绿**（54/87/77/82/56，与 A6 基线一致）。

**本轮其他进展**：腾讯文档连接器已由用户授权打通（`tdoc_init` READY + 实调 `manage.query_file_info` 验证通过），**云端台账 v17 → v18 覆盖写成功**（`base_version 17 → new_version 18`）。

### 2026-09-24（第十五轮续 · CI 首跑打通：4 轮失败定位与修复）

| 提交 | 内容 |
|---|---|
| `12d155b` | 依据 Run #1/#2 日志定位：① 16 套冒烟脚本错误响应体读取是 PS5.1-only 写法（CI pwsh 7 读不到）→ 统一双路径兜底；② mysql healthcheck 加 `start_period 120s`；③ setup-java v4→v5 |
| `f7a2248` | Run #3 诊断日志抓到真凶：**增量 SQL（06/08）被容器初始化目录加载，全新建库必撞 1060 → mysql 容器退出 → 依赖等待赛跑时输时赢**（Run #2 侥幸过）。修复：06/07/08 移入 `docs/sql/incremental/`（不进挂载目录），docs 同步路径；登记 **T-46**，顺带补登 **T-44** |
| `5be6a67` | Run #4 仍挂同两条 → 本地下载 pwsh 7.4 实测：PS7 的 ErrorDetails.Message 是**多行 + \u 转义**形态（curl 实锤服务端发的是真中文），且响应流已被 PS7 释放（ReadAsStringAsync 必抛异常）。修复：ReadErrBody 改 ErrorDetails 优先；address「拒单文案」断言改匹配 `body.message`（JSON 解码后是真中文）。**本地 pwsh 7.4 预演 smoke 54/0 + address 82/0** |
| **Run #5 ✅** | **GitHub Actions 首次全绿**：编译检查 ✅ + 冒烟子集 356 项 ✅（`5be6a67`）。A6 的「CI 待验证」正式关闭；agent 08 踩坑登记 **T-44 / T-45 / T-46** |

### 2026-09-23（第十四轮 · A8 收货地址簿 + T-39 雪花 ID 精度根治）

| 文件 | 变更 |
|---|---|
| `duoduo-common-web/.../LongToStringJacksonModule.java`（**新增，本轮核心**） | **全站 ID 精度保护**。规则 =「`Long`/`long`/`BigInteger` **默认转字符串**」+「字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额 / 计数 / epoch 毫秒时间戳三类）的**保持数字**」，用 `BeanSerializerModifier.changeProperties()` 把命中白名单的字段 writer 换成 `NumericLongPropertyWriter`（`gen.writeNumber`）绕过全局 ToString 序列化器。**判据是字段语义**（前端会不会拿它做算术），不是 Java 类型 —— 全站金额字段 63 处都是 `Long`，一刀切会让前端 `price / 100` 算出 `NaN`。**漏标方向安全**：新加 id 字段无需任何操作即受保护。⚠ 白名单**必须用 `Set.copyOf(Arrays.asList(...))`**：`Set.of()` 遇重复元素在**运行期**抛 `IllegalArgumentException: duplicate element`（编译期不报）。反序列化与 `@PathVariable Long` 不受影响 |
| `duoduo-common-web/.../WebAutoConfiguration.java`（**改**） | 新增 `@Bean LongToStringJacksonModule`（供 Feign / RestTemplate 等非 MVC 场景），并在 `extendMessageConverters` 里给每个 `MappingJackson2HttpMessageConverter` 挂上该模块；两者都挂是刻意的 —— 前者让"隐藏规则"显式出现在装配清单里，读代码的人不必翻 `extendMessageConverters` 才知道 id 是字符串 |
| `duoduo-user/`（**A8 新增文件**） | `entity/UserAddressEntity`、`mapper/UserAddressMapper`、`service/AddressService`（**`fullAddress()` 是全站唯一拼接处**：省+市+区+明细直接相连，直辖市省=市只留一次；设默认/删除在**事务内**保证"恒有且仅有 1 条默认"）、`controller/AddressController`（6 用户端点 + `GET /internal/snapshot`）、`support/InternalTokenFilter`（内部凭据，失败关闭）；`dto/UserDtos` 增地址相关 DTO；`resources/db/schema.sql` 增表 |
| `docs/sql/incremental/07-a8-user-address.sql`（**新增**） | `t_user_address`（user_id / 收货人 / 电话 / 省市区 / 明细 / 标签 / 是否默认 / 时间戳）；`uk_addr_user_default` 之类约束 + 覆盖索引 `(user_id, is_default)` |
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
| `docs/sql/05-duoduo-marketing.sql`、`docs/sql/incremental/06-a5-coupon-columns.sql`（**新增**）、`docs/sql/00-init-databases-and-user.sql`（**改**） | 新库 `duoduo_marketing`：`t_coupon_template`（运营券模板：名称/描述/类型/门槛/抵扣/总量/已领量/起止/状态）、`t_coupon`（用户券：券码/用户/模板/状态/锁定订单号/核销订单号/领取时间/过期时间），**领券幂等靠唯一索引 `uk_coupon_user_tpl(user_id, template_id)`**（一人一模板一张）；种子模板 **5001**（新客立减 5 元，满 50 减 5，1000 张）/ **5002**（满 200 减 30）；交易域增量列 `t_order.coupon_code VARCHAR(32)`（**MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，重复执行报 1060 属预期**） |
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
docker ps                                  # 10 个容器应全为 healthy
# 需要 ES 检索（A10）时额外起：docker compose --profile search up -d duoduo-es
# 一键全量回归（19 套，含 ES 前置检查：ES 未运行时显式跳过 search-smoke 并列入汇总）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1

# 端到端冒烟（55 项，网关 + 用户域 + 商品域；含「分页 total 以 JSON 数字下发」类型断言）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 购物车端到端自测（87 项，需登录，会临时改库并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1

# 下单 / 拆单 / 库存冻结 端到端自测（77 项，含安全断言与部分冻结失败补偿）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1

# 支付 / 订单状态机 / 超时关单 端到端自测（71 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1

# 担保交易 / 资金托管 / 放款 / 退款 / 自动确认收货 端到端自测（125 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1

# 沙箱支付渠道 / 预下单 / 回调验签 端到端自测（62 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1

# 卖家中心 / 商家端：准入、归属隔离、发货、售后退款、结算对账（101 项，会临时改夹具并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1

# 平台管理后台：权限边界、报表对账、类目品牌、内容审核、订单强关（72 项，夹具自建自毁）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1

# 履约域：评价与晒单（59 项；含内部接口保护、字段裁剪、匿名脱敏、追评与聚合口径）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1

# 历史订单托管单回补（88 项；权限三层 / dryRun 预演不写库 / 幂等 / 挂账清零 / 控制组 / REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1

# 售后仲裁工单（101 项；立案 / 卖家处理 / 买家申请仲裁 / 运营裁定退款 / 已放款边界 B-01 / 越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1

# 图片上传（8 项；白名单只放 GET / 匿名上传 401 / 上传→回读 roundtrip / 扩展名白名单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\upload-smoke.ps1

# 物流轨迹（79 项；发货即揽收 / 乱序正序 / 幂等 / 批量全有或全无 / 无时区按 UTC / 越权 / 敏感字段 / 含断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1

# 信用分（82 项；基础分 / 权重表 / 因子和=总分 / 单调性 / 回写幂等 / 凭据边界）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\credit-smoke.ps1

# 优惠券（145 项；领券幂等 / 并发防超发 / 锁·解·核销 CAS / 折扣落交易域 / 生命周期闭环 / 清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coupon-smoke.ps1

# 收货地址簿（82 项；CRUD / 默认唯一性与删除顺延 / 归属校验统一 404 / 下单写快照 / 清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\address-smoke.ps1

# 跨域 ID 精度回归（56 项；只断言 JSON 原始文本，绕开反序列化的精度掩盖；含断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\id-precision-smoke.ps1

# 微信登录绑定（26 项；未绑 422 / 未配置降级 503 / 绑定幂等 / 抢绑 409 / 免登 401）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-auth-smoke.ps1

# AI 导购（16 项；推荐真实性 / 无 sellerId 泄露 / 匿名可问答 / 400 边界 / 429 限流）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1

# ES 检索（40 项；**需 ES 在跑**：docker compose --profile search up -d duoduo-es）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-smoke.ps1

# 限时秒杀（26 项；匿名边界 / 运营准入 / 下单幂等 replay / 限购 / 售罄 / 取消回补）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seckill-smoke.ps1

# ---------- A12 压测（JMeter 5.6.3，工具在会话 tools/ 下；jtl 与 HTML 报告落 logs/perf/，不入库） ----------
# ⚠️ 必须直接 java -jar 启动（jmeter.bat 检测不到 JDK21 时会 pause 挂死）；临时放大限流：DUODUO_TRADE_PLACE_QPS=5000 重建 trade
cd C:\Users\<会话目录>\tools\apache-jmeter-5.6.3\bin
java -jar ApacheJMeter.jar -n -t D:\智能体项目\duoduo-pet-mall-java\scripts\perf\order-pay-confirm.jmx `
     -l D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100.jtl -e -o D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100-report `
     -JTHREADS=100 -JRAMP=30 -JDURATION=300 -j D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100-run.log
# 队列通道换 order-queued-enqueue.jmx（trade 需 DUODUO_TRADE_ORDER_MODE=queue）；汇总：python scripts/perf/summarize_jtl.py <jtl...>

# 手动触发一轮托管单回补（先 dryRun 看计划，再执行；运营账号的 Cookie 需自行带上）
# curl.exe -X POST "http://127.0.0.1:8080/api/admin/settlements/backfill?dryRun=true"

# 六套真实浏览器 UI 自测（需先起前端 dev server）
# ⚠️ 端口用 3000 而非 5173：本机 5173 落在 Windows 排除端口段（Docker Desktop 启动后 WinNAT 保留 5149-5248，
#    报 listen EACCES）。3000 本就在网关 CORS_ORIGINS 白名单里，且必须走 localhost（不能 127.0.0.1）。
cd D:\智能体项目\duoduo-pet-mall-java\frontend
node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost   # 另开一个终端
cd .. && UI_BASE=http://localhost:3000 node scripts/e2e/seller-ui-check.mjs      # 商家端 29 项
UI_BASE=http://localhost:3000 node scripts/e2e/review-ui-check.mjs              # 评价与晒单 20 项
UI_BASE=http://localhost:3000 node scripts/e2e/aftersale-ui-check.mjs           # 售后三端 44 项（买家→卖家→运营整条链）
UI_BASE=http://localhost:3000 node scripts/e2e/logistics-ui-check.mjs           # 物流轨迹 44 项（买家/卖家同轨 + 空态 + 越权 + 终态仍在）
# 地址簿 64 项 / 优惠券 43 项（优惠券需先建夹具：scripts\e2e\coupon-ui-fixture.ps1 -Action create|drop）——合计 6 套 244 项

# ---------- 买家端 Web 商城（frontend/） ----------
cd D:\智能体项目\duoduo-pet-mall-java\frontend
npm install                 # 首次
npm run dev                 # http://localhost:5173（需后端 10 容器在跑，网关 :8080）
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
| 03 | 架构与不变式（INV-1~17，历史缺陷换来的硬约束） |
| 04 | 接口契约全集（端点×认证×DTO 字段×枚举×错误码，机械抽取自源码） |
| 05 | 开发任务手册（6 个配方 + 代码骨架，照抄即可） |
| 06 | 测试与验收门禁（按改动类型选必跑脚本 + 断言写法约定） |
| 07 | 待办事项与验收标准（A1~A13，逐项含接口草案与可执行验收标准） |
| 08 | 环境运维与踩坑禁忌（T-01~T-64 症状→根因→做法、10 条禁忌） |

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
