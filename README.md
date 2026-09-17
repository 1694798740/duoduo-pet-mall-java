# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)；开发看板见 [`docs/PLAN.md`](./docs/PLAN.md)。

**最近更新：2026-09-17** — 交易域推进到「下单 → 库存冻结 → 支付 → 发货 → 收货 → 超时关单」全链路打通；
四套端到端冒烟共 **288 项断言全绿**。详见文末「变更记录」。

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
| `duoduo-app-product` | duoduo/duoduo-product:1.0.0 | 8082 | 类目 / 品牌 / SPU-SKU / 检索 / 首页 / C2C 闲置 / 库存主数据 |
| `duoduo-app-trade` | duoduo/duoduo-trade:1.0.0 | 8083 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / 支付幂等 |

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

### 端到端冒烟（四套，共 288 项断言）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1          # 54 项：用户域 + 商品域
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1    # 87 项：购物车
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1    # 76 项：下单拆单 + 库存冻结
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1  # 71 项：支付幂等 + 状态机 + 超时关单
```

四套脚本的共同约定（踩过坑才有的）：
- 每次运行使用**随机手机号**，可重复执行；
- 夹具（价格、库存）一律**从接口动态取**，不硬编码 —— 否则种子数据一改，测试就会「通过但其实是错的」；
- 凡改库必**先记录原值、复原写回原值**，并在结尾**自证**「库内数据已复原」；
- 前两套加 `-SkipDbCheck` 可跳过改库段落，只跑接口断言。

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
│   ├── PLAN.md                 开发看板：里程碑 / 任务 / 问题与协助区 / 命令速查
│   ├── GITHUB-PUSH-GUIDE.md    推送指南
│   └── sql/                    容器初始化 SQL（00 建库授权 / 01 用户库 / 02 商品库 / 03 交易库）
├── scripts/                    一键启动、冒烟测试（4 套）
├── duoduo-common/              通用：统一响应 / 错误码 / 业务异常 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/          Servlet 侧基础设施：全局异常处理 / 请求 ID / 身份上下文透传
├── duoduo-gateway/             API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
├── duoduo-user/                用户服务
├── duoduo-product/             商品服务（含库存主数据）
└── duoduo-trade/               交易服务（购物车 / 订单 / 支付）
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
| POST | `/api/products/skus/stock/{freeze,release,deduct}` | **库存写接口**（冻结 / 释放 / 扣减），见下 | **内部凭据** |
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
| GET | `/api/orders` | 我的订单列表（可按 `status` 过滤） | 需登录 |
| GET | `/api/orders/{orderNo}` | 订单详情（含明细快照） | 需登录 |
| POST | `/api/orders/{orderNo}/pay` | **支付**（模拟渠道，必须带 `idempotentKey`），重复回调只入账一次 | 需登录 |
| POST | `/api/orders/{orderNo}/cancel` | 取消（**仅待付款可取消**），并释放冻结 | 需登录 |
| POST | `/api/orders/{orderNo}/confirm` | 确认收货（已发货 → 已完成） | 需登录 |
| POST | `/api/orders/{orderNo}/ship` | 发货（已付款 → 已发货） | **内部凭据** |
| POST | `/api/orders/internal/sweep` | 手动触发一轮超时关单补偿调度（运维排障 / 自动化测试用） | **内部凭据** |

购物车条目的失效原因码（机器可读，前端只管展示文案）：
`SKU_NOT_FOUND` / `OFF_SHELF` / `OUT_OF_STOCK` / `STOCK_NOT_ENOUGH`。
**失效条目不计入结算金额** —— 否则结算页金额与订单金额会对不上。

订单状态机：`待付款 → 已付款 → 已发货 → 已完成`，分支 `已关闭`（用户取消或超时未支付）。
每次流转都写 `t_order_status_log`（只增不改），出问题时能查清「谁在什么时候改的」。

### 内部接口的凭据

库存写接口与发货/补偿调度属于**服务间或运营侧**调用，要求请求头 `X-Internal-Token`，
由 `InternalTokenFilter` 校验（**默认开启、失败关闭**）。密钥来自环境变量 `INTERNAL_TOKEN`，
compose 里提供开发默认值，**生产必须注入真实值**。

---

## 四、已落地的工程约束

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
- **幂等靠唯一索引，不靠分布式锁**：
  下单 `uk_order_user_idem(user_id, idempotent_key)`、支付 `uk_pay_channel_idem(channel, idempotent_key)`、
  加购 `uk_cart_user_sku`、冻结 `uk_freeze_sku_order`。锁只用来减少冲突，正确性由数据库保证。
- **库存原子性**：冻结 / 释放 / 扣减都是**条件 UPDATE**（判断写进 WHERE），
  由数据库行级原子性防超卖；单次请求内的整批 SKU 在同一本地事务里**要么全成、要么全不成**。
- **跨服务副作用的处理**：库存冻结/扣减发生在商品服务，**不受交易服务事务保护**，
  因此下单失败时会显式补偿释放；同时保留「超时关单补偿调度」作为最终兜底（可重入）。
- **状态机集中在枚举**：合法流转写在一处，非法流转一处拒绝；各接口再收窄自己的合法起点
  （例如「已付款 → 已关闭」只属于退款流程，不开放给用户自助取消）。
- **业务规则**：宠物档案的「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）。

---

## 五、后续阶段（按 docs/ARCHITECTURE.md 的路线推进）

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 工程骨架 + 公共模块 + 网关 + 用户服务 + 商品服务 | ✅ 完成（54 项冒烟） |
| **P1-a** | 容器化开发环境（中间件 + 业务服务全部容器化、Nacos 服务发现） | ✅ 完成（7 容器 healthy） |
| **P1-b** | 交易服务：购物车 ✅ / 下单拆单 ✅ / 库存冻结与释放 ✅ / 订单状态机 ✅ / 支付幂等 ✅ / **C2C 担保交易 + 卖家中心 ⏳** | 🔄 进行中 |
| **P1-c** | 前端接入（复用 Node 版 17 个页面，接口指向网关 :8080） | ⏳ |
| **P2** | 履约服务（物流/售后/仲裁）+ 评价 + 营销（优惠券）+ XXL-Job 分布式任务 | ⏳ |
| **P3** | AI 服务「多多」+ Elasticsearch 检索 + RocketMQ 削峰 + Sentinel 限流 + Seata 分布式事务 | ⏳ |
| **P4** | SkyWalking / Prometheus 可观测 + K8s 编排 + JMeter 压测（对齐容量验收标准） | ⏳ |

前端（React 18 + Vite）沿用此前实现的 17 个页面，接口契约调整后即可对接。

---

## 六、变更记录

### 2026-09-17
- **交易域全链路打通**：购物车（7 接口）→ 下单拆单（含明细快照与库存冻结）→ 支付（幂等）→ 发货 → 确认收货 → 超时关单。
- **商品服务新增库存写接口**：`/api/products/skus/stock/{freeze,release,deduct}`，原子条件更新 + 内部凭据保护。
- **可售口径统一**为 `stock − frozen_stock`（原「只看 stock>0」会把已被他人下单锁定的商品仍显示有货）。
- **四套端到端冒烟共 288 项断言全绿**：54（用户+商品）/ 87（购物车）/ 76（下单冻结）/ 71（支付状态机）。
- 修复自测暴露的三个真实缺陷：① 已付款订单可被用户自助取消（状态机边被误用）；
  ② 超时关单释放依据错用「台账自身的过期时间」（与订单过期时间形成两份真相），改为以订单状态为准；
  ③ 并发释放时「冻结量不足」被当作失败，导致台账永远卡在 FROZEN（改为冲正为 RELEASED 并留审计日志）。

### 更早
- 容器化开发环境：7 容器全 healthy、Nacos 注册 4 服务、网关 `lb://` 生效、`down -v` 从零重建可复现。
- 修复中文「双重编码」缺陷：初始化 SQL 一律以 `SET NAMES utf8mb4;` 开头，
  否则中文会以 latin1 入库，表现为「数据看似存在、但中文关键词检索永远 0 命中」。
- 商品域：类目树 / 品牌 / SPU-SKU 检索（6 种排序）/ 价格区间区间相交语义 / 物种与生命阶段筛选 /
  违禁词 422 / C2C 闲置可见性控制。
- 用户域：注册登录 / httpOnly Cookie / 网关 401 拦截 / 身份透传 / 实名掩码 /
  宠物档案与「生命阶段矛盾」422。
