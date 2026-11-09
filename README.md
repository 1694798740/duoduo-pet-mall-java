# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5 · React 18 + TypeScript · 微信小程序
>
> 一个覆盖 **买家端（Web + 小程序）、商家工作台、平台运营后台** 的宠物用品电商演示项目：交易 / 沙箱支付 / C2C 担保交易 / 履约 / 营销 / 社交 / AI 导购 全链路可跑通。

> ⚠️ **PRD 说明（DEF-009）**：PRD 原文件不在本仓库，验收基线以本 README 的接口口径、`docs/PLAN.md` 的验收标准与运行中的 OpenAPI / 页面行为为准。

## 📚 文档导航

| 文档 | 内容 |
| --- | --- |
| 🚀 **[部署文档（DEPLOYMENT.md）](./DEPLOYMENT.md)** | 从零安装（Windows 逐步版）、环境要求、容器启动、配置说明、启动验证、常见问题排查 |
| 🧪 **[新手快速上手（demo-quick-start.md）](./docs/demo-quick-start.md)** | 5 分钟上手 / 30 分钟玩遍演示环境的客户版指南，含常见疑问 |
| 🎬 [完整体验路线（demo-experience-guide.md）](./docs/demo-experience-guide.md) | 20~30 分钟走完全部功能的客户版体验指南 |
| 🏛 [架构设计（ARCHITECTURE.md）](./docs/ARCHITECTURE.md) | 服务拆分、分库分表、容量模型、降级矩阵 |
| 🔌 [接口清单（API.md）](./docs/API.md) | 各域端点表与关键语义说明（完整版） |
| 🧭 [开发看板（PLAN.md）](./docs/PLAN.md) | 里程碑、端形态、支付渠道、问题与协助区、已知边界 |
| 🛠 [运维手册（OPS.md）](./docs/OPS.md) | 三站点部署、入口开关、备份恢复演练、数据清理与回滚 |
| ✅ [自测手册（SELFTEST.md）](./docs/SELFTEST.md) | 环境/数据准备、功能点清单、逐项步骤与预期、排障表 |
| 📝 [变更记录（CHANGELOG.md）](./docs/CHANGELOG.md) | 逐轮更新日志（按时间倒序归档） |
| 📦 [对象存储说明（COS）](./docs/COS-配置说明.md) | 腾讯云 COS 配置与图片 / 资质材料接入 |
| 🤝 [交接总纲（handover/）](./docs/handover/00-交接总纲.md) | 10 分钟接管：环境、架构、库表、接口、前端、测试证据、风险 |
| 🤖 [AI Agent 接管包](./AGENTS.md) | 给 AI 读的接管指令 + `docs/agent/00~08`（领域模型 / 代码地图 / 契约全集 / 门禁 / 踩坑） |

## ✨ 项目概览

### 三端入口

| 端 | 本地地址 | 试用机地址 | 说明 |
| --- | --- | --- | --- |
| 买家商城 | http://localhost:8088 | http://8.163.71.44 | 首页 / 搜索 / AI 导购 / 购物车 / 订单 / 闲置 / 分享圈 |
| 商家工作台 | http://merchant.localhost:8091 | http://8.163.71.44:8091 | 商品、订单、发货、售后、结算、店员 |
| 平台运营后台 | http://admin.localhost:8090 | http://8.163.71.44:8090（限 IP） | 审核、仲裁、订单干预、类目品牌、报表对账 |

> 三个站点**登录态互相独立**（Cookie 按主机名隔离）。演示账号：运营 `13684295254` / `duoduo12345`，买家 `13800009999` / `duoduo12345`；完整账号清单见 [docs/test-accounts.md](./docs/test-accounts.md)。
> 试用机为演示环境：支付走沙箱，不产生真实扣款；数据可能定期重置。

### 功能地图

| 角色 | 能力 |
| --- | --- |
| 买家 | 注册登录 / 实名、商品浏览与搜索、AI 导购「多多」、购物车与下单、沙箱支付、订单与物流、评价晒单、优惠券、秒杀、多多币与签到、二手闲置（买 / 卖，担保交易）、私信问一问、在线客服、分享圈、宠物档案、收货地址 |
| 商家 | 入驻申请与资质续期、商品发布与审核、订单发货、售后处理、结算对账、店员子账号 |
| 运营 | 商家 / 商品 / 内容审核、售后仲裁、订单干预、类目品牌管理、举报处理、操作审计、租户健康、SLA 抽查、秒杀活动、多多币 |
| 交叉能力 | 通知中心与未读合并、租户隔离、权限越权矩阵、两步验证与 IP 白名单、权限即时生效、每日对账与备份 |

