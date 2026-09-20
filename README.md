# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)；开发看板见 [`docs/PLAN.md`](./docs/PLAN.md)。

**最近更新：2026-09-20（第十轮）** — **售后三端页面完成**：买家 `/after-sales`（申请 / 我的 / 详情 + 申请平台仲裁）、
卖家 `/seller/after-sales`（同意即退款 / 拒绝必填原因）、运营 `/admin/after-sales`（裁定，说明必填）。
至此 A3 从接口到入口全链路打通 —— 上一轮交付的后端「可立案、可留证、可裁定」现在**用户在页面上真的走得到**。
新增真实浏览器自测 `scripts/e2e/aftersale-ui-check.mjs`（**44 项断言**，走完买家→卖家→运营整条链并回接口层确认钱真的退了）。
接口冒烟仍是 **11 套 856 项**，浏览器 UI 断言累计 **3 套 93 项**。详见「十二、变更记录」。

**上一轮（第九轮）** — 售后仲裁工单后端：履约域 `t_after_sale` + 8 个端点；卖家拒绝后买家可申请平台仲裁，
运营裁定退款会**真的执行退款**；**已完成订单（钱已放款给卖家）的裁定退款被拒 422** 并提示走追偿（刻意边界，非缺陷）。
`scripts/aftersale-smoke.ps1` **101 项断言**。

---

## 一、运行方式（Docker 优先）

中间件与业务服务**全部容器化**，一条命令起全套：

```bash
docker compose up -d          # 起 8 个容器
docker ps                     # 期望：8 个容器全是 healthy
```

| 容器 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `duoduo-mysql` | mysql:8.0.36 | 3306 | **四个库**：`duoduo_user` / `duoduo_product` / `duoduo_trade` / `duoduo_fulfilment`（`nacos` 库由 Nacos 自建） |
| `duoduo-redis` | redis:7.2-alpine | 6379 | 缓存与分布式能力预留 |
| `duoduo-nacos` | nacos/nacos-server:v2.3.2 | 8848 / 9848 | 服务注册与配置；**已注册 5 个服务**（网关 + 4 个业务服务） |
| `duoduo-app-gateway` | duoduo/duoduo-gateway:1.0.0 | 8080 | 统一入口：路由 + JWT 前置校验 + 身份透传 + CORS |
| `duoduo-app-user` | duoduo/duoduo-user:1.0.0 | 8081 | 注册登录 / 令牌轮转 / 实名 / 宠物档案 |
| `duoduo-app-product` | duoduo/duoduo-product:1.0.0 | 8082 | 类目 / 品牌 / SPU-SKU / 检索 / 首页 / C2C 闲置 / **库存主数据** |
| `duoduo-app-trade` | duoduo/duoduo-trade:1.0.0 | 8083 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / **支付（沙箱扫码）** / C2C 担保交易 |
| `duoduo-app-fulfilment` | duoduo/duoduo-fulfilment:1.0.0 | 8084 | 履约域：**评价与晒单**（后续：物流轨迹 / 售后仲裁 / 信用分） |

**从零重建**（连数据卷一起删，会重新执行 `docs/sql/` 里的初始化脚本）：

```bash
docker compose down -v && docker compose up -d
```

### 构建

```bash
# Maven 构建（产出可执行 jar；Dockerfile 用它做镜像，不在容器内编译）
mvn -gs maven/settings.xml -B -DskipTests package

# 改了代码后重建镜像并重启对应服务
docker compose build duoduo-trade-app && docker compose up -d duoduo-trade-app
```

> 项目内 `maven/settings.xml` 使用阿里云镜像 + 项目内本地仓库，
> 因为机器上全局 `settings.xml` 的 `mirrorOf=*` 指向**已失效的公司私服** `100.wunding.com:1443`。
> 用项目内配置可以绕开，**不需要改任何全局配置**。

### 端到端冒烟（十一套 856 项接口断言 + 三套真实浏览器 UI 断言 93 项）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1              # 54 项：用户域 + 商品域
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1        # 87 项：购物车
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1        # 76 项：下单拆单 + 库存冻结
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1      # 71 项：支付幂等 + 状态机 + 超时关单
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1   # 125 项：担保交易 + 资金托管 + 退款
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1  # 62 项：沙箱支付渠道（扫码/验签/回调）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1       # 84 项：卖家中心（准入/归属隔离/发货/售后/结算）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1        # 49 项：平台管理后台（权限边界/审核/强关/对账）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1       # 59 项：履约域评价（内部接口/资格/匿名/追评/聚合）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1     # 88 项：托管单回补（dryRun/幂等/挂账清零/控制组/REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1    # 101 项：售后仲裁（立案/卖家处理/升级/裁定退款/已放款边界/越权）

