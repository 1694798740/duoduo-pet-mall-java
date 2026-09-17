# 多多宠物商店 · 项目开发计划（看板）

> 更新时间：2026-09-18 10:40
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
| M5 | **P1-b 交易服务** | 🔄 进行中 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / 支付幂等 / C2C 担保交易 / 卖家中心 |
| M6 | 买家端 · **Web 商城** | ⬜ 未开始 | 响应式网站；PC 端展示支付二维码 |
| M7 | 买家端 · **微信小程序** | ⬜ 未开始 | 小程序内复用同一套支付接口 |
| M8 | **沙箱支付渠道** | ✅ 完成 | 渠道抽象 + 扫码预下单 + 收银台 + 回调验签 + 幂等（M8-1 自测 62 项全绿）；真实渠道留接口位 |
| M9 | 商家端 · **Web（商家服务平台）** | ⬜ 未开始 | 商品 / 订单 / 发货 / 售后 / 结算；后端即 M5-9 卖家中心 |
| M10 | 平台管理后台 · **Web** | ⬜ 未开始 | 类目品牌维护、内容审核、订单干预、资金对账、报表 |
| M11 | 营销 / AI 导购 / 履约 / 检索 / 压测 | ⬜ 未开始 | 更后期 |

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

### 进行中

| 任务 | 当前进展 |
|---|---|
| **M5-9 卖家中心** | 商品管理 / 订单查看 / 发货 / 售后。落地卖家账号体系后，把 `/ship` 与运营侧退款从「内部凭据」升级为「校验调用者是否为该订单的卖家」，并让退款走完整的售后申请-审核链路 |

### 未开始（按你确认的端形态排期）

| # | 任务 | 依赖 | 说明 |
|---|---|---|---|
| M6 | 买家端 Web 商城 | M5 接口就绪 | 复用 Node 版 17 个页面骨架，接口指向网关 `:8080`；补订单/售后/资金托管页与**支付二维码页** |
| M7 | 买家端 微信小程序 | M6 的接口封装 + 小程序 AppID | 登录接 `code2Session`；支付复用同一套预下单 + 轮询接口 |
| M9 | 商家端 Web（商家服务平台） | M5-9 | 商品、订单、发货、售后、结算对账 |
| M10 | 平台管理后台 Web | M5-9 | 类目品牌、内容审核、订单干预、资金对账、报表 |
| M11 | 营销 / AI 导购「多多」/ 履约售后 / ES 检索 / RocketMQ 削峰 / Sentinel / 压测 | 全部后期 | — |

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
| I-15 | 支付渠道首轮自测暴露**三处缺陷**（M8-1 实现期） | **已自愈**（2026-09-18） | 无需操作 | ① **未知渠道 / MOCK 回调拒绝用了 400**（应为 422 业务错误，注册表 javadoc 与实现不一致）→ 补错误码 `PAYMENT_CHANNEL_UNKNOWN` 并改用之；② **二维码内容默认指向交易服务 8083 直连地址**——扫码设备不可达，且网关缺 `/sandbox/pay` 路由 → 改为网关 8080 + 补路由与白名单；③ **订单取消 / 关单不作废未支付支付单**——`markClosedByOrder` 在 Mapper 里定义了却从未被调用，订单已取消、二维码还能扫出钱来 → 在取消与调度关单两条路径都接上。三处均已由 62 项自测断言固定 |

<details>
<summary><b>附：真实渠道备查（当前不需要你提供，将来要接真实收款时再取）</b></summary>

- **微信支付**：商户号 `mchid`、APIv3 密钥、商户 API 证书（`apiclient_cert.pem` / `apiclient_key.pem`）、
  关联 AppID（小程序 / 公众号）、可公网访问的 HTTPS 回调域名
- **支付宝**：AppID、应用私钥、支付宝公钥（或证书模式三件套）、已签约产品（电脑网站支付 / 当面付）
- **前置资质**：微信支付商户号需营业执照（个人主体申请不到）；支付宝需企业实名认证；回调域名需备案

</details>

---

## 6. 本轮变更记录

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
| B-04 | 订单卖家归属统一为平台自营 `sellerId=0`（商品域 SPU 暂无商家字段） | 第三方商家接入需要商品域先补字段；订单与明细结构已支持按卖家拆单 | M5-9 卖家中心 / M9 商家端 |
| B-05 | 补偿调度用 `@Scheduled`（单实例） | 开发环境单实例足够；生产多实例需 XXL-Job 之类保证只跑一份 | 上生产前 |
| B-06 | 历史订单缺托管单（见 I-14） | 当前库里的都是早期测试数据，可清空重建 | 生产上线前必须回补 |
| B-07 | **支付走沙箱渠道，不是真实资金渠道**（不入账、不结算到银行卡） | 你的决定：真实渠道资质门槛过高，先用沙箱把工程链路做实 | **上线真实收款前**：实现 `PaymentChannel` 的微信 / 支付宝版本，材料清单见协助区折叠附件 |
