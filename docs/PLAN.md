# 多多宠物商店 · 项目开发计划（看板）

> 更新时间：2026-09-18 18:20
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
| M11 | 营销 / AI 导购 / 履约 / 检索 / 压测 | 🔄 进行中 | **P2-1 评价与晒单已落地**（新建 `duoduo-fulfilment`，自测 59 项 + UI 20 项全绿）；余下：物流轨迹 / 售后仲裁 / 信用分 / 优惠券 / AI / ES |

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

### 进行中

| 任务 | 当前进展 |
|---|---|
| M11 剩余块：物流轨迹 / 售后仲裁 / 信用分（`duoduo-fulfilment` 同域内扩展） | 评价已落地，同域下一步最省；信用分已于评价域埋好数据（真实 user_id 始终保留） |
| M11 其他：优惠券营销（`duoduo-marketing`）/ AI 导购 / ES 检索 / RocketMQ / Sentinel / 压测 | 都需要新中间件或新服务，建议按「营销 → 检索 → 削峰」顺序推进 |

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
| I-14 | **历史订单缺托管单**（M5-8 上线前的订单没有 `t_settlement` 记录） | **待办（数据迁移）** | 无需你操作 | 影响：这些订单确认收货时「放款」会找不到托管单（只记 ERROR，不影响买家收货）。处理：写一次性回补脚本按订单状态推断资金状态；当前开发库可清空重建，**生产上线前必须回补** |
| I-16 | 客户端发错 `Content-Type`（如 POST 无 body 时带 `x-www-form-urlencoded`）落到兜底 handler → 返回 **500** | **已自愈**（2026-09-18） | 无需操作 | 客户端错误不应是 5xx，会污染告警。修法：新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 `handleUnsupportedMediaType`，返回 **415**；由卖家冒烟断言固定 |
| I-15 | 支付渠道首轮自测暴露**三处缺陷**（M8-1 实现期） | **已自愈**（2026-09-18） | 无需操作 | ① **未知渠道 / MOCK 回调拒绝用了 400**（应为 422 业务错误，注册表 javadoc 与实现不一致）→ 补错误码 `PAYMENT_CHANNEL_UNKNOWN` 并改用之；② **二维码内容默认指向交易服务 8083 直连地址**——扫码设备不可达，且网关缺 `/sandbox/pay` 路由 → 改为网关 8080 + 补路由与白名单；③ **订单取消 / 关单不作废未支付支付单**——`markClosedByOrder` 在 Mapper 里定义了却从未被调用，订单已取消、二维码还能扫出钱来 → 在取消与调度关单两条路径都接上。三处均已由 62 项自测断言固定 |
| I-17 | 履约域（M11/P2-1）自测暴露**三个工具层隐患** | **已自愈**（2026-09-18） | 无需操作 | ① 内部接口断言**打错服务端口**（连履约 8084 打用户域路径 → 无路由 500，看着像过滤器坏了）；② **PowerShell 把 `$SPU?page=1` 整体当变量名**（`?` 是合法的变量名字符），展开成 `=1&pageSize=50`，服务端收到 `spuId="=1&pageSize=50"` → 必须写 `${SPU}?`；③ **19 位雪花主键经 `ConvertFrom-Json` 丢精度**，出现「数据在库里、断言找不到」的假失败 → 断言改用业务键，需要主键时从**原始响应文本**正则提取 |
| I-18 | **`git push` 通道受阻**：`github.com` 的 git 端点报 `CONNECT tunnel failed, response 502` / `Empty reply from server`（代理只放通 `api.github.com`） | **已自愈**（2026-09-18） | 无需操作 | 改走 **REST Git Data API** 推送成功（远端 `main = af28bba`）；工具 `.workbuddy/tools/push_via_api.py`（令牌取自 git 凭据管理器，不落盘、不回显）。注意：本地与远端 **SHA 分叉但内容一致**，网络恢复后执行 `git fetch origin main && git reset --hard origin/main` 对齐即可 |

<details>
<summary><b>附：真实渠道备查（当前不需要你提供，将来要接真实收款时再取）</b></summary>