# 三套 UI 自测都需先起前端 dev server。
# 端口约定 5173，但**本机 5173 会被 Windows 排除端口段占用**（Docker Desktop 启动后 WinNAT 保留 5149-5248，
# 报 `listen EACCES`）。改用 3000 即可 —— 它已在网关 CORS_ORIGINS 默认白名单里，不用改任何配置：
cd frontend && node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost   # 另开终端
# 注意必须走 localhost，不能是 127.0.0.1（不在 CORS 白名单）
cd .. && UI_BASE=http://localhost:3000 node scripts/e2e/seller-ui-check.mjs        # 商家端 29 项
UI_BASE=http://localhost:3000 node scripts/e2e/review-ui-check.mjs                # 评价与晒单 20 项
UI_BASE=http://localhost:3000 node scripts/e2e/aftersale-ui-check.mjs             # 售后三端 44 项（买家→卖家→运营整条链）
```

十一套脚本的共同约定（踩过坑才有的）：
- 每次运行使用**随机手机号**，可重复执行；
- 夹具（价格、库存、佣金）一律**从接口动态取**，不硬编码 —— 否则种子数据一改，测试就会「通过但其实是错的」；
- 凡改库必**先记录原值、复原写回原值**，并在结尾**自证**「库内数据已复原」；
- 跨轮次的断言按「本次测试开始时间」**限定范围**，不被功能上线前的历史数据误伤；
- 部分脚本加 `-SkipDbCheck` 可跳过改库段落，只跑接口断言。

### 交接资料（换人/换账号接管先看这里）

`docs/handover/` 下 9 篇：**00 交接总纲**（10 分钟接管）+ 上手环境 / 架构导航 / 环境变量与密钥（脱敏）/
数据库表结构 / 接口契约 / 前端手册 / 测试证据 / 风险与接手建议。
`manifest.json` 记录每篇在腾讯文档与乐享知识库的 id 与链接；云端两处已同步。

**AI Agent 接管包**（给 AI 读，与上面的人读版互补）：仓库根 `AGENTS.md` + `docs/agent/00~08`
（接管指令与硬规矩 / 领域模型 / 代码地图 / 架构不变式 / 接口契约全集 / 开发任务手册 /
测试门禁 / 待办与验收标准 / 环境与踩坑）。

### 降级方案：不开 Docker

中间件也可用免安装版跑（`scripts/start-infra.ps1` 起 MySQL/Redis，`scripts/start-local.ps1 -Mysql` 起三进程），
但这是**备用**路径：Docker Desktop 装在 `C:\Program Files\Docker`，其引擎是独立进程，
启动助手在部分 Windows 家庭版环境需要 WSL2（详见 `docs/GITHUB-PUSH-GUIDE.md` 与记忆留档）。

---

## 二、模块结构

```
duoduo-pet-mall-java/
├── pom.xml                     聚合父 POM：统一版本、编译参数（release 21）、插件管理
├── Dockerfile                  业务服务共用镜像（temurin:21-jre-alpine，非 root 运行）
├── docker-compose.yml          8 容器编排（中间件 + 5 个业务服务）
├── maven/                      项目专用 Maven 配置（阿里云镜像）与本地环境变量脚本
├── docs/
│   ├── ARCHITECTURE.md         完整架构设计（服务拆分、分库分表、容量模型、降级矩阵、路线）
│   ├── PLAN.md                 开发看板：里程碑 / 端形态 / 支付渠道 / 问题与协助区 / 已知边界
│   ├── GITHUB-PUSH-GUIDE.md    推送指南
│   └── sql/                    容器初始化 SQL（00 建库授权 / 01 用户库 / 02 商品库 / 03 交易库 / 04 履约库）
├── scripts/                    一键启动、冒烟测试（9 套接口断言 + 3 套真实浏览器 UI 断言）
├── duoduo-common/              通用：统一响应 / 错误码 / 业务异常 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/          Servlet 侧基础设施：全局异常处理 / 请求 ID / 身份上下文透传
├── duoduo-gateway/             API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
├── duoduo-user/                用户服务
├── duoduo-product/             商品服务（含库存主数据）
├── duoduo-trade/               交易服务（购物车 / 订单 / 冻结台账 / 支付渠道 / 资金托管）
├── duoduo-fulfilment/          履约服务（评价与晒单；后续：物流轨迹 / 售后仲裁 / 信用分）
└── frontend/                   买家端 Web 商城（React 18 + TS + Vite，见第七节）
```

**分层纪律**：Controller 不写业务逻辑；Service 不依赖 `HttpServletRequest`；
数据访问统一走 Mapper（MyBatis-Plus），参数化查询，禁止字符串拼接 SQL。

---

## 三、已实现的接口

统一响应：`{"code","message","data","requestId","timestamp"}`；失败时 `code` 与 HTTP 状态码语义一致
（400 参数 / 401 未认证 / 403 无权限 / 404 不存在 / 409 冲突 / 422 业务规则 / 429 限流 / 503 降级）。

### 用户域（`duoduo-user`）

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

### 商品域（`duoduo-product`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/categories` · `/flat` · `/{id}` | 两级类目树 / 扁平列表 / 单个类目 | 免 |
| GET | `/api/home` | 首页聚合：类目 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选 | 免 |
| GET | `/api/products` | 商品检索（参数见下表） | 免 |
| GET | `/api/products/{spuId}` | 商品详情：SKU、适用属性聚合、同类推荐 | 免 |
| GET | `/api/products/skus/batch?ids=2001,2002` | **批量 SKU 采购视图**（供交易服务调用；一次取几十个，避免 N 次跨服务调用） | 免 |
| POST | `/api/products/skus/stock/{freeze,release,deduct,restock}` | **库存写接口**（冻结 / 释放 / 扣减 / 退货入库），见下 | **内部凭据** |
| GET | `/api/listings` · `/{id}` | C2C 闲置列表（只返回已审核通过）/ 详情 + 同款全新推荐 | 免 |
| GET | `/api/meta/brands` · `/dictionaries` | 品牌列表 / 筛选字典 | 免 |

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

### 交易域（`duoduo-trade`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/cart` | 购物车列表：实时价格 + 可售库存 + **失效原因码** + 已勾选合计 | 需登录 |
| GET | `/api/cart/count` | 件数角标（不查商品服务） | 需登录 |
| POST | `/api/cart/items` | 加购（同 SKU **累加数量**，不新增行） | 需登录 |
| PATCH | `/api/cart/items/{skuId}` | 改数量 | 需登录 |
| PATCH | `/api/cart/items/{skuId}/selection` | 勾选 / 取消勾选 | 需登录 |
| DELETE | `/api/cart/items/{skuId}` · `/api/cart/selected` | 删除一条 / 清空已勾选 | 需登录 |
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

### 支付渠道（`duoduo-trade` /payment）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/payments/callback/{channel}` | **渠道支付回调**（HMAC 验签 + 金额核对 + 幂等入账；时间戳过期 / 签名错误拒绝） | 渠道密钥验签 |
| POST | `/api/payments/sandbox/{payNo}` | **沙箱「扫码后确认支付」**（模拟用户在收银台点确认；走与回调同一套验签与入账逻辑） | 免（沙箱专属） |
| GET | `/api/payments/sandbox/pay/{payNo}` | **沙箱收银台页面**（二维码指向这里；展示单号 / 金额 / 状态，带确认按钮） | 免（扫码设备无登录态） |
| GET | `/api/payments/{payNo}` | 查询支付单状态（仅本人） | 需登录 |

### 卖家中心（`duoduo-trade` /api/seller，M5-9）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/seller/summary` | 经营概览：各状态订单数 + 托管/已放款/已退回金额（**过滤后汇总仍是全量**） | 卖家或运营 |
| GET | `/api/seller/orders` | 我的订单列表（按 `status` 过滤；**只返回归属自己的单**） | 卖家或运营 |
| GET | `/api/seller/orders/{orderNo}` | 订单详情（含买家收货信息与资金区） | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/ship` | **发货**（填物流公司/单号，落 `t_order` 并回显）；资金**仍托管**，不触发放款 | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/refund` | **售后退款**（已付款/已发货；已完成需追偿或平台垫付，返回 422） | 归属校验 |
| GET | `/api/seller/settlements` | 结算对账（分页 + 全量汇总金额） | 卖家或运营 |

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

购物车条目的失效原因码（机器可读，前端只管展示文案）：
`SKU_NOT_FOUND` / `OFF_SHELF` / `OUT_OF_STOCK` / `STOCK_NOT_ENOUGH`。
**失效条目不计入结算金额** —— 否则结算页金额与订单金额会对不上。

