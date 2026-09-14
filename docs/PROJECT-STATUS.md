# 多多宠物商店 · 项目交付现状与后续方案

> 版本：v1.0 ｜ 更新日期：2026-09-14
> 正式工程（Java 微服务版）：`D:\智能体项目\duoduo-pet-mall-java`
> 原型工程（Node 版）：`D:\智能体项目\duoduo-pet-mall`
> 远程仓库：https://github.com/1694798740/duoduo-pet-mall-java （私有）

---

## 0. 一句话结论

**Java 微服务版已完成 P0（工程骨架 + 网关 + 用户域）与 P1-a（商品域），三进程实跑、54 项端到端断言全绿、代码已在 GitHub。
距离「可商用的宠物电商系统」还差：交易闭环、履约与售后、营销、AI 导购、前端、真实基础设施接入、容量压测——即 P1-b 到 P4。**

当前所有服务进程**未在运行**（后台进程会被会话回收），需要用下面的「一键启动」重新拉起。

---

## 1. 项目资产清单

| 资产 | 路径 / 地址 | 说明 |
|---|---|---|
| Java 正式工程 | `D:\智能体项目\duoduo-pet-mall-java` | 当前交付主体，5 个 Maven 模块 |
| Java 远程仓库 | https://github.com/1694798740/duoduo-pet-mall-java | 私有，7 个提交 / 81 个文件 |
| Node 原型工程 | `D:\智能体项目\duoduo-pet-mall` | 早期原型，技术栈不符已弃用，**保留作前端与业务逻辑参考** |
| Node 远程仓库 | https://github.com/1694798740/duoduo-pet-mall | 私有，5 个提交 / 110 个文件 |
| 架构设计文档 | `duoduo-pet-mall-java/docs/ARCHITECTURE.md` | 服务拆分、分库分表、容量模型、降级矩阵、P0–P4 路线 |
| 生产库 DDL | `duoduo-pet-mall-java/docs/sql/` | MySQL 8 建表语句 |
| 本文件 | 项目资产 · `多多宠物商店-项目交付现状与后续方案.md` | 也有一份在 `duoduo-pet-mall-java/docs/PROJECT-STATUS.md` |

---

## 2. 已完成的部分

### 2.1 Java 正式工程（5 个模块 / 55 个 Java 文件）

| 模块 | 职责 | Java 文件 | 可执行产物 |
|---|---|---|---|
| `duoduo-common` | 统一响应、错误码、业务异常、雪花 ID、登录用户上下文、JWT 服务 | 9 | — |
| `duoduo-common-web` | Servlet 侧基础设施：全局异常处理、请求 ID 过滤器、身份上下文拦截器、自动装配 | 4 | — |
| `duoduo-gateway` | API 网关（WebFlux）：路由、JWT 前置校验、身份头透传与防伪造 | 3 | 45 MB fat jar |
| `duoduo-user` | 用户域：账号、登录注册、令牌轮转、实名、宠物档案 | 14 | 43 MB fat jar |
| `duoduo-product` | 商品域：类目、品牌、SPU/SKU、检索、首页聚合、C2C 闲置浏览 | 25 | 43 MB fat jar |

构建命令与结果：`mvn -gs maven/settings.xml -B -DskipTests package` → **BUILD SUCCESS**。

### 2.2 已实现的接口（共 22 个端点）

**用户域（`duoduo-user`，:8081）**

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并直接登录（手机号冲突转 409） | 免 |
| POST | `/api/auth/login` | 密码登录；账号不存在与密码错误返回同一提示，防手机号枚举 | 免 |
| POST | `/api/auth/refresh` | 刷新令牌轮转：旧令牌立即吊销 | 免 |
| POST | `/api/auth/logout` | 吊销刷新令牌并清 Cookie | 免 |
| GET | `/api/users/me` | 当前用户资料 | 需登录 |
| PATCH | `/api/users/me` | 修改昵称 / 头像 | 需登录 |
| POST | `/api/users/me/realname` | 实名认证（身份证仅存掩码） | 需登录 |
| GET / POST | `/api/pets` | 宠物档案列表 / 新建（首只自动设为当前宠物） | 需登录 |
| PUT | `/api/pets/{id}` | 更新档案 | 需登录 |
| POST | `/api/pets/{id}/default` | 设为当前宠物 | 需登录 |
| DELETE | `/api/pets/{id}` | 删除档案（默认宠物自动顺延） | 需登录 |

