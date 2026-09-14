# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 完整架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)。

---

## 一、本机环境现状（务必先读）

这台机器**不能直接跑完整微服务栈**，原因与替代方案：

| 依赖 | 本机状态 | 影响 | 替代方案 |
|---|---|---|---|
| JDK 21 | ✅ `D:\jdk\jdk21`（21.0.10 LTS） | — | 已配置 `maven/env.ps1` |
| Maven 3.9.4 | ✅ `D:\devsoft\apache-maven-3.9.4` | 全局 `settings.xml` 的 `mirrorOf=*` 指向**已失效的公司私服** `100.wunding.com:1443` | 用项目内 `maven/settings.xml`（阿里云镜像）绕开，**不改你的全局配置** |
| Docker Desktop | ❌ 未安装（choco 安装因缺管理员权限失败） | MySQL / Redis / Nacos 无法用 compose 起 | 见下 |
| MySQL | ❌ 未安装 | 用户服务连不上库 | `--spring.profiles.active=local` 用 **H2 内存库**（同一份 `schema.sql`，与生产 MySQL 方言一致） |
| Nacos | ❌ 未启动 | 服务注册与网关 `lb://` 路由不可用 | `local` profile 关闭 Nacos，网关路由改静态地址 |
| Redis | ✅ `D:\devsoft\redis`（未启动） | 当前阶段未依赖（刷新令牌走库表） | P3 阶段接入缓存时再启动 |

**结论**：本机可以真编译、真启动、真验证「网关 + 用户服务」双进程链路；完整微服务栈需要你补齐 Docker Desktop 或原生安装 MySQL / Nacos。

需要管理员权限安装 Docker Desktop 时，请以**管理员身份**打开 PowerShell 执行：

```powershell
choco install docker-desktop -y
```

---

## 二、构建与运行

```powershell
# 1) 载入本地环境（仅当前会话生效，不改系统变量）
. .\maven\env.ps1

# 2) 全量构建（首次会拉取 Spring 依赖，约数分钟）
mvn -gs maven\settings.xml -B -DskipTests clean install

# 3) 启动用户服务（H2 内存库 + 关闭 Nacos）
java -jar duoduo-user\target\duoduo-user-1.0.0.jar --spring.profiles.active=local

# 4) 另开一个终端启动网关（静态路由指向 8081）
java -jar duoduo-gateway\target\duoduo-gateway-1.0.0.jar --spring.profiles.active=local
```

启动后统一入口是网关：`http://localhost:8080`。

### 冒烟验证（网关 → 用户服务 全链路）

```powershell
# 注册（免认证，网关放行）
curl.exe -i -c cookies.txt -X POST http://localhost:8080/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"phone\":\"13900000001\",\"password\":\"duoduo123\",\"nickname\":\"铲屎官小林\"}"

# 未带令牌访问受保护接口 → 401（网关拦截，请求不会打到后端）
curl.exe -i http://localhost:8080/api/users/me

# 带令牌访问 → 200
curl.exe -i -b cookies.txt http://localhost:8080/api/users/me

# 建宠物档案
curl.exe -i -b cookies.txt -X POST http://localhost:8080/api/pets ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"豆豆\",\"species\":\"cat\",\"breed\":\"布偶\",\"birthday\":\"2026-06-14\",\"weightKg\":2.4,\"allergens\":[\"谷物\",\"牛肉\"]}"

# 生命阶段与出生日期矛盾 → 422（业务规则生效）
curl.exe -i -b cookies.txt -X POST http://localhost:8080/api/pets ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"团团\",\"species\":\"dog\",\"birthday\":\"2026-08-01\",\"lifeStage\":\"SENIOR\"}"
```

---

## 三、模块结构