### 近期亮点

- **AI 导购从 Mock 重构为 RAG + Agent**：DashScope Function Calling，`/api/duoduo/chat/stream` SSE 逐 token 下发，搜索 / 详情 / 库存 / 推荐 / 加购 / 转人工六类工具，写操作需一次性确认令牌。
- **二手闲置 · 担保成交全链路**：买家下单 → 平台托管 → 卖家发货 → 确认收货结算，含未发货自助退款、超卖 CAS 防重、卖出订单发货。
- **多多币 + 每日签到**：1 币 = 1 分、与券叠加、单笔抵扣上限 50%、T+7 返币，账户 / 批次 / 流水三账联动。
- **云端试用环境**：阿里云 ECS 上 14 个容器 healthy，三端 80 / 8090 / 8091 入口按主机名隔离，每日备份到 COS。
- **质量基线**：32 套接口冒烟、22 套真实浏览器 UI 断言、小程序 72 项无浏览器测试，全部纳入 `check-all.ps1` 门禁。

## 🚀 快速开始

**前置**：Docker Desktop（含 Compose v2）、JDK 21、Maven 3.9+、Node.js 18+、Git。Windows 从零安装的逐步图文流程见 [部署文档 §0](./DEPLOYMENT.md)。

```bash
# 1. 获取代码
git clone git@github.com:1694798740/duoduo-pet-mall-java.git
cd duoduo-pet-mall-java

# 2. 一条命令起全套（3 个中间件 + 8 个业务/网关 + 3 个站点容器）
docker compose up -d
docker ps            # 期望：14 个容器全部 healthy

# 3. 构建并启动网页端正式版容器（对外 8088，长期可用）
bash scripts/web-up.sh
```

打开 http://localhost:8088 即可（运营账号 `13684295254` / `duoduo12345`）。

```bash
# 可选：检索加速层（默认不起，检索自动降级走 MySQL）
docker compose --profile search up -d duoduo-es

# 可选：削峰队列（默认 sync 模式不需要 MQ）
docker compose --profile mq up -d rocketmq-namesrv rocketmq-broker
DUODUO_TRADE_ORDER_MODE=queue docker compose up -d --force-recreate duoduo-trade-app

# 改代码后：重建对应服务（以交易域为例）
mvn -gs maven/settings.xml -B -DskipTests package
docker compose build duoduo-trade-app && docker compose up -d duoduo-trade-app

# 从零重建（连数据卷一起删，会重新执行 docs/sql/ 初始化脚本）
docker compose down -v && docker compose up -d
```

> `maven/settings.xml` 使用阿里云镜像 + 项目内本地仓库，用于绕开本机全局配置中已失效的私服镜像，**无需修改任何全局配置**。
> 端口、`.env`、数据库初始化与三层启动验证见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 📦 模块结构

```
duoduo-pet-mall-java/
├── pom.xml                 聚合父 POM（release 21、统一版本与插件管理）
├── docker-compose.yml      容器编排（14 个基础服务 + ES / RocketMQ 可选 profile）
├── Dockerfile              业务服务共用镜像（temurin:21-jre-alpine，非 root）
├── maven/                  项目专用 Maven 配置（阿里云镜像）
├── docs/                   架构 / 计划 / 运维 / 自测 / 接口 / 交接 / SQL / OpenAPI
├── scripts/                启动、冒烟（32 套）、e2e（22 套）、运维、备份、门禁脚本
├── miniprogram/            买家端微信小程序（测试号阶段）
├── frontend/               npm workspaces：packages/{api-sdk,shared} + apps/{mall,merchant,admin}
├── duoduo-common/          统一响应 / 错误码 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/      Servlet 基础设施：全局异常 / 请求 ID / 身份透传
├── duoduo-gateway/         API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
├── duoduo-user/            用户：注册登录 / 令牌轮转 / 实名 / 宠物档案 / 地址簿 / 多多币
├── duoduo-product/         商品：类目品牌 / SPU-SKU / 检索 / 首页 / C2C 闲置 / 库存主数据
├── duoduo-trade/           交易：购物车 / 下单拆单 / 库存冻结 / 状态机 / 支付 / 资金托管
├── duoduo-fulfilment/      履约：评价晒单 / 物流轨迹 / 售后仲裁 / 信用分
├── duoduo-marketing/       营销：优惠券（领券 / 券包 / 锁定核销 / 补偿调度）
├── duoduo-ai/              AI 导购「多多」：候选召回 + 对话编排（RAG + Agent）
└── duoduo-social/          社交：关注 / 私信与卡片消息 / 商品咨询 / 通知中心
```