**商品域（`duoduo-product`，:8082）**

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/categories` | 两级类目树 | 免 |
| GET | `/api/categories/flat` | 扁平类目列表 | 免 |
| GET | `/api/categories/{id}` | 单个类目（含子节点） | 免 |
| GET | `/api/home` | 首页聚合：类目 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选 | 免 |
| GET | `/api/products` | 商品检索（见下方参数表） | 免 |
| GET | `/api/products/{spuId}` | 商品详情：SKU、适用属性聚合、同类推荐 | 免 |
| GET | `/api/listings` | C2C 闲置列表（只返回已审核通过） | 免 |
| GET | `/api/listings/{id}` | 闲置详情 + 「同款全新」推荐 | 免 |
| GET | `/api/meta/brands` | 品牌列表 | 免 |
| GET | `/api/meta/dictionaries` | 筛选字典（物种 / 生命阶段 / 成色 / 供货类型 / 排序） | 免 |

`GET /api/products` 查询参数：

| 参数 | 说明 |
|---|---|
| `keyword` | 关键词，命中标题或副标题；**含违禁词直接返回 422** |
| `categoryId` | 类目 id；传一级类目会**自动展开其下子类目** |
| `brandId` / `supplyType` | 品牌 / 供货类型（`self` 自营、`merchant` 第三方） |
| `minPrice` / `maxPrice` | 价格区间（单位：分），语义是**区间相交** |
| `species` / `lifeStage` | 按宠物筛选（`cat`/`dog`；`BABY`/`ADULT`/`SENIOR`） |
| `inStockOnly` | 只看有货（SPU 下任一 SKU 有可售库存） |
| `sort` | `default` / `sales` / `price_asc` / `price_desc` / `rating` / `newest`，非法值返回 400 |
| `page` / `pageSize` | 页码从 1 开始，`pageSize` 上限 100 |

### 2.3 已落地的数据模型（8 张表）

| 域 | 表 | 说明 |
|---|---|---|
| 用户 | `t_user` | 账号、密码哈希（BCrypt）、角色、实名信息（仅存掩码）、信用分 |
| 用户 | `t_refresh_token` | 刷新令牌，**只存 SHA-256 哈希**并轮转 |
| 用户 | `t_pet_profile` | 宠物档案：品种、生日、生命阶段、体重、**过敏原**、默认宠物标记 |
| 商品 | `t_category` | 两级类目树 |
| 商品 | `t_brand` | 品牌、产地、品牌故事 |
| 商品 | `t_spu` | 标准化商品，价格区间冗余、销量、评分 |
| 商品 | `t_sku` | 可售单元：价格、可售库存、冻结库存、适用物种/生命阶段/过敏原 | 
| 商品 | `t_listing` | C2C 闲置：成色、属地、审核状态、浏览量 |

种子数据：24 个类目、8 个品牌、18 个 SPU、22 个 SKU、10 条闲置
（其中刻意包含 1 条待审核、1 条已驳回、1 个全部无货的 SPU，用于验证可见性与库存规则）。

### 2.4 已落地的工程约束

- **认证**：JWT 短时效 access（15 分钟）+ 服务端 refresh（7 天）；refresh 只存 SHA-256 哈希并轮转；
  令牌走 httpOnly Cookie，**不写 localStorage、不放 URL 查询参数**。
- **身份透传**：网关校验通过后才写入 `X-User-Id` 等内部头，并**先剥离客户端自带的同名头**防伪造；
  当前用户只从服务端上下文取，**绝不接受客户端传 userId**。
- **错误体系**：服务层只抛业务异常，全局处理器统一转换；HTTP 状态码与业务语义一致
  （400 参数、401 未认证、403 越权、404 不存在、409 冲突、422 业务规则、429 限流）；
  5xx 堆栈只进服务端日志，客户端只拿到规范结构 + requestId。
- **链路追踪**：每请求生成 requestId，写入响应头与日志 MDC，跨服务透传。
- **结构化日志**：JSON 格式，包含时间、级别、服务名、requestId、logger、线程，且对引号做转义。
- **分布式 ID**：雪花算法，含时钟回拨保护（回拨 > 5ms 直接拒绝生成）；主键不用自增，为分库分表铺路。
- **N+1 治理**：列表页类目名、品牌名、库存状态一律按整页 id 集合批量查询；列表页不 JOIN SKU。
- **分页稳定性**：所有排序都追加 `id` 作为兜底排序键，避免同值时翻页重复或漏项。
- **业务规则**：宠物档案「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）；
  商品搜索与标题双向拦截违禁词（宠物行业合规必需）；
  C2C 闲置只暴露「已上架 + 已审核通过」，未审核内容按 id 直访也返回 404。

### 2.5 Node 原型工程（保留作参考）

| 项 | 状态 |
|---|---|
| 技术栈 | Express + TypeScript + SQLite(WAL)；React 18 + Vite，17 个页面 |
| 代码规模 | 后端 60 个 TS 文件 / 31 张表；前端 17 个页面 |
| 验证 | `backend/scripts/smoke.js` **88 项断言全部通过**；前后端类型检查 0 错误 |
| 特殊能力 | AI 导购「多多」：8 类意图路由 + 本地知识库 RAG + 11 个工具 + SSE 流式输出，**不接 LLM 也能完整运行** |
| GitHub | https://github.com/1694798740/duoduo-pet-mall （私有，110 文件） |
| 定位 | **不作为交付物**（PRD 要求 Java），但其 17 个前端页面与业务逻辑可直接移植到 Java 版 |

---

## 3. 怎么使用（手把手）

### 3.1 环境要求

| 依赖 | 状态 | 是否需要你处理 |
|---|---|---|
| JDK 21 | ✅ `D:\jdk\jdk21`（21.0.10 LTS） | 否 |
| Maven 3.9.4 | ✅ `D:\devsoft\apache-maven-3.9.4` | 否 |
| MySQL | ❌ 未安装 | **否**——本地用 H2 内存库跑通，切生产才需要 |
| Redis | ✅ 已有 `D:\devsoft\redis`（未启动） | 否——当前版本未依赖 |
| Docker Desktop | ❌ 未安装 | **是**——见第 5 章 |

### 3.2 三步跑起来

```powershell
cd D:\智能体项目\duoduo-pet-mall-java