```
duoduo-pet-mall-java/
├── pom.xml                     聚合父 POM：统一版本、编译参数（release 21）、插件管理
├── maven/
│   ├── settings.xml            项目专用 Maven 配置（阿里云镜像 + 项目内本地仓库）
│   └── env.ps1                 本地环境变量（JAVA_HOME / MAVEN_HOME）
├── docs/
│   ├── ARCHITECTURE.md         完整架构设计（服务拆分、分库分表、容量模型、降级矩阵、路线）
│   └── sql/                    生产 MySQL DDL
├── docker-compose.yml          基础设施编排（MySQL / Redis / Nacos），需 Docker
├── duoduo-common/              通用：统一响应 / 错误码 / 业务异常 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/          Servlet 侧基础设施：全局异常处理 / 请求 ID / 身份上下文透传
├── duoduo-gateway/             API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
└── duoduo-user/                用户服务：注册登录 / 令牌轮转 / 实名 / 宠物档案
```

**分层纪律**：Controller 不写业务逻辑；Service 不依赖 `HttpServletRequest`；
数据访问统一走 Mapper（MyBatis-Plus），参数化查询，禁止字符串拼接 SQL。

---

## 四、已实现的接口（本轮阶段）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并直接登录（手机号唯一，冲突转 409） | 免 |
| POST | `/api/auth/login` | 密码登录；账号不存在与密码错误返回同一提示，防手机号枚举 | 免 |
| POST | `/api/auth/refresh` | 刷新令牌轮转：旧令牌立即吊销，签发新对 | 免 |
| POST | `/api/auth/logout` | 吊销刷新令牌并清 Cookie | 免 |
| GET | `/api/users/me` | 当前用户资料 | 需登录 |
| PATCH | `/api/users/me` | 修改昵称 / 头像 | 需登录 |
| POST | `/api/users/me/realname` | 实名认证（身份证仅存掩码） | 需登录 |
| GET | `/api/pets` | 宠物档案列表 | 需登录 |
| POST | `/api/pets` | 新建档案（首只自动设为当前宠物） | 需登录 |
| PUT | `/api/pets/{id}` | 更新档案 | 需登录 |
| POST | `/api/pets/{id}/default` | 设为当前宠物 | 需登录 |
| DELETE | `/api/pets/{id}` | 删除档案（默认宠物自动顺延） | 需登录 |

统一响应：`{"code","message","data","requestId","timestamp"}`；失败时 `code` 与 HTTP 状态码语义一致。

---

## 五、已落地的工程约束

- **认证**：JWT 短时效 access（15 min）+ 服务端 refresh（7 d）。refresh 只存 SHA-256 哈希并轮转，
  即使库被拖走也无法直接冒用；令牌同时下发 httpOnly Cookie 与响应体，前端不得写入 localStorage。
- **身份透传**：网关校验通过后写入 `X-User-Id / X-User-Role` 等内部头，
  并**先剥离客户端自带的同名头**，防止伪造身份越权；业务服务禁止公网直连。
- **边界校验**：所有入参用 Jakarta Validation 注解在 Controller 侧校验；
  校验失败统一转 400 + 字段级提示。
- **错误体系**：服务层只抛 `BizException`，由 `GlobalExceptionHandler` 统一转换；
  5xx 堆栈只进服务端日志，客户端只拿到规范结构 + requestId。
- **链路追踪基础**：每请求生成 `X-Request-Id`，写入响应头与日志 MDC，跨服务透传。
- **分布式 ID**：雪花算法，含时钟回拨保护；主键不再使用自增，为后续分库分表铺路。
- **业务规则**：宠物档案的「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）。

---

## 六、后续阶段（按 docs/ARCHITECTURE.md 的路线推进）

| 阶段 | 内容 |
|---|---|
| **P1** | 商品服务（类目/SPU/SKU/商详）+ 交易服务（购物车/下单拆单/支付/订单状态机）+ MySQL + Redis |
| **P2** | 履约服务（发货/物流/售后/仲裁）+ 评价 + 营销（优惠券）+ XXL-Job 分布式任务 |
| **P3** | AI 服务「多多」+ Elasticsearch + RocketMQ 削峰 + Sentinel 限流 + Seata 分布式事务 |
| **P4** | SkyWalking / Prometheus 可观测 + K8s 编排 + JMeter 压测（对齐容量验收标准） |

前端（React 18 + Vite）沿用此前实现的 17 个页面，接口契约调整后即可对接。