| 服务 / 容器 | 端口 | 职责 |
| --- | --- | --- |
| `duoduo-app-gateway` | 8080 | 统一入口：路由 + JWT 校验 + 身份透传 + CORS |
| `duoduo-app-web` | 8088 | 买家商城：nginx 托管产物 + 同源反代 `/api`、`/sandbox` |
| `duoduo-app-admin-web` | 8090 | 平台后台独立站点（`admin.localhost`，短地址 `/console`） |
| `duoduo-app-merchant-web` | 8091 | 商家平台独立站点（`merchant.localhost`，短地址 `/workspace`） |
| `duoduo-app-user` | 8081 | 注册登录 / 实名 / 宠物档案 / 地址簿 / 多多币 |
| `duoduo-app-product` | 8082 | 类目品牌 / SPU-SKU / 检索 / 闲置 / 库存主数据 |
| `duoduo-app-trade` | 8083 | 购物车 / 订单 / 冻结台账 / 支付 / 担保交易 |
| `duoduo-app-fulfilment` | 8084 | 评价 / 物流 / 售后仲裁 / 信用分 |
| `duoduo-app-marketing` | 8085 | 优惠券与补偿调度 |
| `duoduo-app-ai` | 8086 | AI 导购（未配 Key 时 503 降级，可 mock 整链跑通） |
| `duoduo-app-social` | 8087 | 关注 / 私信 / 商品咨询 / 通知 |
| `duoduo-mysql` · `duoduo-redis` · `duoduo-nacos` | 3306 · 6379 · 8848/9848 | 六库（含 Nacos 自建库）/ 缓存 / 注册配置 |
| `duoduo-es`（可选） | 9200 | 检索加速，`--profile search`；未启动时降级 MySQL |
| `rocketmq-namesrv` · `broker`（可选） | 9876 · 10911 | 削峰队列，`--profile mq` |

**分层纪律**：Controller 不写业务逻辑；Service 不依赖 `HttpServletRequest`；数据访问统一走 Mapper（MyBatis-Plus）、参数化查询，禁止字符串拼接 SQL。

## 🔌 接口与契约

统一响应：`{"code","message","data","requestId","timestamp"}`；失败时 `code` 与 HTTP 状态语义一致
（400 参数 / 401 未认证 / 403 无权限 / 404 不存在 / 409 冲突 / 422 业务规则 / 429 限流 / 503 降级）。

| 域 | 服务 | 路由前缀 | 代表能力 |
| --- | --- | --- | --- |
| 用户 | `duoduo-user` | `/api/auth` · `/api/users` · `/api/pets` · `/api/addresses` · `/api/coins` | 注册登录、令牌轮转、实名、宠物档案、地址簿、多多币与签到 |
| 商品 | `duoduo-product` | `/api/products` · `/api/categories` · `/api/listings` · `/api/meta` | 检索筛选、详情、C2C 闲置、库存写接口（内部凭据） |
| 交易 | `duoduo-trade` | `/api/cart` · `/api/orders` · `/payment` · `/api/payments` | 购物车、下单拆单、支付、退款、担保托管 |
| 履约 | `duoduo-fulfilment` | `/api/reviews` · `/api/logistics` · `/api/aftersales` | 评价晒单、物流轨迹、售后仲裁、信用分 |
| 营销 | `duoduo-marketing` | `/api/coupons` · `/api/seckill` | 领券、券包、锁·解·核销、秒杀活动 |
| AI | `duoduo-ai` | `/api/duoduo` | 对话流式输出、工具调用、加购确认 |
| 社交 | `duoduo-social` | `/api/socials` · `/api/support` | 关注、私信、商品咨询、通知中心、客服会话 |
| 平台 | 多服务 | `/api/seller/**` · `/api/admin/**` | 商家中心与运营后台（角色 + 权限点双重校验） |

完整端点表与逐条语义见 **[docs/API.md](./docs/API.md)**；由源码机械生成的契约全集见
[docs/agent/04-接口契约全集.md](./docs/agent/04-接口契约全集.md)（`scripts/extract-api-contract.py --check` 为门禁），
OpenAPI 摘要见 [docs/openapi/endpoints.md](./docs/openapi/endpoints.md)（端点总数以脚本输出为准，README 不写死）。

