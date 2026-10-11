# 多多宠物商店 · Code Wiki

> 本文是面向开发者的**代码级百科**：整体架构、模块职责、关键类与函数、依赖关系、数据模型、运行与测试方式。
> 生成时间：2026-10-10。内容依据仓库实际源码整理；业务规则的权威解释在各源码类注释与 `docs/agent/03-架构与不变式.md`。
> 配套文档：[PLAN.md](./PLAN.md)（进度权威）、[ARCHITECTURE.md](./ARCHITECTURE.md)（长期蓝图）、`docs/agent/`（AI 接管包 00~08）、`docs/handover/`（人读交接包）。

---

## 目录

1. [项目概览](#1-项目概览)
2. [整体架构](#2-整体架构)
3. [仓库结构](#3-仓库结构)
4. [模块依赖关系](#4-模块依赖关系)
5. [公共基础模块](#5-公共基础模块)
6. [业务服务详解](#6-业务服务详解)
7. [前端（React Monorepo）](#7-前端react-monorepo)
8. [微信小程序](#8-微信小程序)
9. [数据库设计](#9-数据库设计)
10. [横切机制](#10-横切机制)
11. [服务间调用矩阵](#11-服务间调用矩阵)
12. [关键业务流程](#12-关键业务流程)
13. [定时任务清单](#13-定时任务清单)
14. [配置与环境变量](#14-配置与环境变量)
15. [项目运行方式](#15-项目运行方式)
16. [测试体系](#16-测试体系)
17. [CI/CD](#17-cicd)
18. [开发约定与不变式精要](#18-开发约定与不变式精要)
19. [关键文件速查（按意图）](#19-关键文件速查按意图)

---

## 1. 项目概览

**多多宠物商店**是「宠物用品电商（B2C）+ 宠物闲置二手交易（C2C）」的双边平台，Java 微服务实现。

| 维度 | 内容 |
|---|---|
| 业务能力 | 账号/实名/卖家入驻、宠物档案、收货地址、类目品牌、SPU/SKU、库存、闲置、购物车、下单拆单、担保交易（资金托管/放款/退款/佣金）、沙箱支付、评价晒单、售后仲裁、物流轨迹、信用分、优惠券、限时秒杀、AI 导购、分享圈（社区）、关注/私信/通知、商家入驻审核与店员、操作审计、审核 SLA 抽查 |
| 参与者 | 游客、买家 `USER`、卖家 `SELLER`、运营 `OPERATOR`、管理员 `ADMIN`；另有商家店员（角色仍为 USER，权限由 `t_merchant_member` 成员身份驱动） |
| 技术栈 | Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0（Nacos）· MyBatis-Plus 3.5.5 · OpenFeign · MySQL 8 · Redis 7 · Spring Cloud Gateway（WebFlux）· JWT(jjwt 0.12.5) · Knife4j/OpenAPI 3 · RocketMQ 5.3（可选）· Elasticsearch 8.11（可选）· pinyin4j |
| 前端 | React 18 + TypeScript strict + Vite 5，npm workspaces monorepo：3 个独立应用（商城/平台后台/商家平台）+ 2 个共用包 |
| 小程序 | 原生微信小程序（29 个页面，待 AppID 真机联调） |
| 构建 | Maven 多模块（10 个模块）、Docker Compose 多进程、GitHub Actions CI |
| 测试 | JUnit 单测 210+、PowerShell 接口冒烟 20+ 套 1400+ 断言、真实浏览器 e2e 25 套、前端 Node 单测 251 |
| 核心约束 | 金额整数分、时间 UTC、雪花 ID、幂等靠唯一索引、不跨库查询、内部接口 HMAC 鉴权、64 位 ID 出参序列化为字符串 |

---

## 2. 整体架构

### 2.1 部署拓扑

```
                            ┌─────────────── 浏览器三站点（Nginx 同源反代）──────────────┐
                            │ 商城 localhost:8088   后台 admin.localhost:8090             │
                            │ 商家 merchant.localhost:8091                               │
                            └───────────────────────────┬───────────────────────────────┘
 微信小程序 ────────────────────────────────────────────┤
                                                        ▼
                              duoduo-gateway :8080（唯一对外入口）
                    路由 lb:// · JWT 校验 · 白名单 · 入口收窄 · 身份头注入 · CORS
                                                        │ Nacos 服务发现
  ┌──────────┬──────────┬──────────┬──────────┬─────────┴────────┬──────────┬──────────┐
  │ user     │ product  │ trade    │fulfilment│ marketing         │ ai       │ social   │
  │ :8081    │ :8082    │ :8083    │ :8084    │ :8085             │ :8086    │ :8087    │
  │          │          │  (最复杂) │          │                   │ (无数据库)│          │
  └────┬─────┴────┬─────┴────┬─────┴────┬─────┴────┬──────────────┴──────────┴────┬─────┘
       │          │          │          │          │                               │
  duoduo_user  duoduo_product duoduo_trade duoduo_fulfilment duoduo_marketing   duoduo_social
       └──────────┴────────── MySQL 8 :3306（6 库，各服务只连自己的库）┴──────────┘
                             Redis 7 :6379（限流/秒杀预扣/权限版本）
  可选：Elasticsearch :9200（profile=search，仅 product，故障降级 MySQL）
  可选：RocketMQ NameServer :9876 / Broker :10911（profile=mq，trade 削峰）
  基础设施：Nacos :8848/:9848（standalone + 内嵌 Derby）
```

### 2.2 一个 HTTP 请求的生命周期

1. 浏览器/小程序 → 网关 `:8080`（正式站点由各自 Nginx 同源反代 `/api`、`/sandbox`）。
2. `JwtAuthGlobalFilter`（WebFlux 全局过滤器）：剥离客户端伪造的 `X-User-*`/`X-Forwarded-*` 头 → 白名单判定（Ant 路径匹配，不区分方法）→ 解析 JWT → 入口收窄（`ent` 声明：admin 令牌才能调 `/api/admin/**`，merchant 令牌才能调 `/api/seller/**`）→ 权限版本 `pv` 与 Redis 副本比对（读不到放行）→ 写入可信身份头 `X-User-Id/X-User-Phone/X-User-Role/X-Real-Name-Verified/X-Duoduo-Entry`。
3. 网关按 `application.yml` 显式路由（`lb://duoduo-xxx`，经 Nacos 发现 + LoadBalancer）转发。
4. 业务服务：`RequestIdFilter`（链路 ID + MDC）→ `UserContextInterceptor`（身份头还原进 ThreadLocal）→ `PermissionInterceptor`（`@RequirePerm` RBAC 判权）→ `AuditInterceptor`（`@Audited` 留痕）→ Controller（≤5 行薄壳）→ Service（业务规则）→ Mapper（MyBatis-Plus）。
5. 响应经 `GlobalExceptionHandler` 统一包装为 `ApiResponse`，`Long` 型 ID 由 `LongToStringJacksonModule` 全局序列化为字符串。

### 2.3 关键架构决策

| 决策 | 落地方式 |
|---|---|
| 每服务独占数据库，禁止跨库 JOIN | 跨域数据只走对端**内部接口**（Feign + HMAC 凭据），且返回裁剪过字段的 DTO |
| 不用分布式事务 | 「本地事务 + 唯一索引幂等 + 原子条件更新（CAS）+ 补偿调度」组合（下单/库存/支付/券/售后均如此） |
| 幂等靠数据库唯一索引 | 不靠应用层「先查再插」，不引入分布式锁 |
| AI 服务无状态、不连库 | 商品事实只能来自 product 候选召回；AI 挂掉不影响交易主链路（叶子服务） |
| ES 只做加速层 | `t_sku/t_spu` 永远权威；ES 缺席原地降级 MySQL |
| MQ 可选 | trade 默认 `order-mode=sync`；切 `queue` 才装配 RocketMQ Bean，消费者复用同步下单代码 |
| 三前端独立部署 | 独立应用/独立镜像/独立主机名（Cookie 主机级、不区分端口），同一账号中心 |
| 定时任务可多实例 | 数据库选主锁 `t_sched_leader`（`SchedLeaderLock`），零新增中间件 |

---

## 3. 仓库结构

```
duoduo-pet-mall-java/
├── pom.xml                     # 聚合父 POM（10 模块，依赖版本管理 BOM）
├── Dockerfile                  # 业务服务共用镜像（temurin:21-jre-alpine，非 root，JAR_FILE 构建参数）
├── docker-compose.yml          # 3 中间件 + 8 后端容器 + 3 前端容器；ES/MQ 走 profile 可选
├── .env / .env.example         # 环境变量样例
├── maven/settings.xml          # 仓库自带 Maven 配置
├── docs/
│   ├── PLAN.md                 # 进度权威（里程碑/变更记录/命令速查）
│   ├── ARCHITECTURE.md         # 12 服务长期蓝图与分期
│   ├── CODE-WIKI.md            # ← 本文
│   ├── sql/                    # 容器初始化 DDL：00 建库授权 + 01~06 六库表结构；incremental/ 增量脚本
│   ├── agent/                  # AI 接管包 00~08
│   ├── handover/               # 人读交接包 9 篇
│   └── mq/broker.conf          # RocketMQ broker 配置
├── duoduo-common/              # 基础库：响应/错误码/异常/雪花ID/JWT/UserContext/RBAC/审计注解
├── duoduo-common-web/          # Servlet 基础设施：异常处理/拦截器/Jackson/HMAC/选主锁/审计上报
├── duoduo-gateway/             # WebFlux 网关：8080
├── duoduo-user/                # 用户域：8081
├── duoduo-product/             # 商品域：8082
├── duoduo-trade/               # 交易域：8083（最复杂）
├── duoduo-fulfilment/          # 履约域：8084
├── duoduo-marketing/           # 营销域：8085
├── duoduo-ai/                  # AI 导购：8086（无数据库）
├── duoduo-social/              # 社交域：8087
├── frontend/                   # React monorepo：apps/{mall,admin,merchant} + packages/{api-sdk,shared}
├── miniprogram/                # 原生微信小程序
├── scripts/                    # 接口冒烟 *.ps1/*.py、e2e/ 浏览器用例、运维脚本
└── .github/workflows/ci.yml    # CI：编译 + 前端构建 + 冒烟子集
```

后端统一分层（每个业务服务内部）：

```
com.duoduo.<svc>
├── XxxApplication.java        # @SpringBootApplication + @EnableFeignClients + @MapperScan
├── controller/                # 薄壳：参数校验、鉴权注解、委托 Service
├── service/                   # 业务规则（含 *Job 定时任务）
├── mapper/                    # MyBatis-Plus Mapper（CAS SQL 写在注解/XML）
├── entity/                    # *Entity（@TableName）
├── dto/                       # 请求/响应记录（Java record 为主）
├── client/                    # Feign 客户端 + Gateway 降级封装 + 跨域 DTO 副本
├── support/                   # 状态机、守卫、过滤器、生成器等支撑件
└── config/                    # InternalCallConfig、ServiceConfig（分页/雪花 worker）
```

---

## 4. 模块依赖关系

父 POM（`com.duoduo:duoduo-pet-mall:1.0.0`，packaging=pom）通过三个 BOM 锁版本：
`spring-boot-dependencies 3.2.5`、`spring-cloud-dependencies 2023.0.1`、`spring-cloud-alibaba-dependencies 2023.0.1.0`。
工程**未继承** spring-boot-starter-parent，因此 `spring-boot-maven-plugin` 的 `repackage` 目标必须显式声明（否则产出瘦 jar）。

模块依赖图：

```
duoduo-common （纯基础库：jackson/validation/web/jwt，无 Spring Boot 启动）
    ▲
    ├── duoduo-common-web ── (spring-web/validation、jdbc optional、knife4j；自动装配)
    ▲          ▲
    │          └──────────────────────────────────────┐
    ├── duoduo-gateway（WebFlux：gateway/nacos-discovery/loadbalancer/redis/actuator）
    ├── duoduo-user        ┐
    ├── duoduo-product     │
    ├── duoduo-trade       ├─ 每个业务服务：common-web + mybatis-plus + mysql +
    ├── duoduo-fulfilment  │   openfeign + loadbalancer + nacos-discovery + redis（部分）
    ├── duoduo-marketing   │
    └── duoduo-social      ┘
    duoduo-ai：common-web + openfeign + loadbalancer（JDBC 依赖 optional → 无数据源也能启动）
```

| 模块 | 端口 | 数据库 | Feign 出边 |
|---|---|---|---|
| duoduo-gateway | 8080 | — | 不调业务服务（仅 lb 路由）；读 Redis 权限版本 |
| duoduo-user | 8081 | duoduo_user | social（通知）、product（资质失效下架） |
| duoduo-product | 8082 | duoduo_product | user（商家成员/资质/风险、审计日志） |
| duoduo-trade | 8083 | duoduo_trade | product、marketing、user（地址）、fulfilment（物流）、social（通知）、审计 |
| duoduo-fulfilment | 8084 | duoduo_fulfilment | trade、user、product（评分回写）、social（通知）、审计 |
| duoduo-marketing | 8085 | duoduo_marketing | 无出边（被调用方；仅审计） |
| duoduo-ai | 8086 | 无 | product（候选召回）、user（宠物档案） |
| duoduo-social | 8087 | duoduo_social | product（商品/闲置卡片）、user（用户摘要） |

雪花 `worker-id` 分配：user=1、product=2、trade=3、fulfilment=4、marketing=5、social=6（datacenter-id 默认 1）。

---

## 5. 公共基础模块

### 5.1 duoduo-common

包根 `com.duoduo.common`。

| 类 | 职责与关键 API |
|---|---|
| `api.ApiResponse<T>` | 统一响应体 `{code, message, data, requestId, timestamp}`；静态工厂 `ok()`/`ok(T data)`/`fail(String code, String msg)` |
| `api.PageResult<T>` | 分页体 `{items,total,page,pageSize,pageCount}`，page 从 1 起；`of(...)`、`empty(page,size)` |
| `api.RequestIdHolder` | 请求 ID 的 ThreadLocal 持有器 |
| `enums.ErrorCode` | 全部错误码 ↔ HTTP 状态码的**唯一映射**（见下表） |
| `exception.BizException extends RuntimeException` | 绑定 ErrorCode + 自定义消息；工厂：`validation()`→400、`businessRule()`→422、`notFound()`→404、`unauthorized()`→401、`forbidden()`→403、`conflict()`→409、`rateLimited()`→429、`degraded()`→503 |
| `id.SnowflakeIdGenerator` | 64 位雪花：1 符号 + 41 ms 时间戳 + 5 datacenter + 5 worker + 12 序列；构造校验 worker/datacenter 范围，处理时钟回拨，提供 ID 反解时间戳 |
| `context.UserContext` | `ThreadLocal<LoginUser>`；`get()`、`require()`、`requireUserId()`、`requireRealNameVerified()`（未实名 403） |
| `context.LoginUser` | 身份快照（userId/phone/role/realNameVerified/entry）；`isSeller()`、`isOperator()`（大小写不敏感）、`hasPerm(...)` |
| `context.OperatorGuard` | `requireOperator()`（非 OPERATOR/ADMIN → 403）、`requirePerm(String)`（RBAC 权限点） |
| `security.JwtTokenService` | access/refresh 双令牌签发与解析；claims：`type/phone/role/rn(实名)/ent(入口)/pv(权限版本)`，refresh 带 `jti`；access TTL 15m、refresh TTL 7d |
| `security.ClientEntry` | 入口标识 `mall/admin/merchant`，含边界说明（入口收窄不是强隔离） |
| `security.perm.Permission` | 19 个权限点常量（`域:对象:动作`），如 `platform:console`、`user:role:manage`、`order:ops`、`finance:settlement`、`seller:console` |
| `security.perm.RolePermissionMatrix` | 角色→权限点**唯一权威矩阵**：ADMIN=全部；OPERATOR=全部减 `user:role:manage` 与 `finance:settlement`；SELLER=仅 `seller:console`；USER=空。`canonical(role)` 未知角色折叠为 USER（失败方向指向拒绝）；`has(role,perm)` |
| `security.perm.PermVersion` | 权限版本 Redis key 规则（角色变更后使旧令牌即时失效） |
| `security.perm.RequirePerm` | 方法/类注解，声明所需权限点 |
| `seller.SellerOwnership` | 卖家归属校验通用逻辑 |
| `audit.Audited` / `AuditCategory` / `AuditAction` / `AuditOutcome` / `AuditSlaPolicy` / `AuditLogRecord` / `AuditSink` | 操作审计注解与落库抽象（分类、动作、结果、SLA 策略；审计记录不可删改） |

`ErrorCode` 全集：

| 枚举 | HTTP | 语义 |
|---|---|---|
| SUCCESS | 200 | 成功 |
| BAD_REQUEST / VALIDATION_ERROR | 400 | 请求不合法 / 参数不合法 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 非 JSON 请求体 |
| METHOD_NOT_ALLOWED | 405 | 方法用错 |
| UNAUTHORIZED | 401 | 未认证（含支付回调验签失败 `PAYMENT_SIGNATURE_INVALID`） |
| FORBIDDEN | 403 | 无权限 |
| NOT_FOUND | 404 | 资源不存在（归属校验失败也统一 404，防 IDOR） |
| CONFLICT | 409 | 唯一键冲突等 |
| BUSINESS_RULE / STOCK_INSUFFICIENT / ORDER_STATUS_ILLEGAL / PAYMENT_CHANNEL_UNKNOWN / PAYMENT_AMOUNT_MISMATCH | 422 | 业务规则不满足 |
| RATE_LIMITED | 429 | 限流 |
| DEGRADED | 503 | 依赖不可用已降级 |
| INTERNAL | 500 | 服务内部错误 |

### 5.2 duoduo-common-web

Servlet 侧基础设施，以 Spring Boot 自动装配（`WebAutoConfiguration`，各服务引 jar 即生效）。

| 类 | 职责 |
|---|---|
| `web.RequestIdFilter` | 读/生成 `X-Request-Id`，写入 MDC、响应头与 `RequestIdHolder` |
| `web.UserContextInterceptor` | 把网关注入的 `X-User-*` 头还原为 `LoginUser` 放入 `UserContext` |
| `web.PermissionInterceptor` | 解析 `@RequirePerm`，经 `RolePermissionMatrix.has()` 判权 |
| `web.AuditInterceptor` | 解析 `@Audited`，请求完成后经 `AuditSink` 异步落审计（失败不阻断主链路） |
| `web.GlobalExceptionHandler` | `BizException`、Bean Validation、消息体/参数错误、405、415、`NoResourceFoundException`(404) → `ApiResponse` + 对应 HTTP 状态 |
| `web.LongToStringJacksonModule` | **全局序列化策略**：`Long/long/BigInteger` 默认输出为 JSON 字符串（雪花 ID 18 位超过 JS `Number.MAX_SAFE_INTEGER`）；字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额分、计数、分页 total、epoch 毫秒时间戳三类语义）的保持数字。基于 `BeanSerializerModifier` + 自定义 `BeanPropertyWriter`；**反序列化不受影响**。白名单用 `Set.copyOf(Arrays.asList(...))` 构造 |
| `web.internal.InternalCallAuth` | 内部调用 HMAC 凭据：头格式 `dd1.<caller>.<tsSeconds>.<nonce>.<HMAC-SHA256签名>`，签名覆盖调用方+时间戳+nonce+METHOD（**不含 path**，Feign 取类级 path 的坑）；默认时间窗 300s，密钥按调用方身份从根密钥 `INTERNAL_TOKEN` 派生（也可 `INTERNAL_CALLER_SECRETS` 显式配置）；**失败关闭**（解析/验签异常一律 403） |
| `web.audit.RemoteAuditSink` | 审计记录经 Feign 转发到 user 域 `/api/users/internal/audit-logs` 统一落库 |
| `sched.SchedLeaderLock` | 定时任务数据库选主：`t_sched_leader`（主键=锁名，`expire_at` 自动过期）；抢占顺序固定为先 `UPDATE` 续约、影响 0 行再 `INSERT`，撞唯一键即放弃；锁事务 `REQUIRES_NEW` 独立提交；开关 `duoduo.sched.leader-enabled` |

### 5.3 duoduo-gateway（:8080）

Spring Cloud Gateway（WebFlux/Netty），启动类 `GatewayApplication`。

| 文件 | 职责 |
|---|---|
| `config/GatewaySecurityProperties.java` | 免登白名单（路径前缀/精确路径）、JWT 密钥与 TTL、入口规则 `entryRules` |
| `filter/JwtAuthGlobalFilter.java` | 全局过滤器（核心见 §2.2）：伪造头剥离 → 白名单 → JWT 校验 → 入口收窄 → 身份头注入 → 权限版本校验 |
| `support/RedisPermVersionChecker.java` | `ReactiveStringRedisTemplate` 读权限版本；任何异常/超时 onErrorResume 成「未知」（放行降级，超时 300ms） |
| `resources/application.yml` | 7 条显式路由、CORS、Actuator |

**白名单（免登，路径匹配不区分 HTTP 方法，故只放只读意图）**：
`/api/auth/{login,login/token,register,sms/**,refresh,refresh/token,wechat,wechat/token}`、
`/api/products/**`、`/api/categories/**`、`/api/home/**`、`/api/meta/**`、
`/api/listings/search`、`/api/listings/*`（仅列表/详情，发布等写接口不在内）、
`/api/coupons/templates`（精确，非 `/api/coupons/**`）、
`/api/reviews/product/**`、`/api/reviews/seller/*/credit`、
`/api/shares/feed`、`/api/shares/topics`、`/api/shares/topics/*`、`/api/shares/*`（信息流只读四条）、
`/api/duoduo/chat`、`/api/duoduo/capabilities`（AI 免登录咨询，不写业务数据）、
`/api/seckill/activities`、`/api/seckill/activities/*`、
`/api/payments/callback/**`、`/api/payments/sandbox/**`、`/sandbox/pay/**`、
`/uploads/**`、`/actuator/**`、`/health/**`。

**入口收窄规则**：`/api/admin/**` 仅 `ent=admin`；`/api/seller/**` 仅 `ent=merchant`；不匹配返 403（不是 401，避免前端误跳登录死循环）。无 `ent` 老令牌/小程序令牌不受限。

**CORS**：默认允许 dev server `http://localhost:{5173,3000,5180,5190}`；生产三站点同源不触发跨域。

---

## 6. 业务服务详解

> 鉴权标记：【公开】白名单免登；【登录】任意登录用户；【卖家】SELLER/运营代运营 + 服务端归属校验；【运营】OPERATOR/ADMIN（部分仅 ADMIN）；【内部】HMAC 内部凭据，不经网关；【店员】商家成员身份。

### 6.1 duoduo-user（:8081，用户域）

账号认证、实名、卖家升级、商家入驻与资质、店员、宠物档案、收货地址、微信登录、操作审计落库。

**控制器与端点**：

| Controller（基路径） | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| `AuthController` `/api/auth` | POST `/register` `/login` `/login/token` `/refresh` `/refresh/token` `/wechat` `/wechat/token` `/wechat/{bind,unbind,rebind}` `/logout` | 登录类公开，绑定类登录 | 密码/Bearer/微信三形态；登录写 httpOnly Cookie（`dd_at`/`dd_rt`，`AuthCookieWriter`） |
| `UserController` `/api/users` | GET `/me`；POST `/me/realname`；GET `/internal/{briefs,roles,merchant-membership,merchant-license,merchant-risk,pet-profiles}`；POST `/internal/merchant-violation`、`/internal/{userId}/credit` | 前两个登录；其余内部 | 实名/个人资料；内部批量昵称头像、角色、商家资质/风险、信用分回写 |
| `PetController` `/api/pets` | GET ``、POST ``、PUT `/{petId}`、POST `/{petId}/default`、DELETE `/{petId}` | 登录 | 宠物档案；首只自动默认、按生日推断生命阶段、删除默认顺延 |
| `AddressController` `/api/addresses` | GET ``、GET `/default`、POST ``、PUT `/{id}`、POST `/{id}/default`、DELETE `/{id}`、GET `/internal/snapshot` | 登录/内部 | 地址簿；默认唯一与删除顺延；`fullAddress()` 唯一拼接处；内部快照供下单（归属失败统一 404） |
| `MerchantApplicationController` `/api/users/me/merchant-application` | GET ``、PUT ``、POST `/submit` `/activate` `/renewal` | 登录 | 商家入驻草稿/提交/激活/续期 |
| `MerchantMaterialUploadController` | POST `/api/users/me/merchant-application/materials` | 登录 | 资质材料上传（私有卷，非公开路径） |
| `MerchantMaterialReadController` | GET `/api/merchant-materials/{merchantId}/{storedName}` | 权限点 | 资质读取，每次留痕 |
| `MerchantStaffController` `/api/seller/staff` | GET ``、POST ``、POST `/{memberId}/{disable,enable}` | 卖家/店员 | 店主自管成员（E13） |
| `AdminUserController` `/api/admin/users` | GET ``、POST `/{id}/role` | 运营（改角色仅 ADMIN） | 账号管理；改角色发布权限版本 + 吊销刷新令牌 |
| `AdminMerchantController` `/api/admin/merchants` | GET ``、GET `/{id}`、POST `/{id}/{start-review,approve,reject}`、POST `/license-sweep`、`/audit-sla/{samples,samples/{id}/review,metrics,sweep}` | 运营 | 入驻审核台 + 资质巡检 + 审核 SLA |
| `AdminMerchantStaffController` `/api/admin/merchant-staff` | GET ``、POST `/{memberId}/{disable,enable}` | 运营 | 跨租户成员处置 |
| `AdminAuditLogController` `/api/admin/audit-logs` | GET `` | 运营/管理员 | 审计日志查询（只可读不可改） |
| `AuditLogInternalController` `/api/users/internal/audit-logs` | POST `` | 内部 | 全站审计统一落库口 |

**关键 Service**：
- `AuthService`：注册（手机号唯一索引兜底）、登录（封禁拦截）、刷新（refresh 轮换、入口从 refresh 令牌继承）、微信 code2Session 登录绑定（openid 查人、抢绑 409）。
- `MerchantOnboardingService` / `MerchantLicenseJob` / `MerchantLicenseNotifier`：入驻状态机（`MerchantStatus`）、资质到期巡检与通知（到期联动 product 下架）。
- `MerchantStaffService`、`MerchantViolationService`、`MerchantRiskRules`、`MerchantAuditSampleService/Job`：成员、违规记点、风险规则、SLA 抽样。
- `AdminUserService`：运营账号与角色变更（角色库读、权限版本广播 `PermVersionPublisher`）。
- `AddressService`、`PetProfileService`：见端点说明。
- `WechatGateway`：三模式——mock（`DUODUO_WECHAT_MOCK_ENABLED=true`，compose 默认开）/ 真实 `jscode2session`（WECHAT_APPID/SECRET 注入）/ 未配置降级 503。
- `AuditLogService` + `LocalAuditSink`：审计落库。

**实体（duoduo_user 库）**：`UserEntity(t_user)`、`RefreshTokenEntity(t_refresh_token)`、`PetProfileEntity(t_pet_profile)`、`UserAddressEntity(t_user_address)`、`MerchantEntity(t_merchant)`、`MerchantMemberEntity(t_merchant_member)`、`MerchantMaterialEntity(t_merchant_material)`、`AuditLogEntity(t_audit_log)`、`AuditSampleEntity/RateEntity`。

### 6.2 duoduo-product（:8082，商品域）

类目品牌、SPU/SKU 与价格库存、ES 加速检索、闲置 C2C、商家商品管理、平台/第三方审核、审核 SLA。

**控制器与端点**：

| Controller（基路径） | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| `ProductController` `/api/products` | GET ``（检索）、GET `/search/suggestion`、GET `/{spuId}`、GET `/internal/skus/batch`、`/internal/candidates`、`/internal/categories`、`/internal/cards` | 公开/内部 | 不 JOIN SKU 的稳定分页；价格区间相交；AI 候选视图裁剪 sellerId/成本 |
| `StockController` `/api/products/skus/stock` | POST `/{freeze,release,deduct,restock}` | 内部 | 库存四动作，整批原子事务 |
| `CategoryController` `/api/categories` | GET ``、`/flat`、`/{id}` | 公开 | 两级类目树 |
| `MetaController` `/api/meta` | GET `/brands`、`/dictionaries` | 公开 | 字典 |
| `HomeController` `/api/home` | GET `` | 公开 | 首页聚合 |
| `ListingController` `/api/listings` | GET `/search`、`/{id}`、POST ``、GET `/mine/list`、`/mine/{id}`、POST `/{id}/{status,resubmit,edit,delete}` | 读公开/写登录 | 闲置；发布限流（默认 10 次/24h、间隔 60s，含软删计数，超限 429） |
| `SellerProductController` `/api/seller/products` | GET ``、`/{spuId}`、POST ``、PUT `/{spuId}`、POST `/{spuId}/{status,resubmit}` | 卖家 | 商品维护；`requireOwned()` 统一归属；仅标题/主图可编辑（价格库存类目刻意不开） |
| `AdminCatalogController` `/api/admin` | 类目 GET/POST/PUT `categories...`、品牌 GET/POST/PUT `brands...`（含 status/删除） | 运营 | 主数据维护（后台走独立全量查询，禁用含 ACTIVE 的公开查询） |
| `AdminListingController` `/api/admin/listings` | GET ``、POST `/{id}/audit` | 运营 | 闲置审核（APPROVE/REJECT 显式动词映射） |
| `AdminProductAuditController` `/api/admin/products` | GET ``、`/{spuId}`、POST `/{spuId}/audit` | 运营 | 第三方商品上架审核 |
| `SearchAdminController` `/api/admin/search` | GET `/reconcile`、POST `/reindex` | 运营 | ES 对账与重建 |
| `AdminAuditSlaController` `/api/admin/audit-sla` | GET `/samples`、POST `/samples/{id}/review`、GET `/metrics`、POST `/sweep` | 运营 | 审核 SLA 事后抽查 |
| `InternalRatingController` `/api/products/internal` | POST `/ratings/recompute` | 内部 | 评价后评分聚合回写 |
| `InternalSellerShelfController` `/api/products/internal/seller` | POST `/{sellerId}/off-shelf` | 内部 | 商家资质失效联动下架 |
| `InternalListingController` `/api/listings` | GET `/internal/cards`、POST `/internal/{id}/want` | 内部 | 私信卡片与「想要」计数 |

**关键 Service / support**：
- `ProductService`：检索（ES 优先，`EsSearchEngine` 异常降级 MySQL）；预算/品类约束在 SQL 层生效。
- `StockService`：**原子条件更新**——可售=`stock-frozen_stock`，判断条件写进 UPDATE 的 WHERE（`freeze`/`deduct` 防超卖；`restock` 幂等，`t_sku_restock_log` 去重）。
- `CategoryService`、`ListingService`、`SellerProductService`、`HomeService`、`AuditSampleService`。
- `support/search/`：`SearchIndexer`（索引构建）、`EsSearchEngine`、`SearchSyncJob`（15s 增量兼补偿、启动全量对齐）、`SearchSynonyms`（同义词与拼音应用侧容错，pinyin4j，不装 ES 插件）。
- `BannedWordGuard`（违禁词 422，词表可配）、`SpuRiskJudge`、`CatalogLookup`、`Csv`、`InternalTokenFilter`。

**实体（duoduo_product 库）**：`CategoryEntity`、`BrandEntity`、`SpuEntity(t_spu)`、`SkuEntity(t_sku)`、`ListingEntity(t_listing)`、`AuditSample*`；表 `t_sku_restock_log`、`t_listing_want`。

### 6.3 duoduo-trade（:8083，交易域，最复杂）

购物车、下单拆单、库存冻结补偿、订单状态机、支付（渠道抽象 + 沙箱）、担保交易与佣金、卖家中心、后台干预/对账/报表、托管回补、卖家欠款、RocketMQ 削峰、Sentinel 限流、限时秒杀。

**控制器与端点**：

| Controller（基路径） | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| `CartController` `/api/cart` | GET ``、GET `/count`、POST `/items`、DELETE `/items/{skuId}`、DELETE `/selected` | 登录 | 加购校验可售 |
| `OrderController` `/api/orders` | POST `/preview`、POST ``（下单，幂等键）、POST `/queued`、GET `/placement/{idempotentKey}`、GET ``、GET `/{orderNo:\d{21}}`、POST `/{orderNo}/{cancel,pay,confirm,refund}`、GET `/{orderNo}/settlement` | 登录 | 买家侧；路径形状约束 21 位数字防路由抢占 |
| 同上 | POST `/internal/{orderNo}/refund`、GET `/internal/{orderNo}/fact`、GET `/internal/seller/{sellerId}/stats`、POST `/internal/sweep` | 内部 | 履约域退款/事实/卖家统计；手动补偿 |
| `PaymentController` | POST `/api/payments/callback/{channel}`、POST `/api/payments/sandbox/{payNo}`、GET `/api/payments/{payNo}`、GET `/sandbox/pay/{payNo}` | 回调公开/其余登录 | 渠道回调（验签）、沙箱确认与收银台页面 |
| `SellerController` `/api/seller` | GET `/summary`、`/analytics`、`/orders`、`/orders/{orderNo}`、POST `/orders/{orderNo}/{ship,refund}`、GET `/settlements` | 卖家 | 卖家中心，全部服务端归属校验 |
| `AdminOrderController` `/api/admin/orders` | GET ``、`/{orderNo}`、POST `/{orderNo}/force-close` | 运营 | 仅待付款单可强关，其余 422 |
| `AdminSettlementController` `/api/admin/settlements` | GET `/summary`、`/abnormal`、POST `/backfill`（dryRun）、`/compensate`、GET `/debts`、POST `/debts/{id}/{settle,write-off}` | 运营/ADMIN | 对账、历史托管单回补、手工补偿、欠款处理 |
| `AdminReportController` `/api/admin/reports` | GET `/overview`、`/charts` | 运营 | 报表 |
| `AdminMqController` `/api/admin/mq` | GET `/dlq`、POST `/dlq/replay` | 运营 | 死信查看与重放 |
| `SeckillController` `/api/seckill` | GET `/activities`、`/activities/{id}`、POST `/orders` | 浏览登录态/下单登录 | 秒杀 |
| `AdminSeckillController` `/api/admin/seckill` | GET `/activities`、POST `/activities`、PUT `/activities/{id}`、POST `/activities/{id}/status`、DELETE `/activities/{id}` | 运营 | 活动管理 |

**`OrderService`（主干，2000+ 行，改前必读类注释）关键方法**：

| 方法 | 职责 |
|---|---|
| `previewOrder(userId, req)` | 结算预览：SKU 快照、券试算、费用汇总，不落库 |
| `placeOrder(req)` / `placeOrder(userId, req)` | 下单主干：幂等快路径 → 取购物车/直购行 → Feign 批量取 SKU 快照（价格/可售/卖家）→ **按卖家拆单** → 券 lock 与分摊（只作用于商品总额最大的一单）→ 写订单头/明细快照/地址快照 → 调商品域冻结库存并逐项记账 → 失败按**逐项结果**精确补偿（防幽灵冻结）→ 清购物车 → 发通知事件 |
| `cancel / forceClose` | 取消/强关：仅 `PENDING_PAYMENT`；释放冻结、券解锁、支付单作废，全走状态机 |
| `pay / handleChannelCallback / sandboxPay` | 先落支付单再冻结/入账；回调 = 验签 + 金额核对 + CAS 幂等；成功后订单 PAID、资金进托管 CUSTODY、券核销（核销失败则支付整体不成立） |
| `ship / shipInternal` | 发货 SHIPPED；`@TransactionalEventListener(AFTER_COMMIT)` best-effort 向履约域补推「已揽收」轨迹（绝不抛异常阻断发货） |
| `confirmReceipt` | 确认收货 → COMPLETED → 结算放款 RELEASED（扣平台佣金，费率落库）→ 冻结转实扣 DEDUCTED |
| `refundByBuyer / refundByOperator / refundInternal` | 退款 → REFUNDED，钱回买家，券 v1 不退回；退货入库补偿 RESTOCK_PENDING→RESTOCKED；**售后退款复用同一内部入口** |
| `sweepExpiredOrders / scheduledSweep` | 超时关单（默认 30 分钟）+ 支付单过期 + 自动确认收货（发货满 7 天） |
| `internalOrderFact / internalSellerStats / settlementOf / toViews` | 订单事实、卖家计数（信用分因子）、托管视图、实体转视图（卖家中心复用） |
| 事件监听 | `onOrderShipped`（推物流）、`onOrder*Notice`（下单/支付/发货/退款通知 → social） |

**其他关键类**：
- `support/OrderStatus`：状态机单一事实来源。合法边：
  `PENDING_PAYMENT → PAID | CLOSED`；`PAID → SHIPPED | REFUNDED`；`SHIPPED → COMPLETED | REFUNDED`；`COMPLETED/CLOSED/REFUNDED` 终态。每次迁移写 `t_order_status_log`。
- `support/OrderNoGenerator`：21 位纯数字订单号。
- `payment/PaymentChannel`（SPI：`code()`、`instantSuccess()`、`prepay()`、`verifyCallback()`）、`PaymentChannelRegistry`（未知渠道直接拒绝，不回退 mock）、`MockPaymentChannel`、`SandboxPaymentChannel`（预下单二维码 → 收银台 → HMAC-SHA256 回调，含时间戳窗口与头/报文时间一致性）。
- `SettlementService`（托管台账 CAS：CUSTODY→RELEASED/REFUNDED；佣金 `PLATFORM_FEE_BPS` 默认 600bp=6%，自营 `seller_id=0` 免佣；`walletOf` 是"卖家能取多少钱"的唯一定义）、`SettlementBackfillService`（历史单回补，dryRun 预演、逐条独立提交、幂等靠 `uk_settle_order`）、`SellerDebtService`（卖家欠款台账）、`SellerService`、`AdminOpsService`、`CartService`。
- `client/`：`ProductClient/ProductGateway`（快照+库存，4xx 按业务缺失、5xx 转 DEGRADED）、`MarketingClient/MarketingGateway`（券 lock/trial/release/consume/sweep，release 带 scene 排障）、`UserAddressClient/UserAddressGateway`（地址快照）、`LogisticsClient/LogisticsNotifier`（轨迹）、`SocialNoticeClient/OrderNotifier`（通知）、`MembershipClient`、`AuditLogClient`；`config/InternalCallConfig` 统一注入 HMAC 头。
- 削峰：`mq/OrderPlaceProducer`、`OrderPlaceConsumer`（复用同一 `placeOrder`，失败重试 2 次进 RocketMQ DLQ）、`OrderPlacePayload`；`service/OrderPlaceService`、`entity/OrderPlaceTaskEntity(t_order_place_task)`；`support/OrderPlaceGuard`（Sentinel 资源 `order-place`，QPS 默认 100，超限 429）；`DlqService`。`sync/queue` 双模式由 `duoduo.trade.order-mode` 控制。
- 秒杀：`SeckillService`（Redis 原子预扣挡量，Redis 不可用降级纯 DB 原子扣减）、`SeckillActivity/Sku/OrderEntity`（三张表）。

**实体（duoduo_trade 库）**：`CartItemEntity`、`OrderEntity(t_order)`、`OrderItemEntity`（下单快照：标题/图/价格/地址三字段拷贝）、`OrderStatusLogEntity`、`StockFreezeEntity`（FROZEN→RELEASED/DEDUCTED）、`PaymentEntity`（PENDING→SUCCESS/EXPIRED/CLOSED，唯一键 `uk_pay_channel_idem`）、`SettlementEntity`、`SellerDebtEntity`、`OrderPlaceTaskEntity`、`Seckill{Activity,Sku,Order}Entity`。

### 6.4 duoduo-fulfilment（:8084，履约域）

评价晒单、售后仲裁（含退货物流）、物流轨迹、卖家信用分、分享圈社区、图片上传。

| 子域 | Controller 与关键端点 | 鉴权 |
|---|---|---|
| 评价 | `ReviewController` `/api/reviews`：GET `/product/{spuId}`、`/product/{spuId}/summary`（SQL 聚合评分，公开）；POST ``（提交评价）、`/{id}/append`（一次追评，30 天窗）、GET `/mine`、`/order/{orderNo}`、`/reviewed` | 读公开/写登录 |
| 信用分 | `CreditController`：GET `/api/reviews/seller/{sellerId}/credit`（公开只读）；`CreditInternalController` `/api/reviews/internal/credits/...`：POST `/refresh` 等 | 内部 |
| 售后 | `AfterSaleController` `/api/aftersales`：POST ``（立案，说明必填 422）、GET `/mine`、`/seller`、`/{id}`、POST `/{id}/{reply,return-shipment,return-receipt,escalate}`；`AdminAfterSaleController` `/api/admin/aftersales`：GET ``、POST `/{id}/arbitrate` | 买家/卖家/运营 |
| 物流 | `LogisticsController`：GET `/api/logistics/orders/{orderNo}/tracks`（买卖当事人）；`LogisticsInternalController`：POST `/api/logistics/internal/{orderNo}/tracks`（批量全有或全无） | 登录/内部 |
| 分享圈 | `ShareController` `/api/shares`：GET `/feed`、`/topics`、`/topics/{name}`、`/{id}`、`/mine/list`（读公开）；POST ``、`/{id}/{repost,edit,delete,like,favorite,report}`；`ShareCommentController`：GET `/comments`、POST `/{id}/comments`、`/comments/{id}/{like,report}`；`ShareAdminReportController` `/api/admin/share-reports`：GET ``、POST `/{id}/handle` | 读公开/写登录/运营 |
| 上传 | `UploadController`：POST `/api/uploads`（登录，落 `UPLOADS_DIR`）、GET `/uploads/{filename}`（公开读，仅 http/https 校验由 `ImageUrls` 负责） | 混合 |

**关键规则**：
- `ReviewService`：资格 = 登录人 == 订单买家且订单 COMPLETED 且 spuId 在明细内；唯一索引 `(order_id,user_id,spu_id)` 防撞（冲突 409）；商品标题/图快照；评分经事件异步回写 product（`ProductRatingSyncer`）。
- `AfterSaleService`：状态机 `AfterSaleStatus`（含退货两态 `WAITING_RETURN→RETURNING`）；Mapper 5 处 CAS 更新；**顺序铁律：先让钱动（调 trade 内部退款）、再改售后单状态**；卖家签收退货那一刻才退款；运营裁定复用 trade 退款资金通路，不开新通路；已完成订单边界。
- `LogisticsService`：幂等键 `uk_track_order_node_time`；发货即揽收；按发生时间正序返回；节点枚举 `LogisticsNode`；无时区输入按 UTC。
- `CreditService` + `CreditModel`：**base 60 + 履约完成率 18 + 评价质量 16 + 售后诚信 6 = 100**（启动校验权重和；`@ConfigurationProperties` 可覆盖）；取数编排 trade 计数（不可用返 503 DEGRADED，不按 0 单算）+ 本域评价/售后（售后只计 `APPROVE_REFUND`）；回写 user 域，同值不写库（幂等）。
- `ShareService/ShareCommentService/ShareTopicService/ShareReportService`：信息流、话题 #、转发/点赞/收藏/举报、评论回复与点赞；运营处理台。
- 支撑件：`OrderFacts`（订单事实统一取数口）、`OrderRefs`（订单号哈希，全项目唯一实现）、`ImageUrls`、`UserBriefLoader`；`client/{OrderClient,UserClient,ProductClient,SocialClient}` + 手写契约 DTO（不共享 jar）。

**实体（duoduo_fulfilment 库）**：`ReviewEntity`、`AfterSaleEntity`、`LogisticsTrackEntity`、`ShareEntity(t_product_share)`、`ShareCommentEntity`、`ShareCommentLike/Favorite/Like`、`ShareReportEntity`、`ShareTopicEntity`、`ShareTopicRelEntity`。

### 6.5 duoduo-marketing（:8085，营销域）

优惠券：领券中心、我的券包、下单锁券 → 支付核销 → 取消/关单解锁、TTL 补偿。**只算「能抵多少」，折扣金额由交易域落库**；无任何出边 Feign（叶子服务）。

| Controller | 端点 | 鉴权 |
|---|---|---|
| `CouponTemplateController` `/api/coupons` | GET `/templates`（领券中心，逐券下发 `claimable/claimableReason`）；POST `/admin/templates` | 公开/运营 |
| `CouponController` | POST `/templates/{id}/claim`（重复领取不报错，返 `replay=true` 与原券码）、GET `/mine?status=`、GET `/mine/usable?amount=`（服务端挑 `bestCouponCode`） | 登录 |
| `CouponInternalController` `/api/coupons/internal` | POST `/{lock,trial,release,consume,sweep}` | 内部（trade 直连 8085，不经网关） |

- `CouponService`：领券幂等唯一索引 `uk_coupon_user_tpl(user_id,template_id)`；锁/解/核销**全部 CAS 条件更新**；v1 仅满减券，不支持的组合明确 422；一张券只作用于拆单后商品总额最大的一单；券不随退款退回。
- `CouponReleaseJob`：`@Scheduled` 扫过期 LOCKED 券释放（默认间隔 60s、初始延迟 30s，批次可配）。
- `CouponCodeGenerator`：券码 = `CP` + 14 位去混淆字符（字符表剔除 `0/O/1/I`），刻意不用雪花 ID 规避 JS 精度坑。
- `CouponStatus`：`UNUSED → LOCKED → USED`（USED 终态）；LOCKED 可 release 回 UNUSED；EXPIRED 终态。锁定 TTL 默认 **35 分钟**（必须大于订单超时 30 分钟）。
- 表（duoduo_marketing 库）：`t_coupon_template`、`t_coupon`。

### 6.6 duoduo-ai（:8086，AI 导购「多多」）

无状态、无数据库、不存对话历史；唯一事实来源是 product 候选召回。

| 文件 | 职责 |
|---|---|
| `controller/AiController` `/api/duoduo` | POST `/chat`（免登白名单）、GET `/capabilities`（能力口径） |
| `service/ChatService` | 编排：约束解析 → 候选召回（独立超时）→ 个性化（宠物档案，只补充不覆盖、裁剪病史）→ mock 或 DashScope 生成 → 输出护栏 → 澄清/失败出口 |
| `support/ConstraintParser` | 正则解析预算上限/下限/区间与品类；三态区分（解析到/没提/提了没解析出→明确告知未按预算筛）；价格重叠语义 |
| `support/CategoryCatalog` | 类目口语别名表（猫粮↔宠物主粮） |
| `support/ReplyGuard` | 护栏：广告法绝对化/承诺词、价格一致性（正文价格必须落在候选 min/max）；命中替换为事实版回复而非拒答；micrometer 计数 |
| `support/PolicyKnowledge` / `Suggestions` / `MockReply` / `RecallDiversifier` / `PetContext` | 政策知识库（只写流程入口不写时效数字）、快捷问题/追问、mock 独立话术、候选同款去规格归一去重留低价、宠物上下文 |
| `support/IpRateLimiter` | 单键限流（默认 20/min）+ 全站总配额（默认 300/min）；网关先剥离伪造 XFF 写 TCP 对端 IP；Redis 故障冷却 60s 退内存 |
| `support/DashScopeClient` | 阿里百炼 HTTP 客户端（默认模型 `qwen-plus`，`DASHSCOPE_API_KEY` 注入、`DUODUO_AI_MOCK_ENABLED=false` 切真实） |
| `client/ProductSearchClient` | Feign `/api/products/internal/candidates`（connect 300ms / read 1500ms，独立 `AiFeignConfig`，非全局） |
| `client/UserProfileClient` | Feign user 域取宠物档案摘要（`PetBrief`） |

五类「答不了」问题（物流/退换/优惠/发票/支付）在调模型**之前**拦截，如实告知并给入口；超时/依赖故障 → 503 DEGRADED。

### 6.7 duoduo-social（:8087，社交域）

关注关系、私信（含商品/闲置卡片消息）、通知中心。独立库独立进程，高频轮询不拖登录主链路。

| Controller | 端点 | 鉴权 |
|---|---|---|
| `FollowController` | POST/DELETE `/api/socials/follows/{userId}`、GET `/follows/following`、`/follows/followers`、`/follows/{userId}/state`、`/users/{userId}/profile`、GET `/follows/{userId}/counts`（唯一公开接口） | 登录/counts 公开 |
| `MessageController` | POST `/api/socials/messages`、GET `/messages/conversations`、`/messages/conversations/{peerId}`、POST 已读、GET `/messages/unread-count` | 登录 |
| `NoticeController` `/api/socials/notices` | GET ``、`/unread-count`、`/unread-summary`、POST `/{id}/read`、`/read-all` | 登录 |
| `NoticeInternalController` `/api/socials/internal/notices` | POST ``（单条，两阶段落库：整批非法全不写）、POST `/announce`（全站公告） | 内部 |
| `FollowInternalController` `/api/socials/internal/following` | GET（关注列表，供信息流/通知取数） | 内部 |

- `MessageService`：会话键归一；卡片消息摘要**以服务端事实为准**（Feign 回源 product 商品/闲置卡片）；非互关限发一条 + 滑动窗口频控（默认 60s 内 10 条，超限 429）。
- `FollowService` / `NoticeService` / `UnreadService`：关注唯一索引防重、通知聚合未读。
- client：`UserClient`（用户摘要/角色）、`ProductClient`、`ListingClient`（卡片回源）。
- 表（duoduo_social 库）：`t_social_follow`、`t_social_message`、`t_social_notice`、`t_social_notice_read`。
- `support/MiniProgramPages`：通知跳转小程序页面映射。

---

## 7. 前端（React Monorepo）

`frontend/`，npm workspaces（`packages/*` + `apps/*`），React 18 + TS strict + Vite 5。共用包**不预构建**，Vite alias + tsconfig paths 直接吃 TS 源码。

| 工作区 | 内容 |
|---|---|
| `packages/api-sdk` | 零 React 依赖：类型化 HTTP 客户端（`client.ts` 解包 data、401 单飞刷新重放、5xx 退避重试≤3、4xx 不重试）、端点清单 `endpoints.ts`、类型 `types.ts`、图片工具 |
| `packages/shared` | 认证/应用 store、共用组件库（Layout/hooks/QRCode/评价区/信用分徽标/售后三端共用件）、`lib/roles.ts` 角色判定、全局样式；单向依赖 api-sdk |
| `apps/mall` | 商城（31 页面）：首页/搜索/商详/购物车/结算/订单/售后/评价/优惠券/地址/AI 导购/秒杀/闲置/分享圈/私信/通知/关注/登录/宠物/我的 |
| `apps/admin` | 平台后台（17 文件）：登录/Root、Dashboard、订单、类目、品牌、闲置审核、商品审核、商家审核、店员、运营账号、售后仲裁、举报处理、秒杀、审核 SLA、操作审计、图表 |
| `apps/merchant` | 第三方商家平台（12 文件）：登录/Root、入驻申请、Dashboard、订单与详情、商品、结算、售后、店员 |

**部署形态**：三应用各自 `vite build` → 共用 `frontend/Dockerfile`（构建参数 `APP` × `NGINX_CONF`）→ Nginx 托管静态页并同源反代 `/api`、`/sandbox`：

| 站点 | 容器 | 宿主访问 |
|---|---|---|
| 商城 | duoduo-web-app | http://localhost:8088 |
| 平台后台 | duoduo-admin-web-app | http://admin.localhost:8090 |
| 商家平台 | duoduo-merchant-web-app | http://merchant.localhost:8091 |

换主机名（而非只换端口）是因为 Cookie 主机级作用域、不区分端口。dev server 端口：mall 5173 / admin 5180 / merchant 5190，nginx 与 vite proxy 注入 `X-Duoduo-Entry`。

常用命令（在 `frontend/`）：`npm install --ignore-scripts`（本机 EBUSY 坑）、`npm run dev`、`npm run typecheck`、`npm run build`。

---

## 8. 微信小程序

`miniprogram/`：原生小程序（app.js/app.json + 29 个页面 + 自定义组件 nav-bar、notice-toast）。五个 tab：首页 / 分享圈 / 通知 / 购物车 / 我的。覆盖商品、分类、秒杀、券、地址、下单支付、售后、AI、社区、私信、闲置、实名等页面。`config.js` 配置网关基地址；A13 微信登录走 `/api/auth/wechat`（mock 通道可全链路联调，真机需 AppID/Secret）。`scripts/check-miniprogram.js` 为静态自检。

---

## 9. 数据库设计

MySQL 8，6 个业务库（容器首启自动执行 `docs/sql/00~06`；增量演进脚本在 `docs/sql/incremental/`，幂等 `CREATE TABLE IF NOT EXISTS`）。统一约束：utf8mb4、UTC、金额 `BIGINT` 整数分、主键雪花 ID（MyBatis-Plus `id-type: input`，insert 必须显式赋 ID）。

| 库 | 表 |
|---|---|
| duoduo_user | `t_user`、`t_refresh_token`、`t_pet_profile`、`t_user_address`、`t_merchant`、`t_merchant_member`、`t_merchant_material`、`t_audit_log`、`t_audit_sample`、`t_audit_sample_rate`、`t_sched_leader` |
| duoduo_product | `t_category`、`t_brand`、`t_spu`、`t_sku`（stock/frozen_stock）、`t_sku_restock_log`、`t_listing`、`t_listing_want`、`t_audit_sample`、`t_audit_sample_rate` |
| duoduo_trade | `t_cart_item`、`t_order`（含 discount_amount、收货人快照三字段）、`t_order_item`（标题/图/价快照）、`t_order_status_log`、`t_stock_freeze`、`t_payment`（`uk_pay_channel_idem`）、`t_settlement`（`uk_settle_order`）、`t_seller_debt`、`t_order_place_task`、`t_seckill_activity`、`t_seckill_sku`、`t_seckill_order`、`t_sched_leader` |
| duoduo_fulfilment | `t_review`（uk: order+user+spu）、`t_after_sale`（含寄回运单/签收列）、`t_logistics_track`（uk: order+node+time）、`t_product_share`、`t_share_comment`、`t_share_comment_like`、`t_share_like`、`t_share_favorite`、`t_share_report`、`t_share_topic`、`t_share_topic_rel`、`t_sched_leader` |
| duoduo_marketing | `t_coupon_template`、`t_coupon`（uk: user+template）、`t_sched_leader` |
| duoduo_social | `t_social_follow`、`t_social_message`、`t_social_notice`、`t_social_notice_read` |

核心关系：

```
User 1—N PetProfile / CartItem / Order / UserAddress / MerchantMember
Category 1—N Spu 1—N Sku
CartItem N—1 Sku
Order 1—N OrderItem；Order 1—1 Payment；Order 1—1 Settlement；
Order 1—N OrderStatusLog / StockFreeze / Review
Merchant 1—N MerchantMember / MerchantMaterial
CouponTemplate 1—N Coupon
SeckillActivity 1—N SeckillSku；SeckillOrder（秒杀配额台账）
```

---

## 10. 横切机制

1. **统一响应/异常**：所有出参 `ApiResponse`；`GlobalExceptionHandler` 把异常翻译为 ErrorCode 对应 HTTP 状态。400（参数）与 422（业务规则）严格区分。
2. **身份与鉴权三层**：网关 JWT + 入口收窄（第 1 层）→ `PermissionInterceptor` 的 `@RequirePerm` 权限点（第 2 层）→ Service 层归属校验（第 3 层，写接口只认登录态 userId，绝不接受请求体 userId/sellerId）。
3. **RBAC**：`Permission`（19 个权限点）+ `RolePermissionMatrix`（4 角色唯一权威），替代散落的裸角色字符串比较。
4. **权限版本（E17）**：角色变更 → user 域写 Redis 版本 + 令牌 `pv` 声明 → 网关每请求比对，不一致即要求刷新；降级/改角色同步吊销 refresh 令牌；开关可退回。
5. **内部调用安全**：`InternalCallAuth` HMAC 凭据（`dd1.` 前缀，5 分钟窗口，nonce 防重放，失败关闭）；各服务 `InternalTokenFilter` 保护 `/internal/**`；Feign 由各服务 `InternalCallConfig` 统一加头；**内部流量不经网关**。
6. **ID 体系**：雪花 ID（worker 按服务分配）；64 位 ID 出全站自动变 JSON 字符串（`LongToStringJacksonModule`），金额/计数/epoch 时间戳白名单保数字。
7. **审计（E18）**：`@Audited` 注解 → `AuditInterceptor` → `RemoteAuditSink` → user 域统一落 `t_audit_log`；记录只可读不可删改；资质读取等敏感动作必留痕。
8. **调度选主**：`SchedLeaderLock` 基于 `t_sched_leader` 的 UPDATE 优先两步抢占，`REQUIRES_NEW` 独立事务，锁自动过期。
9. **请求链路**：`X-Request-Id` 贯穿网关 MDC → 各服务 JSON 结构化日志（含 requestId）；日志不记密码/令牌/密钥/收货人隐私。
10. **API 文档**：Knife4j/springdoc 自动出 `/v3/api-docs`（swgger-ui 关闭）；`scripts/export-openapi.ps1` 导出 `docs/openapi/`。

---

## 11. 服务间调用矩阵

| 调用方 → 被调方 | 端点（节选） | 用途 |
|---|---|---|
| trade → product | `GET /api/products/internal/skus/batch`；POST `/api/products/skus/stock/{freeze,release,deduct,restock}`；内部 cards | SKU 快照、库存四动作、商品卡片 |
| trade → marketing | POST `/api/coupons/internal/{lock,trial,release,consume,sweep}` | 下单锁券/试算/关单解锁/支付核销/补偿 |
| trade → user | GET `/api/addresses/internal/snapshot`；商家 membership | 下单地址快照（归属在 user 判，非本人 404） |
| trade → fulfilment | POST `/api/logistics/internal/{orderNo}/tracks` | 发货后补揽收节点（AFTER_COMMIT，best-effort） |
| trade → social | POST `/api/socials/internal/notices` | 下单/支付/发货/退款通知 |
| fulfilment → trade | GET `/api/orders/internal/{orderNo}/fact`、`/internal/seller/{sellerId}/stats`；POST `/internal/{orderNo}/refund` | 评价资格、信用分计数、售后裁定执行退款 |
| fulfilment → user | GET `/api/users/internal/{briefs,roles}`；POST `/internal/{userId}/credit`；pet-profiles | 昵称头像、信用分回写 |
| fulfilment → product | POST `/api/products/internal/ratings/recompute` | 评分聚合回写 |
| fulfilment → social | internal notices | 售后/互动通知 |
| ai → product | GET `/api/products/internal/candidates`、categories | 导购候选（裁剪 sellerId/成本/库存明细） |
| ai → user | GET 宠物档案内部接口 | 个性化（裁剪敏感信息） |
| user → social | internal notices | 审核结果等通知 |
| user → product | POST `/api/products/internal/seller/{sellerId}/off-shelf` | 资质失效/违规联动下架 |
| product → user | merchant-membership / merchant-license / merchant-risk / merchant-violation；`/internal/audit-logs` | 商家准入校验、风险、审计上报 |
| social → product/user | internal cards、listings want、users briefs/roles | 卡片消息回源、用户资料 |
| 全部业务服务 → user | POST `/api/users/internal/audit-logs` | 操作审计统一落库 |

降级纪律：4xx（业务缺失）与 5xx（依赖故障）分开处理——后者转 `DEGRADED` 503，绝不把依赖故障伪装成「0 条/0 单」的业务事实。

---

## 12. 关键业务流程

### 12.1 担保交易主链路

```
加购(校验可售 stock-frozen)
  → 下单 POST /api/orders（idempotentKey + 唯一索引幂等）
      取 SKU 快照 → 按卖家拆单（一单一卖家）→ 券 lock（失败 422 带人话原因）
      → 写订单/明细/地址快照 → 库存 freeze（原子条件更新，逐项记账，失败精确补偿）
  → PENDING_PAYMENT
  → 支付预下单（PaymentChannel：mock 即时成功 / sandbox 二维码收银台）
  → 回调（验签 + 金额核对 + CAS 幂等）→ PAID + 资金 CUSTODY + 券 consume
  → 卖家发货 → SHIPPED（AFTER_COMMIT 补揽收物流节点）
  → 买家确认 / 发货满 7 天自动确认 → COMPLETED
      → 结算放款 RELEASED（扣 1% 佣金，费率落库）→ 冻结转 DEDUCTED
分支：买家取消 / 运营强关（仅待付款）/ 30 分钟超时 → CLOSED
      （释放冻结 RELEASED + 券解锁 + 支付单 EXPIRED/CLOSED）
分支：退款（买家申请/卖家同意/售后裁定）→ REFUNDED
      （钱回买家；退货签收后 RESTOCK_PENDING→RESTOCKED 入库补偿；券 v1 不退）
```

### 12.2 售后仲裁

买家立案（说明必填）→ 卖家 reply 同意/拒绝 → 买家填寄回运单 `WAITING_RETURN` → 卖家签收 `RETURNING`（**此刻触发内部退款**）→ 完结；谈不拢买家 escalate → 运营 `arbitrate` 裁定（裁定退款直接复用 trade 内部退款通路）。全程 5 个 CAS 状态更新留痕。

### 12.3 评价与信用分

评价资格（本人 + COMPLETED + spu 在明细）→ 唯一索引防重 409 → 快照落库 → 事件回写商品评分；追评一次（30 天窗）。
信用分 = 60 + 完成率×18 + 评价质量×16 + 售后诚信×6，夹取 [0,100]；因子实时取数，定时兜底刷新并回写 user 域（同值不写）。

### 12.4 优惠券生命周期

`UNUSED` → 下单 lock（TTL 35min，CAS）→ 支付成功 consume → `USED`（终态）；取消/强关/超时三处 release 回 `UNUSED`；补偿 job 扫过期锁定券；退款不退回。

### 12.5 秒杀与削峰

- 秒杀：Redis 原子预扣挡量 → DB 原子扣减兜底（Redis 故障自动降级纯 DB）；活动/SKU/订单三表，运营台管理。
- 下单削峰（可选）：`DUODUO_TRADE_ORDER_MODE=queue` 时 POST `/api/orders/queued` 落 `t_order_place_task` 并发 RocketMQ；`OrderPlaceConsumer` 异步执行与同步完全相同的 `placeOrder`；Sentinel 资源 `order-place` QPS 限流（默认 100）；失败重试耗尽进 DLQ，运营台查询/重放。

### 12.6 AI 导购

免登提问 → 限流（单键+全局，伪造 IP 无效）→ 约束硬解析（预算/品类，SQL 层过滤）→ product 候选召回（1.5s 超时，只返回真实在售商品）→ 宠物档案个性化 → mock/DashScope 生成 → ReplyGuard 价格与违禁话术校验 → 返回（引用商品必须来自候选；澄清只问一次；五类无数据源问题直接给出口）。

---

## 13. 定时任务清单

| 服务 | 任务 | 默认节奏 | 作用 |
|---|---|---|---|
| trade | `OrderService.scheduledSweep` | 60s（初始延迟 30s） | 超时关单、支付单过期、自动确认收货/放款 |
| marketing | `CouponReleaseJob.scheduledSweep` | 60s（延迟 30s） | 过期锁定券释放 |
| fulfilment | `CreditRefreshJob.refresh` | 15min（延迟 2min） | 卖家信用分兜底刷新回写 |
| product | `SearchSyncJob.sweep` | 15s | ES 增量同步 + 补偿对齐（ES 缺席自动跳过） |
| product | `AuditSampleJob.sweep` | 24h（延迟 20min） | 审核 SLA 抽样 |
| user | `MerchantLicenseJob` / `MerchantAuditSampleJob` | 周期巡检 | 资质到期通知/下架联动；入驻审核 SLA 抽样 |

多实例安全：经 `SchedLeaderLock` 选主（`duoduo.sched.leader-enabled=false` 可退回外部调度）。所有补偿任务本身可重入。

---

## 14. 配置与环境变量

配置全部来自环境变量 + 默认值（application.yml 占位符）。关键项：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DUODUO_*_PORT` | 8080~8087 | 各服务端口（刻意不用通用 `*_PORT` 名） |
| `DB_URL / DB_USERNAME / DB_PASSWORD` | 127.0.0.1:3306/各库，duoduo/duoduo | JDBC（强制 serverTimezone=UTC） |
| `NACOS_ENABLED / NACOS_ADDR / NACOS_USERNAME / NACOS_PASSWORD` | true / 127.0.0.1:8848 / nacos / nacos | 服务注册发现；`--spring.profiles.active=local` 可关闭 |
| `REDIS_HOST / REDIS_PORT` | 127.0.0.1:6379 | 网关权限版本、trade 秒杀、ai 限流 |
| `DUODUO_JWT_SECRET` | 本地开发占位串（生产必改） | JWT 签名（网关与 user 共享） |
| `INTERNAL_TOKEN` | duoduo-local-dev-internal-token | HMAC 根密钥（生产必改） |
| `INTERNAL_SKEW_SECONDS / INTERNAL_CALLER_SECRETS` | 300 / 空 | 内部签名窗口、按调用方独立密钥 |
| `ORDER_EXPIRE_MINUTES / SWEEP_* / PLATFORM_FEE_BPS / AUTO_RECEIPT_DAYS / SETTLEMENT_HOLD_DAYS` | 30 / 60000,30000,200 / 600 / 7 / 7 | 关单超时、扫描参数、佣金基点、自动收货天数、售后期（T+N 冻结天数） |
| `SANDBOX_CASHIER_URL / SANDBOX_CALLBACK_SECRET / SANDBOX_EXPIRE_MINUTES` | 127.0.0.1:8080/sandbox/pay / 占位 / 15 | 沙箱收银台、回调 HMAC、支付单有效期 |
| `DUODUO_TRADE_ORDER_MODE / ROCKETMQ_* / DUODUO_TRADE_PLACE_QPS` | sync / namesrv:9876 / 100 | 削峰模式与限流 |
| `COUPON_LOCK_TTL_MINUTES` | 35 | 券锁定 TTL（必须略大于 30） |
| `DUODUO_SEARCH_ENABLED / DUODUO_ES_URL / DUODUO_SEARCH_SWEEP_MS` | true / http://duoduo-es:9200 / 15000 | ES 开关与同步 |
| `DASHSCOPE_API_KEY / DUODUO_AI_MODEL / DUODUO_AI_MOCK_ENABLED` | 空 / qwen-plus / true | AI 真实凭据 |
| `DUODUO_AI_RATE_LIMIT / DUODUO_AI_GLOBAL_RATE_LIMIT / DUODUO_AI_RECALL_READ_TIMEOUT_MS` | 20 / 300 / 1500 | AI 限流与召回超时 |
| `WECHAT_APPID / WECHAT_SECRET / DUODUO_WECHAT_MOCK_ENABLED` | 空 / 空 / true | 微信登录 |
| `SOCIAL_MSG_RATE_WINDOW / SOCIAL_MSG_RATE_LIMIT` | 60 / 10 | 私信频控 |
| `UPLOADS_DIR` | /data/uploads | 图片落盘卷 |
| `CORS_ORIGINS` | localhost:5173/3000/5180/5190 | 网关跨域白名单 |
| `DUODUO_PERM_VERSION_ENABLED` | true | 权限版本开关（排障可退） |

---

## 15. 项目运行方式

### 15.1 前置

JDK 21、Maven 3.9+、Docker Desktop（含 Compose）、Node 18+（前端）。

### 15.2 编译（本仓指定命令）

```powershell
java -classpath "D:/devsoft/apache-maven-3.9.4/boot/plexus-classworlds-2.7.0.jar" `
  "-Dclassworlds.conf=D:/devsoft/apache-maven-3.9.4/bin/m2.conf" `
  "-Dmaven.home=D:/devsoft/apache-maven-3.9.4" `
  "-Dmaven.multiModuleProjectDirectory=d:/智能体项目/duoduo-pet-mall-java" `
  "-Dmaven.legacyLocalRepo=true" `
  org.codehaus.plexus.classworlds.launcher.Launcher -ntp -DskipTests package
```

（等价短命令：`mvn -gs maven/settings.xml -B -DskipTests package`；注意改代码后必须重打 jar，否则 Docker 构建命中 COPY 缓存会把旧 jar 打进镜像。）

### 15.3 Docker Compose 全栈（推荐）

```powershell
docker compose up -d --build            # 首次：3 中间件 + 8 后端 + 3 前端
docker compose ps                        # 看健康状态
docker compose --profile search up -d duoduo-es   # 可选：ES（检索自动降级，不起也行）
docker compose --profile mq up -d        # 可选：RocketMQ（需把 trade 重建为 queue 模式）
docker compose logs -f duoduo-trade-app
docker compose down                      # 停（保留数据）；down -v 连数据卷一起删
```

默认起 14 个容器：mysql/redis/nacos + gateway/user/product/trade/fulfilment/marketing/ai/social + 三个 web 站点。MySQL 首启自动导入 `docs/sql/`。访问：网关 8080、商城 8088、后台 8090、商家 8091、Nacos 控制台 8848/nacos。

只起中间件（业务在 IDE 跑）：`docker compose up -d mysql redis nacos`。
免 Docker 备用：`scripts/start-infra.ps1`（免安装 MySQL/Redis）；`--spring.profiles.active=local`（H2 + 关 Nacos 的轻量档）。

### 15.4 前端

```powershell
cd frontend
npm install --ignore-scripts
npm run dev          # 商城开发服务器（5173）
npm run typecheck    # 三应用 tsc --noEmit
npm run build        # 三应用全部构建
```

### 15.5 基线自检（改代码前）

1. `docker compose ps` 容器健康；2. Maven package BUILD SUCCESS；3. 受影响冒烟全绿；4. 前端改动 `typecheck` + `build` + 对应 `*-ui-check.mjs`。

---

## 16. 测试体系

| 层 | 位置 | 内容 |
|---|---|---|
| JUnit 单测 | 各模块 `src/test/java` | 210+（ai 94、common-web 21、trade 19、product 15、user 15、marketing 13、social 10、fulfilment 9、gateway 8、common 6）；`mvn -o test` |
| 接口冒烟 | `scripts/*smoke.ps1`、`*.py`、`e8-*.mjs` | 20+ 套 1400+ 断言：smoke（用户/商品）、trade/order/payment/pay-channel/settlement/seller/admin、review/aftersale/logistics/credit、coupon、address、id-precision、ai、search（需 ES）、wechat-auth/bind、seckill、mq、share-circle、social、upload、audit-log、audit-sla、perm-version、entry-scope 等；多套带「断言总数守卫」 |
| 一键回归 | `scripts/run-all-smoke.ps1` | 顺序执行全部冒烟（通配 `*smoke.ps1`），落盘 `logs/smoke/`，ES 不在则显式跳过 search |
| CI 子集 | `scripts/ci-run.ps1 -Scripts ...` | CI 与本地同一入口，落盘 `logs/ci/`，支持 Linux pwsh |
| 浏览器 e2e | `scripts/e2e/*-ui-check.mjs` | 25 套真实 Chromium：商家/评价/售后/物流/券/地址/秒杀/分享/通知私信关注/入驻/资质/店员/商品审核/首页/商品详情/我的闲置/图表/入口隔离/布局等；夹具用 PowerShell 操作 docker exec SQL |
| 前端单测 | `scripts/*.test.js`、`check-*.mjs` | Node 断言 251；`scripts/lib/check-frontend-imports.mjs`（import 可解析性）、`check-bundle-isolation.mjs`（产物闭包隔离） |
| 契约 | `scripts/export-openapi.ps1` | 导出 6 服务 OpenAPI 与端点摘要到 `docs/openapi/` |

纪律：每个用例自建前置并清理复原；ID 精度测试只断言原始响应文本（不反序列化）；新增白名单/内部接口必须配套「无凭据 403 / 有凭据 200 / 无敏感字段」断言。

---

## 17. CI/CD

`.github/workflows/ci.yml`：
- **build**：10 个 Maven 模块编译打包 + 前端三应用 tsc/vite；
- **smoke**：起 Docker 全栈 → 容器 healthy → 经 `scripts/ci-run.ps1` 跑冒烟子集 → 失败即 job 失败并上传日志 artifact。

镜像：业务服务共用根 `Dockerfile`（`eclipse-temurin:21-jre-alpine`、非 root uid 10001、Actuator readiness 健康检查、预建 `/data/uploads` 与 `/data/merchant-materials`）；前端共用 `frontend/Dockerfile`（构建 + Nginx）。

---

## 18. 开发约定与不变式精要

**分层**：Controller（≤5 行，只做校验与委托）→ Service（业务规则，入口校验 + 服务层再校验）→ Mapper；跨服务只有补偿没有分布式事务。

**17 条不变式（违反即缺陷，详见 `docs/agent/03`）**：

1. 幂等靠唯一索引（下单/支付/评价/领券/退款），不靠先查再插，不引入分布式锁；
2. 库存可售 = `stock - frozen_stock`，扣减走原子条件更新（券同理）；
3. 先落支付单再扣库存；回调 = 验签 + 金额核对 + CAS 幂等；
4. 补偿按逐项结果精确记录（防幽灵冻结）；
5. 订单状态只走状态机，迁移必写 `t_order_status_log`；
6. 对外动词 ≠ 库内状态（APPROVE→APPROVED），必须显式映射；
7. 后台列表不复用只含 ACTIVE 的公开查询；
8. 写接口归属只认登录态，DTO 不接受 userId/sellerId；
9. 内部只读接口必须裁剪字段（不得出现 receiver*/phone/sellerId/成本）；
10. 400（VALIDATION_ERROR）与 422（BUSINESS_RULE）分清；
11. 内部接口失败关闭（密钥未配即拒绝）；
12. 金额整数分、时间 UTC、对外时间 ISO-8601；
13. `id-type: input` 模块 insert 必须显式赋雪花 ID；
14. 网关白名单按路径匹配不看方法——只放精确只读路径，写接口不得在白名单前缀下；
15. 中文写入显式 `SET NAMES utf8mb4`；
16. 64 位 ID 到前端一律字符串；
17. Long 出参默认字符串化，仅金额/计数/epoch 时间戳语义白名单保数字。

注释要求写清「为什么」；金额/密钥/隐私不入日志；页面三态（加载/空/错误）齐全，4xx 不重试、5xx 重试 ≤3。

每轮交付：编译 → 镜像重建 healthy → 受影响冒烟 → 前端 typecheck/build → 更新 `docs/PLAN.md` 与 README（同一次提交）。

---

## 19. 关键文件速查（按意图）

| 想做什么 | 看哪里 |
|---|---|
| 理解业务与术语 | `docs/agent/01-项目目标与领域模型.md` |
| 改架构/加服务/加跨域调用 | `docs/agent/03-架构与不变式.md` |
| 查接口精确契约 | `docs/agent/04-接口契约全集.md`、`docs/openapi/` |
| 抄代码骨架/配方 | `docs/agent/05-开发任务手册.md` |
| 下单主链路（最易错） | `duoduo-trade/.../service/OrderService.java`、`support/OrderStatus.java` |
| 库存原子扣减 | `duoduo-product/.../service/StockService.java`、`mapper/SkuMapper.java` |
| 支付渠道 | `duoduo-trade/.../payment/{PaymentChannel,SandboxPaymentChannel}.java` |
| 售后退款顺序 | `duoduo-fulfilment/.../service/AfterSaleService.java` |
| 优惠券 CAS | `duoduo-marketing/.../service/CouponService.java`、`mapper/CouponMapper.java` |
| 信用分公式 | `duoduo-fulfilment/.../support/CreditModel.java` |
| AI 护栏/召回 | `duoduo-ai/.../support/{ConstraintParser,ReplyGuard,IpRateLimiter}.java` |
| 网关鉴权/白名单 | `duoduo-gateway/.../filter/JwtAuthGlobalFilter.java`、`config/GatewaySecurityProperties.java`、`application.yml` |
| 统一响应/错误码/异常 | `duoduo-common/.../api/ApiResponse.java`、`enums/ErrorCode.java`、`exception/BizException.java` |
| RBAC 权限 | `duoduo-common/.../security/perm/{Permission,RolePermissionMatrix}.java` |
| 内部调用签名 | `duoduo-common-web/.../web/internal/InternalCallAuth.java`、各服务 `config/InternalCallConfig.java` |
| ID 序列化 | `duoduo-common-web/.../web/LongToStringJacksonModule.java` |
| 定时选主 | `duoduo-common-web/.../web/sched/SchedLeaderLock.java` |
| 表结构 | `docs/sql/01~06-*.sql`（权威）+ 模块内 `resources/db/schema.sql`（副本） |
| 环境变量 | 各服务 `src/main/resources/application.yml` + `docker-compose.yml` + `.env.example` |
| 前端请求内核 | `frontend/packages/api-sdk/src/`（client/endpoints/types） |
| 跑测试 | `scripts/run-all-smoke.ps1`、`scripts/ci-run.ps1`、`scripts/e2e/run-ui-checks.ps1` |
| 排错踩坑 | `docs/agent/08-环境运维与踩坑禁忌.md` |