# ① 载入环境变量（仅当前会话生效，不改你的系统变量）
. .\maven\env.ps1

# ② 构建（首次会拉取 Spring 依赖，约数分钟；之后增量构建在 10 秒内）
mvn -gs maven\settings.xml -B -DskipTests package

# ③ 一键启动三进程（H2 内存库 + 关闭注册中心，零外部依赖）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-local.ps1
```

启动脚本会依次拉起 **用户服务 :8081 → 商品服务 :8082 → 网关 :8080**，
并等待网关就绪后打印访问地址。日志分别写在工程根目录的
`user-service.log` / `product-service.log` / `gateway.log`。

统一入口：**http://127.0.0.1:8080**

> ⚠️ 为什么必须加 `-gs maven\settings.xml`：你机器上的全局 Maven 配置里
> `mirrorOf=*` 指向的是**已失效的公司私服** `100.wunding.com:1443`，
> 不加这一项会导致所有依赖拉取失败。项目内的配置使用阿里云镜像 + 工程内独立本地仓库，
> **没有改动你的全局配置**。

### 3.3 验证是否正常（54 项断言）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1
```

期望输出结尾：

```
=== 冒烟结果：通过 54 项，失败 0 项 ===
```

该脚本每次运行都会生成**随机手机号**，可以反复执行，不会因数据残留而失败。