**认证口径**：外部请求统一走网关，JWT 短时效 access（15 min）+ 服务端 refresh（7 d，仅存 SHA-256 哈希并轮转）；
服务间写接口使用 `X-Internal-Token`（`InternalTokenFilter` 校验，默认开启、失败关闭，生产必须注入真实值）。

## 💳 支付与资金托管

支付采用**沙箱渠道**（不申请真实商户资质），但工程形态与真实渠道一致：预下单 → 二维码收银台 → 回调验签 → 金额核对 → 幂等入账。将来接真实渠道只需新增 `PaymentChannel` 实现，订单与账本代码不动。

```
买家支付(channel=sandbox) → POST /api/orders/{orderNo}/pay
   → 支付单 PENDING + codeUrl（指向网关 /sandbox/pay/{payNo}）
   → 收银台「模拟扫码支付」→ 与渠道回调同一套验签 + 金额核对 + 幂等入账
   → 支付单 SUCCESS / 订单已付款 / 资金进平台托管 CUSTODY
   ├─ 二维码过期（默认 15 分钟）→ EXPIRED（补偿调度）
   └─ 订单取消 / 超时关单 → 未支付支付单 CLOSED，二维码作废
```

C2C 担保交易的订单状态与资金状态是**两条时间线**：订单回答「货走到哪」，托管单（`t_settlement`）回答「钱在谁手里」。

```
下单(冻结库存) → 待付款 → 已付款(资金 CUSTODY) → 已发货 → 已完成(放款 RELEASED)
                                  └─ 未发货退款 → 已退款(REFUNDED，原路退回)
```

- 资金状态迁移一律用 **CAS**（`status='CUSTODY'` 写进 WHERE），重复确认收货 / 重复退款天然无副作用。
- **已放款订单不能直接退款**，需走追偿或平台垫付；**未发货才允许买家自助退款**，已发货必须走售后。
- 发货不等于放款；退款不阻塞入库（台账 `RESTOCK_PENDING → RESTOCKED` + 补偿调度兜底）。
- 订单关闭必须作废未支付支付单，避免「订单已取消、二维码还能付钱」。

## 🧩 三端 Web 与小程序

| 应用 | 技术栈 | 规模 | 启动 |
| --- | --- | --- | --- |
| 买家商城 `frontend/apps/mall` | React 18 + TS + Vite，手写 CSS 主题 | 主交易闭环 10 页 + 优惠券 / 金币 / 闲置 / 分享圈 | `npm run dev`（5173，代理到网关 8080） |
| 商家平台 `frontend/apps/merchant` | 同一 workspaces 仓库，共享 `api-sdk` / `shared` | 9 个路由：概览 / 订单 / 商品 / 售后 / 结算 / 店员 / 入驻 | 容器 8091 |
| 运营后台 `frontend/apps/admin` | 同上 | 14 个工作台：审核 / 仲裁 / 干预 / 报表 / 审计 / 秒杀 | 容器 8090 |
| 小程序 `miniprogram/` | 原生小程序（测试号阶段） | 30 页，主链路复用网关契约 | 微信开发者工具导入 |

关键实现决策：

- **令牌零接触**：JWT 只在 httpOnly Cookie，前端读不到也不需要读；登录态唯一判据是 `GET /api/users/me`，401 时用 refresh 单飞刷新并重放一次。
- **金额全用「分」**：接口层不换算，仅展示层 `formatPrice`（分 → 元）。
- **雪花 ID 一律按字符串传输**：18 位超出 JS 安全整数范围，`JSON.parse` 会静默改值。
- **三端会话隔离靠主机名**：Cookie 作用域不区分端口，因此用 `admin.localhost` / `merchant.localhost` 而不是同主机不同端口。
- **顶栏只放一级动线**：个人事务与两个工作台入口收进头像下拉；1200px 收搜索框、1024px 收品牌文字，宁可收搜索也不切导航。

## 🛡 工程约束