### 平台管理后台（`/api/admin/**`，M10）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/reports/overview` | 运营报表：GMV / 总订单 / 已付款 / 退款率 / 转化率（全平台口径，SQL 聚合） | 运营 |
| GET | `/api/admin/settlements/summary` | 资金对账：托管中 / 已放款 / 已退回三组金额与笔数、佣金 | 运营 |
| GET | `/api/admin/settlements/abnormal` | 异常挂账：已付款却缺托管单的订单（`LEFT JOIN` 排查） | 运营 |
| POST | `/api/admin/settlements/backfill?dryRun=` | **历史订单托管单回补**（A1 / I-14）：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`、佣金按当前 `PLATFORM_FEE_BPS` 重算并回传口径说明；`?dryRun=true` **只出计划、一行都不写库**；不带参数即执行；幂等靠 `uk_settle_order`（重复执行不产生重复单）；**不触碰已有托管单的订单**，也不给 `PENDING_PAYMENT`/`CLOSED` 补账（钱从未收过） | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/categories`、`/{id}`、`/{id}/status` | 类目增改删 + 启停用；**后台列表含停用项**（可重新启用），公开接口仍只暴露 ACTIVE | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/brands`、`/{id}`、`/{id}/status` | 品牌同上 | 运营 |
| GET | `/api/admin/listings` | 闲置审核工作台列表（含待审 / 已驳回，按 `auditStatus` 过滤） | 运营 |
| POST | `/api/admin/listings/{id}/audit` | 审核：`APPROVE` / `REJECT`（驳回必须填原因）；`audit_reason` / `audit_at` 落库留痕 | 运营 |
| GET | `/api/admin/orders`、`/{orderNo}` | 全平台订单列表（状态 / 订单号前缀过滤）与详情（无归属限制） | 运营 |
| POST | `/api/admin/orders/{orderNo}/force-close` | **强制关单**（仅待付款）：复用状态机合法流转 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR | 运营 |

**权限模型**：统一 `OperatorGuard.requireOperator()`（OPERATOR / ADMIN，忽略大小写）；
网关层 `/api/admin/**` 不进白名单（未登录 401），服务层校验角色（非运营 403）——两层各挡一种流量。
已付款 / 已发货订单**不开**后台干预口子，一律走退款 / 售后的合法流转。

### 履约域：评价与晒单（`duoduo-fulfilment` `/api/reviews`，M11/P2-1）

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

### 履约域：售后仲裁工单（`duoduo-fulfilment` `/api/aftersales`，A3）

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

### 售后三端页面（`frontend/src/pages/`，A3 配套）

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

---

## 四、支付：渠道抽象与沙箱扫码（M8-1）

支付的形态是**真实渠道的完整仿真**（只是不接真实的微信/支付宝商户通道）：

```
买家点「支付」(channel=sandbox)
   │ POST /api/orders/{orderNo}/pay     ← 预下单：落支付单 PENDING + 返回 codeUrl（二维码内容）与过期时间
   ▼
二维码（指向网关 /sandbox/pay/{payNo} 的收银台页面）
   │ 扫码设备打开收银台 → 看到 单号/金额/状态 → 点「模拟扫码支付」
   │ POST /api/payments/sandbox/{payNo} ← 与渠道回调走同一套验签 + 金额核对 + 幂等入账
   ▼
支付单 PENDING → SUCCESS（CAS）；订单 → 已付款；资金进平台托管（CUSTODY）
   ├─ 二维码过期（默认 15 分钟）→ 支付单 EXPIRED（补偿调度自动扫）
   └─ 订单被取消 / 超时关单 → 未支付支付单作废（CLOSED），二维码作废后不可再付
```

**`PaymentChannel` 接口**（`duoduo-trade/.../payment/`）：预下单 / 回调验签 / 查询三个动作，
`MOCK`（即时成功，供自动化测试与本地联调）与 `SANDBOX`（扫码形态）是两个实现；
将来接真实渠道 = 再加一个实现类，**订单与账本代码不动**。

**回调安全（真实渠道同款攻击面，全部有测试覆盖）**：
- HMAC-SHA256 验签（密钥来自环境变量 `SANDBOX_HMAC_KEY`，compose 提供开发默认值）；
- 时间戳窗口校验（默认 ±5 分钟，防重放）；
- **金额核对**：回调金额与支付单金额不一致 → 拒绝入账（422），防止「1 分钱付 100 元的单」；
- 幂等：重复回调只入账一次（先查快路径 + `uk_pay_channel_idem` 唯一索引兜并发 + CAS 状态迁移）。

**口径说明（用户已拍板）**：本项目用**沙箱支付**，不申请真实微信/支付宝商户资质。
沙箱渠道的「扣款」不会真实发生，但**从预下单到入账的每一环（含验签、金额核对、幂等、过期作废）
都与真实渠道一致**——将来若要接真实渠道，只需替换渠道实现并配置商户密钥。

---

## 五、C2C 担保交易（资金托管）

```
下单 ──冻结库存──→ 待付款
                    │ 支付成功（渠道回调/沙箱确认）
                    ▼
                 已付款 ──资金进平台托管（CUSTODY）──→ 已发货
                                                        │ 买家确认收货 / 发货满 N 天自动确认
                                                        ▼
                                                     已完成 ──资金放款给卖家（RELEASED）
   分支：待付款 ──取消/超时──→ 已关闭（钱从未收过，或已释放冻结）
         已付款/已发货 ──退款──→ 已退款（REFUNDED，钱原路退回买家）
```

**订单状态与资金状态是两条时间线**：订单状态回答「货走到哪了」，托管状态
（`t_settlement`）回答「钱现在在谁手里」。分开记之后，「这笔钱到底在谁手里」永远只有一个答案。

| 概念 | 位置 | 说明 |
|---|---|---|
| 订单状态 | `t_order.status` | `PENDING_PAYMENT` / `PAID` / `SHIPPED` / `COMPLETED` / `REFUNDED` / `CLOSED` |
| 支付单状态 | `t_payment.status` | `PENDING`（待支付，沙箱扫码中）/ `SUCCESS` / `EXPIRED` / `CLOSED` / `FAILED` |
| 托管状态 | `t_settlement.status` | `CUSTODY`（托管中）/ `RELEASED`（已放款）/ `REFUNDED`（已退回买家） |
| 冻结台账 | `t_stock_freeze.status` | `FROZEN` → `DEDUCTED` → `RESTOCK_PENDING` → `RESTOCKED`；或 `FROZEN` → `RELEASED` |
| 平台佣金 | `t_settlement.platform_fee` | 按 `duoduo.trade.platform-fee-bps`（默认 100 bp = 1%）计，**结算当刻的口径落库留档** |

几条刻意的规则：

- **资金状态迁移一律用 CAS**（`status = 'CUSTODY'` 写进 WHERE）：定时任务与买家请求可能同一秒到达，
  数据库保证同一笔钱只能出去一次，因此「重复确认收货」「重复退款」天然无副作用；
- **已放款的订单不能直接退款**：钱已到卖家手里，需走追偿或平台垫付（属另一条流程，见 `docs/PLAN.md` 的已知边界）；
- **退款界限在服务端强制**：已付款未发货才允许买家自助退款；已发货必须走售后（货在买家手上，系统无从判断有没有退回来）；
- **退款不阻塞入库**：钱该退就退，货回库是仓库侧的后续动作，由台账 `RESTOCK_PENDING → RESTOCKED` 与补偿调度兜住；
- **发货不等于放款**：买家没见到货之前，钱一直留在托管里；
- **订单关闭必须作废未支付支付单**：否则订单已取消、二维码还能扫出钱来（本期接通了此前一直缺失的 `markClosedByOrder`）。

### 内部接口的凭据

库存写接口、发货、运营侧退款与补偿调度属于**服务间或运营侧**调用，要求请求头 `X-Internal-Token`，
由 `InternalTokenFilter` 校验（**默认开启、失败关闭**）。密钥来自环境变量 `INTERNAL_TOKEN`，
compose 里提供开发默认值，**生产必须注入真实值**。

---

## 六、已落地的工程约束

- **认证**：JWT 短时效 access（15 min）+ 服务端 refresh（7 d）。refresh 只存 SHA-256 哈希并轮转，
  即使库被拖走也无法直接冒用；令牌只走 httpOnly Cookie，**禁止写入 localStorage 或 URL 参数**。
- **身份透传**：网关校验通过后写入 `X-User-*` 内部头，并**先剥离客户端自带的同名头**，防止伪造身份越权；
  业务服务禁止公网直连。服务内一律从 `UserContext` 取用户，**请求体与路径里没有 userId**。
- **边界校验**：所有入参用 Jakarta Validation 注解在 Controller 侧校验；校验失败统一转 400 + 字段级提示。
- **错误体系**：服务层只抛 `BizException`，由 `GlobalExceptionHandler` 统一转换；
  5xx 堆栈只进服务端日志，客户端只拿到规范结构 + requestId。
- **链路追踪基础**：每请求生成 `X-Request-Id`，写入响应头与日志 MDC，跨服务透传；日志为结构化 JSON。
- **分布式 ID**：雪花算法（含时钟回拨保护），各服务使用不同 worker-id（用户 1 / 商品 2 / 交易 3）。
  订单号不对外暴露雪花 ID，而是 `时间戳 + 随机 + 校验位`（雪花 ID 的规律性会暴露业务量）。
- **幂等靠唯一索引或 CAS，不靠分布式锁**：
  下单 `uk_order_user_idem`、支付 `uk_pay_channel_idem`、加购 `uk_cart_user_sku`、冻结 `uk_freeze_sku_order`、
  托管 `uk_settle_order`；放款与退款用条件 UPDATE 保证「同一笔钱只出去一次」。
- **库存原子性**：冻结 / 释放 / 扣减 / 入库都是**条件 UPDATE**（判断写进 WHERE），
  由数据库行级原子性防超卖；单次请求内的整批 SKU 在同一本地事务里**要么全成、要么全不成**。
- **跨服务副作用的处理**：库存变更发生在商品服务，**不受交易服务事务保护**，
  因此下单失败时会按逐项结果**精确补偿**；跨服务调用**不放在事务里发**（退款入库走事务提交后的事件），
  避免「远程已生效、本地回滚」造成对不上账；补偿调度作为最终兜底（可重入）。
- **状态机集中在枚举**：合法流转写在一处，非法流转一处拒绝；各接口再收窄自己的合法起点
  （例如已付款订单不允许买家自助取消，退款走独立的 `REFUNDED` 终态而不是复用「已关闭」）。
- **支付回调安全**：HMAC 验签 + 时间戳窗口 + 金额核对 + 幂等入账四道防线（见第四节）。
- **业务规则**：宠物档案的「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）。

---

## 七、买家端 Web 商城（`frontend/`，M6 / P1-c）

### 技术栈与启动

React 18.3 · react-router-dom 6.28 · TypeScript 5.6（strict + noUncheckedIndexedAccess）· Vite 5.4。
无状态管理库（两个 Context 够用）、无 UI 框架（一份手写 CSS 主题）、**无任何运行时依赖做二维码**（自实现编码器）。

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173，需后端 8 容器在跑（网关 :8080）
npm run build      # tsc --noEmit && vite build
```

### 页面清单（主交易闭环 10 页）

| 路由 | 页面 | 说明 |
|---|---|---|
| `/` | 首页 | `GET /api/home` 聚合一次拉全：类目 / 推荐 / 新品 / 闲置 / 品牌 |
| `/search` | 检索 | 筛选条件全部同步进 URL query，可分享可回退 |
| `/product/:id` | 商品详情 | SKU 选择、过敏原与宠物档案比对提示、同类推荐 |
| `/listings` · `/listing/:id` | 二手闲置 | 列表筛选 + 详情（担保交易规则说明；本期只浏览不下单） |
| `/login` | 登录/注册 | `?next=` 回跳原路径；一键生成体验账号（走真实注册） |
| `/cart` | 购物车 | 以 `skuId` 为键；失效条目标注原因且不可结算 |
| `/checkout` | 结算 | 幂等键进入页面即生成、失败不更换；成功清空已勾选 |
| `/orders` · `/orders/:orderNo` | 订单 | 7 状态页签；详情含二维码、3 秒轮询、资金托管明细 |
| `/pets` | 宠物档案 | 全 CRUD；生日与生命阶段一致性实时提示（对齐后端 422 规则） |
| `/me` | 我的 | 资料 / 实名（身份证只回显掩码） |

### 关键实现决策

- **令牌零接触**：JWT 全在 httpOnly Cookie（`dd_at` 15 min / `dd_rt` 7 d），前端读不到也不需要读；
  登录态唯一判据是 `GET /api/users/me`，401 时用 `dd_rt` 单飞刷新并重放原请求一次。
- **开发代理避免 CORS**：`vite.config.ts` 把 `/api` 与 `/sandbox` 代理到 `VITE_DEV_API_TARGET ?? http://localhost:8080`，
  同源请求天然带上 Cookie，绕开 httpOnly Cookie 的 SameSite 与 CORS 组合问题。
- **金额全用「分」**：接口层不做换算，仅在展示层 `formatPrice`（分 → 元）。
- **筛选字典由后端驱动**：成色 / 物种 / 生命周期等下拉与标签从 `/api/meta/dictionaries` 取
  （后端下发的是码值，前端只做 code→label 映射），避免硬编码字典与后端漂移。
- **错误映射**：4xx 不重试、5xx 与网络错误退避重试 ≤3 次；`ApiError.isAuthError` 区分登录态失效；
  `toFriendlyMessage` 把后端 code 翻成可读文案，绝不把堆栈甩给用户。
- **零依赖二维码**：自实现 UTF-8 Byte 模式 + Reed-Solomon 纠错 + 8 掩码择优（ISO/IEC 18004），
  SVG 渲染。实测 21×21 模块矩阵在真实 Chromium 中清晰可扫。

自测记录：`tsc --noEmit` 零错误；curl 冒烟覆盖注册/加购/下单幂等/支付/托管/退款全链；
真实浏览器（Chromium 153）逐页验证 12 个路由的渲染与交互，含真实 UI 填单下单 → 模拟扫码 →
3 秒轮询自动刷新为「已付款」→ 托管明细金额核对（¥59.00 / 服务费 ¥0.59 / 卖家实收 ¥58.41）。
验证截图见 `frontend/docs/screenshots/`。

已知边界（对齐 `docs/PLAN.md`）：依赖未部署服务（履约 / 营销 / AI / 管理后台）的功能未做页面；
闲置发布与下单入口留待后续迭代；演示库**不预置演示账号**（登录页提供一键生成体验账号）。

---

## 八、商家端 Web（`frontend/src/pages/seller/`，M9 / P1-f）

与买家端**同一套工程、同一套登录态**（`/seller/**` 与 `/**` 共用 `frontend/`），只是按角色开入口。

| 路由 | 页面 | 说明 |
|---|---|---|
| `/seller` | 经营概览 | 六状态订单数 + 托管 / 已放款 / 已退回三栏资金；平台自营范围提示 |
| `/seller/orders` | 订单管理 | 状态页签过滤；列表卡片带买家收货信息；**立即发货**弹窗（物流公司 / 单号） |
| `/seller/orders/:orderNo` | 订单详情 | 状态步骤条 + 履约信息 + 资金区 + 发货 / 退款表单 |
| `/seller/settlements` | 结算对账 | 分页表格 + **全量汇总三栏**（过滤不变）；与买家端口径同源 |

**成为卖家**：实名认证后，在「我的」页点「申请成为卖家」（`POST /api/users/me/merchant`）。
角色快照写在 access token 内，**需重新登录**才能看到商家端入口（前端已在提示里写明，见 `docs/PLAN.md` B-08）。

**关键实现决策**：

- **`RequireSeller` 只校验登录、不拦角色**。前端拦角色是体验优化，不是安全边界——
  真正的边界在后端 `SellerService.requireSellerScope()`（403）与 `requireOwnOrder()`（404/403）。
  前端硬拦会造成「角色刚升级、token 还没换，入口就消失」的假故障。
- **`canAccessSellerConsole` 统一判定入口可见性**（Layout 导航 + 「我的」页快捷入口），
  角色字符串统一转大写比对，避免 `USER`/`user` 两种写法漂移。
- **概览与对账的汇总取全量、列表取过滤后**——分页只影响列表，不影响「我一共托管了多少钱」。
- **「已完成」的订单不显示退款按钮**（后端同样 422），钱已放款给卖家，需走追偿或平台垫付。

自测记录：`tsc --noEmit` 零错误、`vite build` 通过（60 模块）；后端 `scripts/seller-smoke.ps1` **84 项断言全绿**
（准入 401/403、实名前置 422、归属隔离、越权 403、重复发货 422、已完成退款 422、物流落库与回显、平台自营可见性、夹具自动复原）；
真实浏览器 `scripts/e2e/seller-ui-check.mjs` **29 项断言全绿**（逐页走概览 / 订单 / 状态过滤 / 一键发货 / 详情 / 对账 / 非卖家视角，
每页截图，并收集全部 HTTP 4xx/5xx 与 console 错误），截图见 `frontend/docs/screenshots/sel_*.png`。

两个只在浏览器里现形的坑（已写进脚本注释，别再踩第二次）：
- **前端必须用 `http://localhost:5173`**，不能用 `127.0.0.1` —— 网关 CORS 白名单只认 `localhost`，
  带 `127.0.0.1` 的 Origin 会被按非法跨域直接 403，页面只显示「没有权限执行该操作」，curl 复现不出来；
- **雪花 ID 有 18 位，超过 JS 安全整数范围**，`JSON.parse` 会悄悄把它变成另一个数。

---

## 九、平台管理后台 Web（`frontend/src/pages/admin/`，M10）

运营 / 管理员登录后，导航栏出现「管理后台」入口。五个工作台共用一条标签导航：

| 路由 | 工作台 | 内容 |
|---|---|---|
| `/admin` | 运营概览 | 经营报表（GMV / 订单量 / 退款率 / 转化率）+ 资金对账三栏 + **异常挂账表**（已付款却缺托管单，需人工回补） |
| `/admin/audits` | 内容审核 | 待审 / 已通过 / 已驳回三视图；通过后公开可见，驳回必须填原因（留痕：原因 + 时间） |
| `/admin/categories` | 类目管理 | 增改删 + 启停用；状态列 + 按状态切换按钮；**停用项仍可见可恢复** |
| `/admin/brands` | 品牌管理 | 同类目 |
| `/admin/orders` | 订单干预 | 全平台订单（状态过滤 + 订单号前缀搜索）；**仅待付款**可强制关单（确认弹窗说明后果：作废支付单、释放冻结、写状态日志） |

**关键实现决策**（与商家端同一套哲学）：

- **`RequireOperator` 只校验登录、不拦角色**。非运营进入时页面统一渲染 `AdminDenied` 拦截页；
  真正的边界在后端 `OperatorGuard.requireOperator()`（403）。
- **`isOperator` 与后端 `LoginUser#isOperator()` 同口径**（忽略大小写，OPERATOR/ADMIN 均可），
  入口可见性与接口准入不会出现「页面让进、接口拒绝」的分叉。
- **后台与公开查询分离**：类目 / 品牌的后台列表走不过滤状态的专用方法，
  停用后运营仍可见、可重新启用；公开的树形 / 下拉接口仍只暴露 ACTIVE。

自测记录：后端 `scripts/admin-smoke.ps1` **49 项断言全绿**
（未登录 401 / 普通用户 403 / 运营放行，报表与对账字段，类目品牌增改删启停 + 停用可见，
审核通过 / 驳回留痕且被驳回内容公开不可见，强关全链含冻结释放与重复强关被拒，夹具自建自毁、账号用完降权）；
前端 `tsc --noEmit` 零错误、`vite build` 通过。

## 十、评价与晒单（`frontend/src/`，M11/P2-1）

| 路由 / 位置 | 内容 |
|---|---|
| 商品详情页评价区（`components/ReviewSection.tsx`） | 评分汇总（平均分 / 五星分布条 / 好评率 / 带图 / 追评）+ 星级与「有图」筛选 + 分页；匿名评价显示为「匿名用户」 |
| `/reviews/new?orderNo=…` | 按订单逐件评价：**已评过的商品不再出现在表单**，只列还差哪几件；大星号打分、内容、图片直链、匿名勾选 |
| `/reviews/mine` | 我的评价 + 追评弹窗（可否追评由服务端 `appendable` 决定，前端不自己算窗口期） |
| 订单列表（`/orders`） | 「已完成」订单显示**去评价 / 已评价**；已评状态由 `/api/reviews/reviewed` **一次批量**查回，不是每张卡片各发一次请求 |
| 「我的」页快捷入口 | 我的评价 |

**关键实现决策**：

- **评价入口只挂在订单上**，不在导航里另开「评价中心」：评价总是从「我买过的那单」出发，
  这与用户的真实心智一致（也是主流电商的形态）。
- **依赖数组用 `orderNos.join(',')` 而不是数组**：数组每次渲染都是新引用，
  会让 `useAsync` 每轮重发请求 —— 这是 React 里最容易被忽略的性能坑之一。
- **降级而非硬失败**：用户服务不可用时评价仍可看（昵称兜底为「用户 1234」）；
  图片 JSON 脏数据按无图处理，不让整页 500。

自测记录：`scripts/review-smoke.ps1` **59 项断言全绿**；
真实浏览器 `scripts/e2e/review-ui-check.mjs` **20 项断言全绿**（截图 `frontend/docs/screenshots/rev_*.png`）：
备一笔已完成订单 → 订单列表看到「去评价」→ 评价页打分提交 → 我的评价看到内容与匿名标记 → 商品详情公开评价区看到该条。

---

## 十一、后续阶段：端形态与支付口径

### 端形态（用户端两端口，商家与平台都是 Web）

| 端 | 形态 | 面向谁 | 支付方式 |
|---|---|---|---|
| 买家端 | **微信小程序** | 买家 | 小程序内拉起支付（沙箱渠道） |
| 买家端 | **响应式网页** | 买家 | PC 上**扫码支付**（沙箱渠道：二维码 → 收银台 → 确认支付） |
| 商家端 | Web（商家服务平台） | 商家 / 卖家 | 商品、订单、发货、售后、结算对账 |
| 平台管理后台 | Web | 平台运营 | 类目品牌、内容审核、订单干预、资金对账、报表 |

### 支付口径（沙箱支付 · 用户已拍板）

支付用**沙箱渠道**：不申请真实微信/支付宝商户资质，资金不真实流转。
但支付链路的工程形态与真实渠道完全一致（预下单 / 二维码 / 收银台 / 回调验签 / 金额核对 / 幂等 / 过期作废），
将来切真实渠道只需新增 `PaymentChannel` 实现并配置商户密钥。

### 阶段路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 工程骨架 + 公共模块 + 网关 + 用户服务 + 商品服务 | ✅ 完成（54 项冒烟） |
| **P1-a** | 容器化开发环境（中间件 + 业务服务全部容器化、Nacos 服务发现） | ✅ 完成（现已 8 容器 healthy，含履约服务） |
| **P1-b** | 交易服务：购物车 ✅ / 下单拆单 ✅ / 库存冻结 ✅ / 状态机 ✅ / 支付幂等 ✅ / C2C 担保交易 ✅ / 沙箱支付渠道 ✅ / **卖家中心 ✅** | ✅ 完成 |
| **P1-c** | 买家端 **Web 商城**（主交易闭环 10 页，接口指向网关 :8080） | ✅ 完成 |
| **P1-d** | 买家端 **微信小程序** | ⏳ |
| **P1-e** | ~~真实支付渠道~~ → **沙箱支付渠道**（已完成；真实渠道为可选后续） | ✅ |
| **P1-f** | **商家端 Web ✅ + 平台管理后台 Web ✅（M10：报表/对账/审核/类目品牌/订单干预）** | ✅ 完成 |
| **P2** | 履约服务（**评价与晒单 ✅** / 物流轨迹 / 售后仲裁 / 信用分）+ 营销（优惠券）+ XXL-Job 分布式任务 | 🔄 评价已上线 |
| **P3** | AI 服务「多多」+ Elasticsearch 检索 + RocketMQ 削峰 + Sentinel 限流 + Seata 分布式事务 | ⏳ |
| **P4** | SkyWalking / Prometheus 可观测 + K8s 编排 + JMeter 压测（对齐容量验收标准） | ⏳ |

---

## 十二、变更记录

### 2026-09-20（第十轮 · A3 售后三端页面）

- **买家端 3 页**：`/after-sales`（我的售后 + 状态页签）/ `/after-sales/new?orderNo=`（申请：类型 + 诉求 + 举证图）
  / `/after-sales/:id`（详情：步骤条、卖家说明、裁定说明、退款金额 + 申请平台仲裁）。
  入口挂在**订单上**（已发货 / 已完成才显示）与「我的」页 —— 售后总是从「我买过的那单」出发，
  未发货时买家本就能自助退款，走售后反而绕远。
- **卖家端 1 页** `/seller/after-sales`：店铺售后列表 + 处理弹窗，`AGREE` 立刻退款 / `REJECT` 必填原因。
- **运营端 1 页** `/admin/after-sales`：全平台列表 + 裁定弹窗，支持退款（真的执行） / 驳回，**说明必填**。
- **契约修正：售后单 `id` 改为字符串传输**。它是 18 位雪花值，超过 `Number.MAX_SAFE_INTEGER`，
  `JSON.parse` 会静默改写；前端从路由参数原样取用、不做数值转换。
  后端 `AfterSaleView.id` 类型 `Long` → `String`，冒烟脚本同步改为正则提取。
- **三端共用件** `pages/afterSaleShared.tsx`：状态页签、类型文案、状态配色、进度步骤条 ——
  避免「同一状态在两个页面显示成两种说法」。
- **自测**：新增 `scripts/e2e/aftersale-ui-check.mjs` **44 项断言全绿**（真实 Chromium，
  买家→卖家→运营整条链，最后回接口层确认订单与托管同步 `REFUNDED`、退款金额等于实付）；
  前端 `tsc --noEmit` 零错误、`vite build` 通过；本轮改到了订单页 / 「我的」页 / 卖家端与后台共用件，
  故**三套 UI 自测全跑**（`review-ui-check` / `seller-ui-check` 均 `exit=0`）；
  回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。
- **环境坑（本轮新踩 → 08 篇 T-27）**：dev server 报 `listen EACCES: permission denied 127.0.0.1:5173`，
  **不是端口被占、也不是沙箱拦的** —— 是 Windows **排除端口段**：Docker Desktop 启动后 WinNAT 保留
  `5149-5248`，5173 正在其中。查 `netsh int ipv4 show excludedportrange protocol=tcp`；
  改 `--port 3000` + `UI_BASE=http://localhost:3000` 即可（3000 本就在网关 `CORS_ORIGINS` 白名单里）。
- **本轮暴露并修复的 4 个问题**（全部是 `tsc` 与 `vite build` 发现不了的）：
  1. 前端路由是 `after-sales` 而入口链接写成 `/seller/aftersales` → 落到「页面不存在」。
     接口路径 `/api/aftersales` 与前端路由命名**刻意不同**，链接必须照路由写。
  2. `getByRole('button', { name: '处理' })` 是**子串匹配**，先命中页签「待卖家处理」，
     弹窗根本没打开 → 改成行内 `exact: true` 定位。
  3. 用「页面里还有没有『申请平台仲裁』几个字」判断按钮消失，被**成功 toast 的文案**误命中 → 改判按钮计数。
  4. 裁定弹窗默认结论是「驳回」（保守默认值），按钮名是「确认驳回」而不是「支持退款并执行」。
- **数据清理**：清掉前几轮崩溃遗留的 2 条 `PENDING` 售后挂单、4 个未降权的运营账号，
  并把 34 笔被测试改过归属的订单 `seller_id` 归还平台自营。

### 2026-09-18（第九轮 · A3 售后仲裁工单）

- **新增表** `t_after_sale`（履约库）：售后单含诉求、举证图、卖家处理说明、仲裁结论与说明、退款金额；
  唯一键 `(order_id, type)`，所有状态迁移走 CAS 条件更新。
- **新增 8 个端点**：`/api/aftersales`（立案）、`/mine`、`/seller`、`/{id}`、`/{id}/reply`、`/{id}/escalate`、
  `/api/admin/aftersales`、`/api/admin/aftersales/{id}/arbitrate`。两段前缀都**不进网关白名单**。
- **网关**：`/api/admin/aftersales/**` 加入履约域路由 —— 这是**必须显式列举**的一段
  （product / trade 的路由是按具体子路径列的，不是 `/api/admin/**` 兜底），漏了它不会报错，只会 404。
- **跨域**：售后裁定通过时调交易域**既有的**运营侧退款入口，不新开资金通路；
  `03` 篇服务间调用矩阵已登记。**先让钱动、再改售后单状态**。
- **资金边界（B-01）**：已完成订单的钱已放款给卖家，裁定退款会被交易域拒 **422** 并提示走追偿 / 垫付；
  售后单**保持原状态**，不落半个状态。立案仍然放行（买家诉求该被受理）。
- **买家求助路径**：卖家一直不处理时，买家可从 `PENDING` 直接 `escalate` 申请平台仲裁 ——
  否则售后就成了「卖家单方面决定要不要理你」。（这是对 `07` 篇草案的补全：草案只有卖家处理与运营裁定，
  没有「卖家拒绝后买家怎么办」这一步。）
- **重构（消重）**：把评价域私有的「订单号→内部标识哈希」与「图片直链校验/JSON 读写」抽到
  `support/OrderRefs` 与 `support/ImageUrls`，两个子域共用 ——
  同一规则两处实现一旦漂移（比如只在一处补协议白名单）就是安全缺口。
- **自测**：新增 `scripts/aftersale-smoke.ps1` **101 项断言全绿**；回归 `review-smoke 59/0`、`settlement-smoke 125/0`。
  后端 `BUILD SUCCESS`、8 容器 healthy。接口冒烟合计 **11 套 856 项**。
- **编译期暴露的问题**：重构 `ReviewService` 时漏改 `toView` 里的一处调用点，编译报
  `找不到符号: 方法 readImages(String)` —— 类内私有方法重命名靠编译器兜住，这正是保留编译检查的价值。
- **遗留（已在本轮后的第十轮补齐）**：三端页面当时未开工（第九轮交付为后端 + 端到端断言）。

（以下为历史轮次）

### 2026-09-18（第八轮 · A1 历史订单托管单回补 I-14 + 文档口径收敛）

- **新增接口**：`POST /api/admin/settlements/backfill?dryRun=`（运营，幂等）。扫描「已有资金事实、却没有账本」的订单，
  按订单状态推断资金状态：`PAID`/`SHIPPED` → `CUSTODY`、`COMPLETED` → `RELEASED`、`REFUNDED` → `REFUNDED`；
  金额取 `pay_amount`，放款时间取订单**真实的 `finished_at`**（而不是脚本执行时刻 —— 回补的意义是与历史对得上）。
- **参数走查询串而不是请求体**：首轮自测暴露真问题 —— 用 `@RequestBody` 时「无请求体 + 客户端默认 Content-Type」
  会被判成 **415**，运营用 curl 直接调这个运维接口就会踩到；查询串没有这个歧义，
  也与既有的 `?active=false` 写法一致。
- **审计口径**：返回结果带 `commissionBps` 与 `commissionNote` —— 历史单据没有留档结算当刻的费率，
  本次只能用当前 `PLATFORM_FEE_BPS` 重算，这个假设必须随结果一起说清楚。
- **实测影响面（回补前后）**：回补前交易库有 **3 笔 `COMPLETED` 订单缺托管单**（确认收货时放款找不到账本，
  只记了一行 ERROR）；回补后 `GET /api/admin/settlements/abnormal` **返回空**，退款单的缺口同样清零。
- **安全边界**：只碰 `s.id IS NULL` 的订单（已有托管单的原样不动）；不给 `PENDING_PAYMENT`/`CLOSED` 补账；
  逐条独立提交（补了 80 条后第 81 条出错不会回滚前 80 条）。
- **自测**：新增 `scripts/backfill-smoke.ps1` **88 项断言全绿**（权限三层 401/401/403、dryRun 不写库、
  三组金额与笔数精确增量、幂等重复执行、挂账清零、控制组逐字段未变、REFUNDED 盲区单独断言、夹具自建自毁）；
  回归 `settlement-smoke 125/0`、`payment-smoke 71/0`、`admin-smoke 49/0`。后端 `BUILD SUCCESS`、8 容器 healthy。
- **自测暴露并修复的缺陷**：
  ① **API 健壮性**：「无请求体 + 默认 Content-Type」调用回补返回 **415** → 参数改走查询串；
  ② **断言基准过远（同时报假失败与假通过）**：dryRun 的「金额一分未动」原先拿「创建夹具之前」的汇总做基准，
  把控制组自身确认收货的放款增量算到了 dryRun 头上（假失败），又恰好被删掉的三条托管单抵消（假通过）→
  改为**紧贴被测操作**取基准；
  ③ **0 值断言被空响应骗过**：`415` 时 `data` 为 null，`[int]$null -eq 0` 让「扫描到 0 条」假通过 →
  所有 0 值断言加 `$null -ne $body.data` 前置。
- **文档口径收敛**（本轮实测 vs 旧文）：容器 7 → **8**（补 `duoduo-app-fulfilment` 行）、
  业务库 3 → **4**、Nacos 注册 4 → **5** 个服务、冒烟 9 套 667 项 → **10 套 755 项**。

（以下为历史轮次）

### 2026-09-18（第七轮 · AI Agent 接管包 + 交接资料双渠道归档）

- 仓库根新增 `AGENTS.md`（AI 编码工具默认入口：三分钟自检 / 必读顺序 / 十条硬规矩 / 交付动作清单）。
- 新增 `docs/agent/00~08`（接管指令与硬规矩 C1–C10 · 领域模型 · 代码地图 · 架构不变式 INV-1~15 ·
  接口契约全集 · 开发任务手册 R1–R6 · 测试门禁 · 待办 A1–A13 · 环境与踩坑 T-01~T-24），
  并同步到乐享知识库「多多宠物商店知识库」（现共 20 篇）。
- 新增 `docs/agent/PROMPT-新AI接手指令.md`：换 AI 接手时的启动提示词（先理解、给计划、经确认再编码）。
- `docs/handover/` 9 篇双渠道归档（腾讯文档项目资产 + 乐享知识库），`manifest.json` 记录全部云端 id。

### 2026-09-18（第六轮 · 履约服务 M11/P2-1：评价与晒单）

- **新增服务**：`duoduo-fulfilment`（:8084）—— 按架构文档的拆分落位（发货 / 物流 / 售后 / 仲裁 / 评价 / 信用分同域），
  本轮先落地「评价与晒单」纵切；compose 第 8 个容器，网关路由 `/api/reviews/**`（白名单只放行只读 `product/**`）。
- **跨域只读**：交易域新增 `GET /api/orders/internal/{orderNo}/fact`（订单事实，**裁剪掉收货人信息**）；
  用户域新增 `GET /api/users/internal/briefs`（批量昵称头像，**只回三字段**）+ `InternalTokenFilter`。
- **评价规则**：仅本人已完成订单可评、`spuId` 须在订单明细、一单一商品一评（唯一索引 + 409）、
  图片 http/https 白名单、追评一次 + 窗口期、匿名只脱敏不删真实主体。
- **前端**：商品详情评价区、`/reviews/new` 逐件评价、`/reviews/mine` 追评、订单列表去评价入口。
- **自测**：`scripts/review-smoke.ps1` **59/0**；`scripts/e2e/review-ui-check.mjs` **20/0**；`tsc` 零错误、`vite build` 通过。
- **自测暴露并修复**：① 内部接口断言打错服务端口（无路由 500 被误读为过滤器故障）；
  ② **PowerShell 把 `$SPU?page=1` 整体当变量名**（`?` 是合法变量名字符）→ 一律写 `${SPU}?`；
  ③ 19 位雪花主键经 `ConvertFrom-Json` 丢精度导致假失败 → 断言改用业务键/原始文本提取；
  ④「商品不在该订单中」由 400 归位为 422（业务规则，不是参数格式错）。

（以下为历史轮次）

### 2026-09-18（第五轮 · 平台管理后台 M10）

- **后端**：`/api/admin/**` 13 个接口（运营报表 / 资金对账 / 类目品牌管理 / 闲置审核 / 订单查询与强关），
  统一 `OperatorGuard` 准入；`t_listing` 补 `audit_reason` / `audit_at` 审核留痕列；
  网关路由补 `/api/admin/**`（不进白名单）。
- **前端**：`pages/admin/` 5 个工作台（运营概览含对账、内容审核、类目、品牌、订单干预）+
  `RequireOperator` 守卫 + 按角色显示的「管理后台」入口。
- **自测**：新增 `scripts/admin-smoke.ps1` **49 项断言全绿**（夹具自建自毁、运营账号用完降权）。
- **自测暴露并修复**：① product 服务 `id-type: input` 下创建类目/品牌未赋主键报 500 → 显式雪花 id；
  ② 审核动词与状态常量比较错位，任何审核都被 422 → 动词/状态两层映射；
  ③ 后台列表复用只含 ACTIVE 的公开查询，停用后无法重新启用 → 专用后台列表 + DTO 带 `status`；
  ④ PS 5.1 中文脚本必须 UTF-8 BOM；`docker exec mysql` 控制台输出 GBK，中文等值断言要改走接口读回。

（以下为历史轮次）

### 2026-09-18（第四轮 · 卖家中心 M5-9 + 商家端 Web M9）

- **卖家账号体系**：`POST /api/users/me/merchant`（实名前置 422、幂等、角色写 `SELLER`）；
  `LoginUser` 修角色大小写 bug 并新增 `isSeller()` / `canAccessSellerConsole()`。
- **卖家中心 6 个接口**（`/api/seller/**`）：概览 / 订单列表 / 订单详情 / 发货 / 售后退款 / 结算对账。
  发货与退款**复用 `OrderService` 的 `shipInternal` / `refundInternal`**，状态机只有一份。
- **权限模型**：准入（角色 403）与归属（`seller_id`）分开校验；不存在 404、越权 403 刻意区分；
  接口不接受任何 `sellerId` 参数；运营可见平台自营 `sellerId=0`；聚合下推到 SQL，不全量捞回内存。
- **网关与过滤器**：trade 路由补 `/api/seller/**`（不在白名单，必须登录）；
  `InternalTokenFilter` 排除 `/api/seller/**`。
- **修复真实缺陷**：客户端发错 `Content-Type` 原本落到兜底返回 **500** → 新增
  `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 handler，返回 **415**。
- **物流信息落库**：`t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，
  买家与卖家详情页都显示 —— 此前只写进状态日志，卖家发货后在详情页看不到自己填的单号。
- **商家端 Web 4 页**（`/seller` · `/seller/orders` · `/seller/orders/:orderNo` · `/seller/settlements`），
  Layout 与「我的」页按角色显示入口；补 favicon，消除 `/favicon.ico` 404。
- **自测**：后端 6 模块 `BUILD SUCCESS`，4 个业务镜像重建、当时 7 容器 healthy；
  新增 `scripts/seller-smoke.ps1` **84 项断言全绿**；真实浏览器
  `scripts/e2e/seller-ui-check.mjs` **29 项断言全绿**（含截图）；
  前端 `tsc --noEmit` 零错误、`vite build` 通过。七套接口冒烟合计 **557 项**。
- **UI 自测暴露并修复**：物流信息没落订单表（只写进状态日志）；页面缺 favicon 导致 404。

### 2026-09-17（第三轮 · 买家端 Web 商城 M6 / P1-c）
- **新建 `frontend/`**（React 18 + TS strict + Vite 5）：12 路由覆盖主交易闭环 10 页；
  API 层（类型化客户端 + 端点清单 + 统一错误/重试/刷新）、通用组件（卡片/分页/弹窗/状态步骤/表格）、
  两个 store（auth 会话恢复 / 购物车角标）。
- **零依赖二维码组件**：自实现 QR 编码（Byte 模式 / L-M-Q-H 纠错 / 版本 1–10 / 8 掩码择优），
  服务于沙箱扫码支付；支付弹窗带 30 分钟倒计时 + 3 秒轮询（支付成功自动刷新订单状态）。
- **与后端口径逐一对齐**（以真实接口实测为准）：响应解包 `data`、分页嵌套于 `data`、
  金额单位分、成色/物种/生命周期字典码值、订单状态枚举、托管三态、幂等键语义。
- **两处自测暴露的前端缺陷修复**：① 闲置成色原先硬编码中文，而后端下发码值
  （`NEW`/`LIKE_NEW`/…），会导致筛选下发错值——改为字典驱动 + 常量兜底映射；
  ② 宠物建档表单未提示「生命阶段须与生日推算一致」（后端 422 规则）——
  表单增加实时一致性提示与一键修正，提交前拦截并说明原因。
- **登录页演示账号修正**：实测演示库 `1380000*` 段无预置账号（此前文档与数据不符），
  移除失效的快捷填充按钮，改为「一键生成体验账号」走真实注册流程。
- **验证手段**：curl 端到端冒烟（注册→加购→下单幂等→沙箱扫码→托管→退款，全绿）+
  真实 Chromium 逐页渲染验证（12 路由），截图归档 `frontend/docs/screenshots/`。

### 2026-09-18（沙箱支付渠道 M8-1）
- **支付口径定为沙箱支付**（用户拍板）：不接真实微信/支付宝商户渠道，资金不真实流转。
- **`PaymentChannel` 渠道抽象**：预下单 / 回调验签 / 查询三个动作；`MOCK`（即时成功）与
  `SANDBOX`（扫码形态）两个实现；`PaymentChannelRegistry` 统一注册。将来接真实渠道只加实现类。
- **支付改为「预下单」语义**：`POST /api/orders/{orderNo}/pay` 返回 `codeUrl`（二维码内容，
  指向网关的沙箱收银台）与过期时间（默认 15 分钟）；`MOCK` 渠道保持即时完成（自动化测试用）。
- **沙箱收银台**（`GET /api/payments/sandbox/pay/{payNo}`）：服务端渲染页面，展示单号 / 金额 / 状态，
  「模拟扫码支付」按钮走 `POST /api/payments/sandbox/{payNo}`，**与渠道回调共用同一套验签与入账逻辑**。
- **回调入口**（`POST /api/payments/callback/{channel}`）：HMAC-SHA256 验签 + 时间戳窗口（±5 分钟）
  + **金额核对**（不一致拒绝入账）+ 幂等（先查 + 唯一索引 + CAS）；重复回调只入账一次。
- **支付单生命周期**：`PENDING → SUCCESS / EXPIRED / CLOSED`；`t_payment` 增加 `code_url`、`expire_at`；
  过期扫描并入补偿调度；**订单取消/关单时作废未支付支付单**（接通了此前一直缺失的 `markClosedByOrder`，
  修复「订单已取消、二维码还能扫出钱来」的漏洞）。
- **六套端到端冒烟共 475 项断言全绿**：54 / 87 / 76 / 71 / 125 / **62（新增支付渠道）**。
- 修复自测暴露的问题：① 未知渠道/MOCK 回调拒绝用错了 400（应为 422 业务错误，与注册表 javadoc 一致）；
  ② 二维码内容默认指向交易服务 8083 直连地址（扫码设备不可达，改为网关 8080 并补网关路由与白名单）；
  ③ 收银台响应头缺 charset（严格客户端按 Latin-1 解码中文乱码，已声明 UTF-8）。

### 2026-09-17（第二轮）
- **C2C 担保交易（M5-8）**：买家付款后资金进平台托管（`t_settlement`，`uk_settle_order` 一单一条）；
  确认收货放款给卖家；退款原路退回买家；**发货满 N 天自动确认收货并放款**（补偿调度的一部分）。
  资金状态迁移全部用 CAS 条件更新，重复放款 / 重复退款天然无副作用。
- **订单状态机新增终态 `REFUNDED`**，并移除历史上那条被误用的 `PAID → CLOSED` 边
  （它既是售后退款通路，也就等于把「用户自助取消已付款订单」的口子一起打开了）。
- **退款两个入口**：买家自助（仅已付款未发货）/ 运营侧（已付款、已发货，走 `X-Internal-Token`）；
  已放款订单的退款被明确拒绝（需追偿或平台垫付）。
- **退货入库**：商品服务新增 `restock` 与 `POST /api/products/skus/stock/restock`；
  退款后货回库走台账 `RESTOCK_PENDING → RESTOCKED`，失败由补偿调度重试 —— **退款不因入库失败而回滚**。
- **`t_order` 增加 `refunded_at`**（退款不是「订单关闭」，资金动作需要独立时间戳）与
  `idx_order_auto_receipt`（自动收货扫描）索引。
- 修复自测暴露的问题：① 退货入库端点编译通过但运行时 404（新增端点无内部引用，编译发现不了，
  必须实调一次验证）；② 自测脚本里佣金取整用了 PowerShell 的「四舍五入」而非 Java 的整数除；
  ③ 跨轮次断言未按时间范围限定，被历史数据误伤。

### 2026-09-17（第一轮）
- **交易域全链路打通**：购物车（7 接口）→ 下单拆单（含明细快照与库存冻结）→ 支付（幂等）→ 发货 → 确认收货 → 超时关单。
- **商品服务新增库存写接口**：`/api/products/skus/stock/{freeze,release,deduct}`，原子条件更新 + 内部凭据保护。
- **可售口径统一**为 `stock − frozen_stock`（原「只看 stock>0」会把已被他人下单锁定的商品仍显示有货）。
- 修复自测暴露的三个真实缺陷：① 已付款订单可被用户自助取消；② 超时关单释放依据错用「台账自身的过期时间」；
  ③ 并发释放时「冻结量不足」被当作失败，导致台账永远卡在 FROZEN（改为冲正为 RELEASED 并留审计日志）。

### 更早
- 容器化开发环境：当时 7 容器全 healthy、Nacos 注册 4 服务、网关 `lb://` 生效、`down -v` 从零重建可复现。
- 修复中文「双重编码」缺陷：初始化 SQL 一律以 `SET NAMES utf8mb4;` 开头，
  否则中文会以 latin1 入库，表现为「数据看似存在、但中文关键词检索永远 0 命中」。
- 商品域：类目树 / 品牌 / SPU-SKU 检索（6 种排序）/ 价格区间区间相交语义 / 物种与生命阶段筛选 /
  违禁词 422 / C2C 闲置可见性控制。
- 用户域：注册登录 / httpOnly Cookie / 网关 401 拦截 / 身份透传 / 实名掩码 /
  宠物档案与「生命阶段矛盾」422。