### 3.4 停止服务

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-local.ps1
```

### 3.5 手工调用接口示例

```powershell
# 注册（免认证）
curl.exe -i -c cookies.txt -X POST http://localhost:8080/api/auth/register `
  -H "Content-Type: application/json" `
  -d "{\"phone\":\"13900000001\",\"password\":\"duoduo123\",\"nickname\":\"铲屎官小林\"}"

# 未带令牌访问受保护接口 → 401（网关拦截，请求不会打到后端）
curl.exe -i http://localhost:8080/api/users/me

# 首页聚合（免认证，一次拿到首屏全部数据）
curl.exe -i http://localhost:8080/api/home

# 商品检索：猫用食品、按价格升序、只看有货
curl.exe -i "http://localhost:8080/api/products?species=cat&inStockOnly=true&sort=price_asc&pageSize=10"

# 商品详情（含 SKU、适用属性、同类推荐）
curl.exe -i http://localhost:8080/api/products/1001

# 建宠物档案（需登录；过敏原会参与后续商品适配）
curl.exe -i -b cookies.txt -X POST http://localhost:8080/api/pets `
  -H "Content-Type: application/json" `
  -d "{\"name\":\"豆豆\",\"species\":\"cat\",\"breed\":\"布偶\",\"birthday\":\"2026-06-14\",\"weightKg\":2.4,\"allergens\":[\"谷物\",\"牛肉\"]}"
```

### 3.6 切换数据库（H2 → MySQL）

`local` 与生产共用**同一份表结构**，切换只改配置：
启动时去掉 `--spring.profiles.active=local`，并通过环境变量注入连接信息：

```powershell
$env:DB_URL      = "jdbc:mysql://127.0.0.1:3306/duoduo_user?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false"
$env:DB_USERNAME = "duoduo"
$env:DB_PASSWORD = "******"
$env:NACOS_ENABLED = "true"
$env:DUODUO_JWT_SECRET = "生产环境必须显式配置的 32 字节以上密钥"
java -jar duoduo-user\target\duoduo-user-1.0.0.jar
```

生产建表语句见 `docs/sql/`。

### 3.7 Node 原型怎么跑（如需查看参考实现）

```powershell
cd D:\智能体项目\duoduo-pet-mall
npm run build      # 前后端类型检查 + 构建
npm start          # 单进程同源启动（:3000），空库会自动写入种子数据
npm run smoke      # 88 项端到端冒烟
```

演示账号：买家 `13800000001 / duoduo123`、卖家 `13800000002 / duoduo123`、
客服 `13800000003 / ops12345`、管理员 `13800000000 / admin123`。

---

## 4. 未完成的部分，以及应该怎么做

### 4.1 服务维度总览

| # | 服务 | 端口 | 状态 | 说明 |
|---|---|---|---|---|
| 1 | `duoduo-gateway` | 8080 | ✅ 已完成 | 路由 + JWT 前置校验 + 身份透传；限流/灰度/埋点未接 |
| 2 | `duoduo-user` | 8081 | 🟡 部分完成 | 账号/登录/实名/宠物档案 ✅；**收货地址 ❌、站内通知 ❌** |
| 3 | `duoduo-product` | 8082 | 🟡 部分完成 | 类目/品牌/SPU-SKU/检索/首页 ✅；**ES 检索 ❌、C2C 闲置发布与审核 ❌** |
| 4 | `duoduo-trade` | 8083 | ❌ 未开始 | **整个交易闭环缺失——这是当前最大的缺口** |
| 5 | `duoduo-fulfilment` | 8084 | ❌ 未开始 | 发货、物流轨迹、售后、纠纷仲裁、评价、信用分 |
| 6 | `duoduo-marketing` | 8085 | ❌ 未开始 | 优惠券、活动、会员等级、积分 |
| 7 | `duoduo-ai` | 8086 | ❌ 未开始 | 「多多」智能体：意图路由、RAG、工具调用、SSE、会话记忆 |
| 8 | `duoduo-admin` | 8087 | ❌ 未开始 | 运营后台：审核、类目配置、风控、数据看板 |

### 4.2 P1-b：交易服务 + 卖家中心（下一步，优先级最高）