- **身份不可伪造**：网关校验后写入 `X-User-*`，并先剥离客户端同名头；业务服务禁止公网直连，服务内一律从 `UserContext` 取用户。
- **错误体系**：服务层只抛 `BizException`，由 `GlobalExceptionHandler` 统一转换；5xx 堆栈只进服务端日志，客户端拿到规范结构 + requestId。
- **链路追踪**：每请求生成 `X-Request-Id`，写入响应头与日志 MDC，跨服务透传；日志为结构化 JSON。
- **分布式 ID**：雪花算法（含时钟回拨保护），各服务独立 worker-id；订单号对外用「时间戳 + 随机 + 校验位」。
- **幂等靠唯一索引或 CAS，不靠分布式锁**：下单 / 支付 / 加购 / 冻结 / 托管各有唯一索引；放款与退款用条件 UPDATE 保证「同一笔钱只出去一次」。
- **库存原子性**：冻结 / 释放 / 扣减 / 入库都是条件 UPDATE，数据库行级原子性防超卖；单请求内整批 SKU 同事务全成或全不成。
- **跨服务副作用**：不放在本地事务里发；下单失败按逐项结果精确补偿，补偿调度作为可重入兜底。
- **状态机集中在枚举**：合法流转写在一处，各接口再收窄自己的合法起点。
- **越权断言单一出口**：`scripts/privilege-matrix-smoke.ps1`（115 条，每条「被拒」必须配「有权者能过」正对照，网关层与服务端层分别断言）。

## ✅ 测试与质量

| 门禁 | 命令 | 规模 |
| --- | --- | --- |
| 接口冒烟（全量） | `scripts\run-all-smoke.ps1` | 32 套，逐套落盘 `logs/smoke/*.log` |
| 接口冒烟（CI 子集） | `scripts\ci-run.ps1` | 17 套，PR 快速反馈 |
| 真实浏览器 e2e | `scripts\e2e\run-ui-checks.ps1` | 22 套 Playwright 断言，截图默认落 `.shots/`（已 gitignore） |
| 小程序逻辑层 | `node --test scripts/mp-harness/*.test.js` | 72 项，直接加载真实页面 js |
| 交付前总门禁 | `scripts\check-all.ps1` | 编译 + 契约 + 双端对照 + 依赖安全 + 冒烟 |
| 运维巡检 / 恢复演练 | `scripts\ops-status.ps1` · `scripts\ops-restore.ps1` | 一屏看全栈 / 真恢复一次并比对 |
| 数据库备份 | `scripts\db-backup.ps1 -BackupRoot <目录>` | 六库 + 上传卷，产物自证 + 三方比对 |

测试纪律：随机手机号可重复执行；夹具从接口动态取、跑完自毁；凡改库必记录原值并复原写回；计数断言数据驱动，不写死条数；**`N/A`（没跑）绝不写成 PASS**，宁假红勿假绿。详见 [SELFTEST.md](./docs/SELFTEST.md) 与 [OPS.md](./docs/OPS.md)。

## 🗺 路线图

| 端 | 形态 | 面向 | 支付 |
| --- | --- | --- | --- |
| 买家 | 响应式 Web + 微信小程序 | 买家 | Web 扫码（沙箱）/ 小程序内拉起（沙箱） |
| 商家 | Web 商家服务平台 | 商家 / 卖家 | — |
| 平台 | Web 管理后台 | 平台运营 | — |

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P0 | 工程骨架 + 公共模块 + 网关 + 用户 / 商品服务 | ✅ |
| P1 | 容器化环境（Nacos）+ 交易域 + 买家 Web + 商家 Web + 平台后台 + 沙箱支付 | ✅ |
| P2 | 履约四块（评价 / 物流 / 售后 / 信用分）+ 优惠券 + 秒杀 + 多多币 | ✅ |
| P3 | AI 导购（RAG + Agent）+ ES 检索 + RocketMQ 削峰 | ✅ 已上线（可选 profile） |
| P4 | 压测基线（A12）、可观测（SkyWalking / Prometheus）、K8s 编排 | 🔄 压测完成，其余待做 |
| P5 | 微信小程序主链路 + 真实支付渠道（需商户资质） | ⏳ 沙箱阶段 |

## 📝 变更记录

完整更新日志已迁至 **[docs/CHANGELOG.md](./docs/CHANGELOG.md)**（按时间倒序，含每轮验证口径与踩坑记录）。近期摘要：

- **2026-10-29**：AI 导购从 Mock 重构为 RAG + Agent（SSE 逐 token、六类工具、一次性确认令牌），新增 `duoduo_ai` 库与商品 AI 画像。
- **2026-10-28**：试用机「方案 A」部署完成（14 容器 healthy、三端 80/8090/8091、每日 03:00 备份到 COS）。
- **2026-10-27**：C2C 闲置担保交易全链上线；同日完成重复收款自动退款（B-04）与多多币 P1 + 签到（R-P1）。

---

<sub>本项目为演示 / 学习用途，支付走沙箱，不含真实资金流转。</sub>