- **微信支付**：商户号 `mchid`、APIv3 密钥、商户 API 证书（`apiclient_cert.pem` / `apiclient_key.pem`）、
  关联 AppID（小程序 / 公众号）、可公网访问的 HTTPS 回调域名
- **支付宝**：AppID、应用私钥、支付宝公钥（或证书模式三件套）、已签约产品（电脑网站支付 / 当面付）
- **前置资质**：微信支付商户号需营业执照（个人主体申请不到）；支付宝需企业实名认证；回调域名需备案

</details>

---

## 6. 本轮变更记录

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
docker ps                                  # 7 个容器应全为 healthy

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

# 卖家中心 / 商家端：准入、归属隔离、发货、售后退款、结算对账（84 项，会临时改夹具并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1

# 平台管理后台：权限边界、报表对账、类目品牌、内容审核、订单强关（49 项，夹具自建自毁）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1

# 履约域：评价与晒单（59 项；含内部接口保护、字段裁剪、匿名脱敏、追评与聚合口径）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1

# 商家端 Web 的真实浏览器 UI 自测（29 项；需先起前端 dev server，必须走 localhost:5173）
cd D:\智能体项目\duoduo-pet-mall-java\frontend
node node_modules/vite/bin/vite.js --port 5173            # 另开一个终端
cd .. && node scripts/e2e/seller-ui-check.mjs

# 评价与晒单的真实浏览器 UI 自测（20 项；同样需 dev server 与 localhost:5173）
node scripts/e2e/review-ui-check.mjs

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

> 服务端口：网关 **8080**、用户 **8081**、商品 **8082**、交易 **8083**。

---

## 7.5 交接包（2026-09-18 建立，换账号接管用）

> 当前账号 token 耗尽前整理的一套**可独立接管**的资料，共 9 篇，本地在 `docs/handover/`，
> 云端同步到两处（腾讯文档「项目资产（按时间归档）/2026-09-18 交接包」与乐享知识库「多多宠物商店知识库」）。
> 逐篇 file_id / entry_id / url 见 `docs/handover/manifest.json`。

| 篇 | 主题 |
|---|---|
| 00 | 交接总纲（10 分钟接管：进度快照 / 下一步 / 命令速查 / 9 篇导航 / 密钥与权限指引） |
| 01 | 快速上手与环境搭建（8 容器、构建专用姿势、常见故障 8 条） |
| 02 | 架构与模块导航（服务拓扑 / 模块对照 / 跨域调用 / 一笔订单的数据流） |
| 03 | 环境变量与密钥（脱敏，四把钥匙与轮换建议） |
| 04 | 数据库与表结构（四库、表清单、状态枚举字典、口径约定） |
| 05 | 接口清单与契约（按域速查、认证与放行边界、错误码表） |
| 06 | 前端开发手册（目录职责、四大页面域、截图索引、浏览器专属坑） |
| 07 | 测试与验收证据（9 套 667 项 + 2 套 49 项、脚本四条约定、历史缺陷） |
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
| B-06 | 历史订单缺托管单（见 I-14） | 当前库里的都是早期测试数据，可清空重建 | 生产上线前必须回补 |
| B-07 | **支付走沙箱渠道，不是真实资金渠道**（不入账、不结算到银行卡） | 你的决定：真实渠道资质门槛过高，先用沙箱把工程链路做实 | **上线真实收款前**：实现 `PaymentChannel` 的微信 / 支付宝版本，材料清单见协助区折叠附件 |
| B-08 | **角色升级后需重新登录才生效**（角色快照写在 access token 内，15 分钟） | 避免每次请求回源查角色；前端已在入驻成功提示里写明 | 若要即时生效：网关每次回源查角色，或入驻成功后服务端主动使旧 refresh 失效并强制重登 |
| B-09 | **售后只有「卖家直接退款」一种形态**，没有独立的售后单（申请-审核-退货物流） | 售后单是独立领域模型，与担保交易主链路分开做 | 接入完整售后体系时 |
| B-10 | 旧的内部凭据端点 `/api/orders/{no}/ship` 与 `/internal/**` **保留** | 现有 6 套冒烟脚本依赖它们 | 清理脚本依赖后可收敛为卖家端单一入口 |