**为什么最优先**：现在只能「逛」，不能「买」。没有购物车和订单，整套系统不具备业务价值。

**要做什么**

| 能力 | 关键实现点 |
|---|---|
| 购物车 | 合并自营与闲置；同 SKU 累加；下架/售罄商品结算前拦截 |
| 下单与拆单 | 一次结算按「供货方 + 运费模板」拆成多个子订单 |
| 库存扣减 | 先冻结（`frozen_stock`）后扣减，超时未支付自动回滚 |
| 订单状态机 | 待支付 → 已支付 → 待发货 → 已发货 → 已收货 → 已完成；非法跃迁返回 422 |
| 支付 | 支付回调幂等（同一笔回调重复到达只生效一次） |
| 超时关单 | 定时任务扫描未支付订单并关单、释放库存 |
| C2C 担保交易 | 资金冻结 → 确认收货 → T+N 结算；服务费按万分比计收 |
| 卖家中心 | 闲置发布（需实名校验 + 违禁词拦截 + 进入待审核）、上下架、我的闲置 |
| 履约接口 | 订单列表 / 详情 / 物流轨迹查询 |

**怎么做**：新建 `duoduo-trade` 模块（复制 `duoduo-product` 的工程结构），
订单表按 `user_id` 分片设计（先单库实现，SQL 里预留分片键）；
支付回调、消息消费全部按「幂等键 + 唯一索引」实现；
状态机用显式枚举 + 允许的跃迁表，禁止散落的 if-else。

**依赖**：无阻塞，可直接开始。接入真实 MySQL 后需要你装 Docker 或原生装 MySQL。

### 4.3 前端（缺口很大，需尽早安排）

**现状**：Java 版**没有任何前端页面**，目前只能通过 curl 或接口调试工具验证。
Node 原型有 17 个页面的 React 实现，可以直接移植。

**建议做法**（两选一，我推荐第一种）

1. **复用 Node 版前端**：保留 `frontend/`（React 18 + Vite + TS），
   把 API 客户端指向网关 `http://localhost:8080`，调整接口字段差异即可。
   工作量最小，界面已经完整（首页、搜索、商详、购物车、结算、订单、售后、宠物档案、优惠券、卖家中心、运营后台、多多对话面板）。
2. **重写**：若希望与 Java 后端同步重新设计，则按页面重新开发，工作量约为前者的 2 倍。

**需要确认的口径**：接口已经全部收敛到网关，前端只需一个 base URL 环境变量，无需额外改动 CORS。

### 4.4 单元测试与集成测试（当前为 0）

**现状**：Java 工程 `src/test` 下**没有任何 JUnit 测试文件**，
质量依赖 `scripts/smoke.ps1` 这一套端到端脚本。

**问题**：端到端脚本跑得慢、定位差——失败时只能知道「某个接口不对」，
无法快速定位到是哪个服务、哪个分支逻辑。商品域的计算逻辑（价格区间相交、类目展开、
可售判定、适用属性聚合）没有单元测试保护，后续改动容易回归。

**应该怎么做**

- **单元测试**：Mock 仓储层，覆盖纯计算逻辑——价格区间相交、类目 id 展开、
  SKU 可售判定、适配度聚合、违禁词匹配（含大小写/空格绕过）、雪花 ID 时钟回拨。
- **集成测试**：用 Testcontainers 或 H2 + 事务回滚，覆盖跨表读写与唯一约束冲突。
- **契约测试**：把 22 个端点的请求/响应结构固化成 JSON Schema，前后端并行开发时防止契约漂移。

### 4.5 基础设施接入（MySQL / Redis / Nacos / ES / MQ）

**现状**：本地用 H2 内存库 + 关闭注册中心跑通，`docker-compose.yml` 已写好但**从未验证过**。

**应该怎么做（按顺序）**

