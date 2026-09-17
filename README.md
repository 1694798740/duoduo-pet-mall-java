# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)；开发看板见 [`docs/PLAN.md`](./docs/PLAN.md)。

**最近更新：2026-09-17（第三轮）** — **买家端 Web 商城（M6 / P1-c）完成**：新建 `frontend/`
（React 18 + TypeScript + Vite），10 页覆盖「注册登录 → 检索 → 详情 → 购物车 → 结算 → 下单 →
扫码支付（二维码 + 3 秒轮询）→ 资金托管明细」全闭环；已在真实 Chromium 中逐页验证，
`tsc --noEmit` 零错误、`vite build` 通过。详见「七、买家端 Web 商城」与文末「变更记录」。

---

## 一、运行方式（Docker 优先）

中间件与业务服务**全部容器化**，一条命令起全套：

```bash
docker compose up -d          # 起 7 个容器
docker ps                     # 期望：7 个容器全是 healthy
```

| 容器 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `duoduo-mysql` | mysql:8.0.36 | 3306 | 三个库：`duoduo_user` / `duoduo_product` / `duoduo_trade`（`nacos` 库由 Nacos 自建） |
| `duoduo-redis` | redis:7.2-alpine | 6379 | 缓存与分布式能力预留 |
| `duoduo-nacos` | nacos/nacos-server:v2.3.2 | 8848 / 9848 | 服务注册与配置；**已注册 4 个服务** |
| `duoduo-app-gateway` | duoduo/duoduo-gateway:1.0.0 | 8080 | 统一入口：路由 + JWT 前置校验 + 身份透传 + CORS |
| `duoduo-app-user` | duoduo/duoduo-user:1.0.0 | 8081 | 注册登录 / 令牌轮转 / 实名 / 宠物档案 |
| `duoduo-app-product` | duoduo/duoduo-product:1.0.0 | 8082 | 类目 / 品牌 / SPU-SKU / 检索 / 首页 / C2C 闲置 / **库存主数据** |
| `duoduo-app-trade` | duoduo/duoduo-trade:1.0.0 | 8083 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / **支付（沙箱扫码）** / C2C 担保交易 |

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

### 端到端冒烟（七套 557 项接口断言 + 一套 29 项真实浏览器 UI 断言）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1              # 54 项：用户域 + 商品域
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1        # 87 项：购物车
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1        # 76 项：下单拆单 + 库存冻结
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1      # 71 项：支付幂等 + 状态机 + 超时关单
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1   # 125 项：担保交易 + 资金托管 + 退款
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1  # 62 项：沙箱支付渠道（扫码/验签/回调）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1       # 84 项：卖家中心（准入/归属隔离/发货/售后/结算）

# 商家端 Web 的真实浏览器 UI 自测（29 项；需先起前端 dev server）
cd frontend && node node_modules/vite/bin/vite.js --port 5173    # 另开终端，必须走 localhost:5173
cd .. && node scripts/e2e/seller-ui-check.mjs
```

七套脚本的共同约定（踩过坑才有的）：
- 每次运行使用**随机手机号**，可重复执行；
- 夹具（价格、库存、佣金）一律**从接口动态取**，不硬编码 —— 否则种子数据一改，测试就会「通过但其实是错的」；
- 凡改库必**先记录原值、复原写回原值**，并在结尾**自证**「库内数据已复原」；
- 跨轮次的断言按「本次测试开始时间」**限定范围**，不被功能上线前的历史数据误伤；
- 部分脚本加 `-SkipDbCheck` 可跳过改库段落，只跑接口断言。

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
├── docker-compose.yml          7 容器编排（中间件 + 4 个业务服务）
├── maven/                      项目专用 Maven 配置（阿里云镜像）与本地环境变量脚本
├── docs/
│   ├── ARCHITECTURE.md         完整架构设计（服务拆分、分库分表、容量模型、降级矩阵、路线）
│   ├── PLAN.md                 开发看板：里程碑 / 端形态 / 支付渠道 / 问题与协助区 / 已知边界
│   ├── GITHUB-PUSH-GUIDE.md    推送指南
│   └── sql/                    容器初始化 SQL（00 建库授权 / 01 用户库 / 02 商品库 / 03 交易库）
├── scripts/                    一键启动、冒烟测试（6 套）
├── duoduo-common/              通用：统一响应 / 错误码 / 业务异常 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/          Servlet 侧基础设施：全局异常处理 / 请求 ID / 身份上下文透传
├── duoduo-gateway/             API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
├── duoduo-user/                用户服务
├── duoduo-product/             商品服务（含库存主数据）
├── duoduo-trade/               交易服务（购物车 / 订单 / 冻结台账 / 支付渠道 / 资金托管）
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
npm run dev        # http://localhost:5173，需后端 7 容器在跑（网关 :8080）
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

## 九、后续阶段：端形态与支付口径

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
| **P1-a** | 容器化开发环境（中间件 + 业务服务全部容器化、Nacos 服务发现） | ✅ 完成（7 容器 healthy） |
| **P1-b** | 交易服务：购物车 ✅ / 下单拆单 ✅ / 库存冻结 ✅ / 状态机 ✅ / 支付幂等 ✅ / C2C 担保交易 ✅ / 沙箱支付渠道 ✅ / **卖家中心 ✅** | ✅ 完成 |
| **P1-c** | 买家端 **Web 商城**（主交易闭环 10 页，接口指向网关 :8080） | ✅ 完成 |
| **P1-d** | 买家端 **微信小程序** | ⏳ |
| **P1-e** | ~~真实支付渠道~~ → **沙箱支付渠道**（已完成；真实渠道为可选后续） | ✅ |
| **P1-f** | **商家端 Web（商家服务平台）✅** + 平台管理后台 Web ⏳ | 🔄 商家端已上线 |
| **P2** | 履约服务（物流/售后/仲裁）+ 评价 + 营销（优惠券）+ XXL-Job 分布式任务 | ⏳ |
| **P3** | AI 服务「多多」+ Elasticsearch 检索 + RocketMQ 削峰 + Sentinel 限流 + Seata 分布式事务 | ⏳ |
| **P4** | SkyWalking / Prometheus 可观测 + K8s 编排 + JMeter 压测（对齐容量验收标准） | ⏳ |

---

## 十、变更记录

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
- **自测**：后端 6 模块 `BUILD SUCCESS`，4 个业务镜像重建、7 容器 healthy；
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
- 容器化开发环境：7 容器全 healthy、Nacos 注册 4 服务、网关 `lb://` 生效、`down -v` 从零重建可复现。
- 修复中文「双重编码」缺陷：初始化 SQL 一律以 `SET NAMES utf8mb4;` 开头，
  否则中文会以 latin1 入库，表现为「数据看似存在、但中文关键词检索永远 0 命中」。
- 商品域：类目树 / 品牌 / SPU-SKU 检索（6 种排序）/ 价格区间区间相交语义 / 物种与生命阶段筛选 /
  违禁词 422 / C2C 闲置可见性控制。
- 用户域：注册登录 / httpOnly Cookie / 网关 401 拦截 / 身份透传 / 实名掩码 /
  宠物档案与「生命阶段矛盾」422。