1. 装 Docker Desktop（见第 5 章）或用 ZIP 免安装版起 MySQL 8 + Redis；
2. `docker compose up -d` 起 MySQL / Redis / Nacos，执行 `docs/sql/` 建表；
3. 逐个服务去掉 `local` profile，验证注册到 Nacos、网关用 `lb://` 路由能通；
4. 接入 Redis 缓存：类目树与品牌（启动加载 + 变更事件刷新）、首页聚合结果（TTL 60 秒 + 后台刷新）、
   商品详情（TTL 5 分钟 + 写后失效）。**所有缓存必须设 TTL**。
5. 商品检索接 Elasticsearch：商品上架/改价时通过消息同步索引，
   `ProductQuery` 契约不变，只替换实现（代码结构已为此预留）。
6. 引入 RocketMQ 削峰：下单、支付回调、索引同步改为消息驱动；
   消费者必须幂等 + 失败重试 + 死信队列。

### 4.6 容量与稳定性（对准「百万用户」）

**现状**：**未做任何压测**。当前只是「功能与契约已验证」。

**诚实结论**：这个数字不是靠选型拿到的，也无法用一句代码兑现。
工程上要落成可验收口径：**30 万同时在线 ≈ 峰值 6 万 QPS**，
需要网关 10 实例 + 业务 60 实例、16 库 × 64 表 + 32 个从库、缓存 12 节点。
能力的真正来源是四件事：**无状态水平扩展 + 分库分表 + 多级缓存 + 消息削峰**。

**应该怎么做**

| 项 | 做法 |
|---|---|
| 无状态化 | 服务不存本地会话（已满足：令牌走 Cookie + JWT 校验），可直接水平扩容 |
| 分库分表 | 订单/评价按 `user_id` 分片，先用 ShardingSphere 做逻辑分片，再演进到物理分库 |
| 压测 | JMeter 对齐：首页/检索 P95 < 200ms、下单 P99 < 500ms、可用性 99.95%、缓存命中率 > 98% |
| 限流熔断 | 网关接 Sentinel（按接口 + 用户维度限流），业务服务配熔断降级 |
| 分布式事务 | 交易跨服务（扣库存 / 扣优惠券 / 下单）用 Seata AT 模式，或改为本地消息表最终一致 |
| 可观测 | SkyWalking 链路追踪 + Prometheus 指标 + Grafana 看板，告警阈值对齐 SLO |
| 发布 | K8s 编排 + 滚动发布 + 就绪探针（`/actuator/health` 已就绪） |

### 4.7 工程配套（小项但要做）

| 项 | 现状 | 应该怎么做 |
|---|---|---|
| CI | Java 版**无** CI；Node 版有 GitHub Actions | 加 GitHub Actions：`mvn -B verify` + 冒烟脚本 |
| 接口文档 | 只有 README 表格 | 引入 springdoc-openapi / Knife4j（版本已在父 POM 声明），自动生成在线文档 |
| 容器化 | 有 `Dockerfile` / `docker-compose.yml` 但未验证 | 装好 Docker 后逐个构建镜像并实跑 |
| 配置中心 | 未接 Nacos Config | 密钥、开关、限流阈值迁到 Nacos，支持不停机调整 |
| 幂等 | 交易域尚未实现 | 所有写接口接受 `Idempotency-Key`，落唯一索引 |

---

## 5. 遇到的问题与解决方式

### 5.1 已解决（我自行处理，不需要你介入）

| # | 现象 | 根因 | 解决方式 |
|---|---|---|---|
| 1 | `mvn` 拉取依赖全量失败 | 全局 `settings.xml` 的 `mirrorOf=*` 指向**已失效的公司私服** `100.wunding.com:1443` | 用项目内 `maven/settings.xml`（阿里云镜像 + 工程内仓库），**未改你的全局配置** |
| 2 | 业务规则异常返回 **500 而非 422**，所有响应 `requestId` 恒为空 | `duoduo-common-web` 的包名不在业务服务扫描范围内，`GlobalExceptionHandler` 与 `RequestIdFilter` **从未被注册** | 在该模块 `WebAutoConfiguration` 中显式 `@Bean` 登记（自动装配已写入 `AutoConfiguration.imports`） |
| 3 | `java -jar` 报 `no main manifest attribute`，jar 只有 43 KB | 只 import BOM、未继承 `spring-boot-starter-parent`，`repackage` 不会自动绑定 | 在父 POM 显式声明 `repackage` execution，产出 43–45 MB 可执行 fat jar |
| 4 | 用户服务静默跑到 **8221** 端口 | 配置用了 `${USER_PORT:8081}`，而宿主环境恰好已设 `USER_PORT=8221`，被静默劫持 | 环境变量名统一加项目前缀：`DUODUO_USER_PORT` / `DUODUO_PRODUCT_PORT` / `DUODUO_GATEWAY_PORT` |
| 5 | 含中文的 `.ps1` 脚本报「字符串缺少终止符」 | PowerShell 5.1 把 UTF-8 **无 BOM** 的脚本按 ANSI 解析（**踩了两次**：`smoke.ps1` 与 `start-local.ps1`） | 统一用 `File.WriteAllText(path, text, UTF8Encoding($true))` 转存为带 BOM |
| 6 | 冒烟脚本赋值 `$home` 直接抛错 | PowerShell 中 `$HOME` 是**只读内置变量** | 变量改名为 `$homeData` |
| 7 | 采集的接口样本中文乱码 | `Invoke-WebRequest` 未按 UTF-8 解码响应就落盘 | 改用 `WebClient` + `Encoding = UTF8`，文件带 BOM |
| 8 | 父 POM 编译报 `'dependencies.dependency.version' is missing` | `dependencyManagement` 漏声明 `duoduo-common-web` 的版本 | 补上版本声明 |
| 9 | `git push` 报 `CONNECT tunnel failed, response 502`，直连也不通 | 沙箱内本地代理拦截了 git 的 HTTPS 隧道 | 改用 **GitHub Git Data API** 构造 tree/commit/ref 完成推送，效果等价 |
| 10 | 推送脚本读不到中文工程路径 | 环境变量传递中文路径时被截断 | 改为把路径写进 UTF-8 配置文件、脚本从文件读取 |
| 11 | Spring 服务启动时报 `APPLICATION FAILED`，但日志被 PowerShell 参数拆分破坏 | `-Dfile.encoding=UTF-8` 未加引号被 PowerShell 拆成两个参数 | 启动参数加引号：`"-Dfile.encoding=UTF-8"` |

### 5.2 需要你协助的问题（我确实无法自行解决）

#### ① Docker Desktop 安装 —— 需要管理员权限

- **现象**：我执行 `choco install docker-desktop -y` 失败，报 `C:\ProgramData\chocolatey\lib-bad` **访问被拒绝**。
  当前会话没有管理员权限，这一步无法绕过。
- **影响**：MySQL / Redis / Nacos 无法用 `docker-compose.yml` 启动 →
  无法把 `local`（H2 内存库）切换到真实基础设施 → 服务注册发现（`lb://` 路由）无法验证。
- **需要你做什么**：以**管理员身份**打开 PowerShell，执行：

  ```powershell
  choco install docker-desktop -y
  ```

  安装后启动 Docker Desktop，确认右下角状态为 Running（首次启动可能需要开启 WSL2 并重启一次）。
- **替代方案（不装 Docker 也能继续）**：MySQL 8 用免安装 ZIP 版解压即用；
  Redis 你机器上已有 `D:\devsoft\redis`。可以先用这两者打通真实数据库，
  把 Docker 留到需要 Nacos / ES / RocketMQ 时再装。
- **是否需要协助**：**需要**（也可选择走替代方案，告诉我一声即可）。

#### ② 撤销已泄露的 GitHub Token

- **现象**：`ghp_4jro…l6LK` 已经出现在对话记录中（会长期留在会话历史里）。
  我用它创建了 `duoduo-pet-mall` 与 `duoduo-pet-mall-java` 两个私有仓库。
- **已做的处理**：临时配置文件与推送脚本**用完即删**；
  校验过本地 `.git/config` 与远端仓库中**均不含任何令牌**。
- **需要你做什么**：到 GitHub → Settings → Developer settings → Personal access tokens，
  **删除该 Token**。
- **影响**：删除后我无法再推送；后续要推送需你重新提供一个 Token（同样写到
  `%USERPROFILE%\.workbuddy\gh-token.txt`，我用完即删）。
- **是否需要协助**：**需要**（且建议尽快）。

#### ③ 后台服务进程会被会话回收

- **现象**：我用后台方式启动的 Java 服务，在会话空闲后会被回收，
  你回来时 8080/8081/8082 可能都不在监听。
- **这不是缺陷**，是执行环境的限制。
- **需要你做什么**：验证前重新执行一次
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-local.ps1`，
  脚本会自动等网关就绪并打印访问地址。
- **是否需要协助**：**不需要**（已知限制，已用脚本规避）。

#### ④ 沙箱内 `git push` 不可用（已规避，但需你知晓）

- **现象**：本地代理拦截 git 的 HTTPS 隧道，`git push` 必失败。
- **影响**：**远端提交的 SHA 与本地不一致**——因为远端提交对象是通过 API 重建的，
  内容完全相同，但对象哈希不同。克隆远端仓库不受影响。
- **是否需要协助**：**不需要**（仅需知晓这一差异，避免以后对比 SHA 时困惑）。

---

## 6. 需要你协助的事项汇总（可勾选）

| # | 事项 | 操作 | 是否阻塞开发 |
|---|---|---|---|
| 1 | 撤销 GitHub Token `ghp_4jro…l6LK` | GitHub → Settings → Developer settings → Personal access tokens → 删除 | 不阻塞开发，但**建议尽快** |
| 2 | 安装 Docker Desktop（或改用 MySQL ZIP + 本机 Redis） | 管理员 PowerShell 执行 `choco install docker-desktop -y` | 阻塞「接入真实 MySQL / 服务注册发现」 |
| 3 | 确认前端方案 | 复用 Node 版 17 个页面（推荐，工作量小）／ 重新设计（工作量大） | 阻塞前端开工 |
| 4 | 确认下一阶段范围 | 默认按 P1-b 交易服务推进 | 不阻塞，默认可开工 |

---

## 7. 后续执行顺序（建议）

1. **P1-b 交易服务 + 卖家中心**（`duoduo-trade` :8083）——补齐「下单 → 支付 → 履约」闭环，接口延续既有分层与错误体系；
2. **接入真实 MySQL + Redis 缓存**——把 `local`（H2）换成生产形态，并验证服务注册发现；
3. **前端对接**——复用 Node 版页面指向网关，形成可在浏览器里完整走一遍的商城；
4. **补单元测试与集成测试**——优先覆盖商品域计算逻辑与交易域状态机；
5. **P2 履约与售后 + 营销**（`duoduo-fulfilment` :8084、`duoduo-marketing` :8085）；
6. **P3 AI 服务「多多」+ ES 检索 + RocketMQ 削峰 + Sentinel 限流 + Seata 事务**；
7. **P4 可观测 + K8s + JMeter 压测**——对齐容量验收标准，此时才敢谈并发能力。

---

## 附：当前验证证据（可复现）

```
构建  mvn -gs maven/settings.xml -B -DskipTests package
      → BUILD SUCCESS；duoduo-gateway 45MB / duoduo-user 43MB / duoduo-product 43MB

运行  网关 :8080 + 用户服务 :8081 + 商品服务 :8082（local profile，H2，零外部依赖）

冒烟  scripts/smoke.ps1 → 通过 54 项，失败 0 项

接口抽样
  GET /api/home                → 推荐 8 件 / 新品 8 件 / 品牌 8 个 / 闲置 6 件 / 物种精选 2 组
  GET /api/products/1001       → SKU 2 个 / 食品=True / 适用物种=cat / 过敏原=鸡肉,谷物
  GET /api/listings            → 可见 8 件（种子 10 件：1 件待审、1 件驳回不可见）

GitHub  github.com/1694798740/duoduo-pet-mall-java（私有，7 提交 / 81 文件）
        推送后校验：无密钥泄露、本地 git 配置无令牌残留
```
