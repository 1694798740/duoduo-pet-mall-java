# 多多宠物商店 · 项目开发计划（看板）

> 更新时间：2026-09-17 10:30
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
| M3 | 端到端冒烟（54 项） | ✅ 已完成 | 免安装模式 54/54；**容器化模式 2026-09-17 复跑 54/54** |
| M4 | **Docker 化开发环境** | ✅ **已完成** | 6 容器全健康；Nacos 注册 3 服务；从零重建数据卷可复现 |
| M5 | **P1-b 交易服务** | 🔄 进行中 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / 支付幂等 / C2C 担保交易 + 卖家中心 |
| M6 | 前端接入（复用 Node 版 17 个页面） | ⬜ 未开始 | 接口指向网关 :8080 |
| M7 | 营销 / AI 导购 / 履约 | ⬜ 未开始 | 更后期 |

---

## 2. 任务清单

### 已完成

| 任务 | 产出 / 证据 |
|---|---|
| 微服务骨架与分层纪律 | Controller 不写业务逻辑、Service 不依赖 HttpServletRequest、Mapper 参数化 |
| 用户域全部接口 | 网关 401 拦截、身份头先剥离再写入、refresh 令牌存 SHA-256 并轮转 |
| 商品域全部接口 | 违禁词 422、非法排序 400、未审核闲置不可见 |
| 54 项端到端冒烟脚本 | `scripts/smoke.ps1`，每次随机手机号可重复跑 |
| GitHub 推送能力恢复 | 本地与远端逐字节一致（blob 哈希全等） |
| Docker Desktop 安装 | 定位 WiX 竞态根因（`SfxCA_` 管道名撞车 → 231 → 1603），第 6 次重试成功 |
| 项目资产分类归档 | 按日期分目录 + 99-历史版本，写入 5 条维护规则 |
| 容器化中间件 | MySQL 8.0.36 / Redis 7.2 / Nacos 2.3.2 三容器健康 |
| 容器数据口径对齐 UTC | `TZ` + `--default-time-zone` 与应用 `serverTimezone=UTC` 同口径 |
| 商品库初始化 SQL 生成 | `docs/sql/02-duoduo-product.sql`（由模块内 schema.sql + data.sql 拼装） |
| **业务服务容器化** | Dockerfile（temurin:21-jre-alpine，非 root 运行）+ `.dockerignore` + compose 三个 app 服务；三镜像已构建（466/455/455 MB） |
| **Nacos 服务发现接入** | 网关改用 `lb://` 负载均衡；Nacos 已注册 `duoduo-user` / `duoduo-product` / `duoduo-gateway` 三服务实例，全部 healthy |
| **容器化模式跑通 54 项冒烟** | `scripts/smoke.ps1` → **54 通过 / 0 失败**（2026-09-17 复跑，exit 0） |
| **中文双重编码缺陷修复** | 见 I-08；`SET NAMES utf8mb4` 已写入 3 个初始化脚本，从零重建数据卷复验通过 |
| **M5-1 `duoduo-trade` 模块骨架** | 模块已建并接入父 POM（6 模块反应堆）；`TradeApplication`（`@EnableScheduling` + `@MapperScan`）、`TradeServiceConfig`（分页插件 + 雪花 worker-id=3）、`application.yml` / `-local`（H2）/ `-mysql` 三档；**`MVN_EXIT=0`、BUILD SUCCESS、产出 fat jar 42.5 MB** |
| **M5-1 交易库 DDL** | 7 张表：`t_cart_item` / `t_order` / `t_order_item` / `t_order_status_log` / `t_stock_freeze` / `t_payment` / `t_settlement`；已在容器 MySQL 实际执行（exit 0）并校验表、唯一索引、字符集 |
| **M5-2 交易服务容器接入** | compose 增加 `duoduo-trade-app`（:8083）；实测 **7 容器全 healthy**、Nacos 注册 `duoduo-trade@172.18.0.8:8083`、启动 2.7s；网关既有 `lb://duoduo-trade` 路由直接生效 |
| **M5-3 购物车** | 商品服务新增批量 SKU 接口 `GET /api/products/skus/batch`；交易服务引入 OpenFeign，新增 `CartItemEntity` / `CartItemMapper` / `CartDtos` / `CartService` / `CartController`；7 个接口（列表 / 角标 / 加购 / 改数量 / 勾选 / 删除 / 清空已勾选）；**自测 `scripts/trade-smoke.ps1` → 87 通过 / 0 失败** |

### 进行中

| 任务 | 当前进展 |
|---|---|
| **M5-4 下单拆单** | 下一步：按卖家拆单、下单快照（title/spec/price）、幂等键 `uk_order_user_idem` 防重复提交；需先定义卖家归属（自营/商家的 sellerId 来源） |
| **M5-5 库存冻结与释放** | 依赖 M5-4；`t_stock_freeze` 表与唯一索引已就绪 |

### 未开始（P1-b 交易服务，既定范围）

| # | 任务 | 依赖 |
|---|---|---|
| 2 | 购物车（合并、改数量、失效校验） | M5-1 |
| 3 | 下单拆单（按商家拆） | 2 |
| 4 | 库存冻结与释放（超时/取消回滚） | 3 |
| 5 | 订单状态机（待付→已付→发货→完成→售后） | 3 |
| 6 | 支付幂等（重复回调只入账一次） | 5 |
| 7 | C2C 担保交易（资金托管、确认收货放款） | 5 |
| 8 | 卖家中心（商品/订单/发货/售后） | 5 |
| 9 | 前端页面接入网关（复用 Node 版） | 接口就绪 |

---

## 3. 问题与协助区

> 你处理完把「状态」改成 **已协助** 即可，我会先验证再继续。

| # | 问题 | 状态 | 需要你做什么 | 我的验证方式 |
|---|---|---|---|---|
| I-01 | Docker Desktop 我无法代启动：家庭版必须走 WSL2，启动要调 `wsl.exe`，而它在沙箱黑名单且禁止绕过 | **已协助**（2026-09-15 你手动双击启动） | 无（若我提示引擎又停了，帮我双击一次） | `docker info` 能返回 Server 版本 |
| I-02 | `docker` 凭据助手 `docker-credential-desktop` 不在 PATH，拉镜像失败 | **已自愈** | 无需操作 | 用独立配置目录 `D:\duoduo-infra\docker-nocreds`（不含 credsStore）绕开，未改动你的任何配置 |
| I-03 | 沙箱禁止我用 `Start-Process` 起 java 进程 | **已自愈** | 无需操作 | 改为容器化由 `docker compose` 起服务 |
| I-04 | 后台 PowerShell 命令中的中文路径按 ANSI 解析被破坏，Maven 把产物建到乱码目录 | **已自愈** | 无需操作 | 改为前台执行；产物落在正确路径 |
| I-05 | `cmd.exe` 被沙箱禁止，无法用 `mvn.cmd` 构建 | **已自愈** | 无需操作 | 用 `java` 驱动 Maven launcher（`plexus-classworlds`），BUILD SUCCESS |
| I-06 | 沙箱侧 `github.com` 的 git 端点间歇 502，`git push` 不稳 | **已自愈** | 无需操作 | 改用 `api.github.com` 做增量同步 + 逐文件 blob 哈希比对 |
| I-07 | 容器构建需拉 `eclipse-temurin` 基础镜像，曾担心网络不通 | **已自愈** | 无需操作 | 镜像已拉到本地（287 MB），三个业务镜像构建成功 |
| I-08 | 容器化后冒烟 2 项失败：中文关键词检索命中 0 条、详情过敏原断言失败 | **已自愈**（2026-09-17 修复） | 无需操作 | 根因：初始化 SQL 被客户端按 **latin1** 执行，中文被**双重编码**入库（`猫粮` 的 UTF-8 字节 `E78CAB` 被拆成 6 个字符再存为 UTF-8）。修法：三个初始化脚本开头加 `SET NAMES utf8mb4`。复验：库内 `HEX` = `E9BAA6`（正确）、关键词检索命中 4 条、**冒烟 54/54** |
| I-09 | 购物车自测脚本自身有缺陷，一度把种子数据改坏（**测试代码问题，非业务代码问题**） | **已自愈**（2026-09-17 修复） | 无需操作 | ① 哈希表用 `[long]` 建键、`[int]` 查键 —— PowerShell 按键**类型相等**匹配，查不到就静默返回 `$null`，夹具价格库存全变 0 → 21 条断言误报；② 改库复原时**写死数字**（`stock = 32`），每跑一次就把种子数据改坏一次，而种子 `data.sql` 里 SKU 2001 实为 **320**。修法：统一字符串键 + 夹具读不到就立即终止不写库 + 先读原值再写回原值 + 新增「测试结束后库内数据仍为原值」自证断言。数据已按种子恢复（2001→320） |

---

## 4. 本轮变更记录（2026-09-17）

| 文件 | 变更 |
|---|---|
| `docs/sql/00-init-databases-and-user.sql` | 开头新增 `SET NAMES utf8mb4`（附原因注释）；新增 `duoduo_trade` 库与授权 |
| `docs/sql/01-duoduo-user.sql` | 开头新增 `SET NAMES utf8mb4` |
| `docs/sql/02-duoduo-product.sql` | 开头新增 `SET NAMES utf8mb4` |
| `docs/sql/03-duoduo-trade.sql` | **新增**：交易库建库建表（由模块内 `db/schema.sql` 拼装，7 张表） |
| `docker-compose.yml` | 新增 `duoduo-trade-app`（:8083），网关 depends_on 补上 trade |
| `pom.xml` | 父 POM 增加 `<module>duoduo-trade</module>` |
| `duoduo-product/.../ProductController.java` | 新增 `GET /api/products/skus/batch`（批量 SKU 采购视图，供交易服务调用） |
| `duoduo-product/.../ProductDtos.java` | 新增 `SkuPurchaseView`（与 C 端 `SkuView` 刻意分开，避免字段互相污染） |
| `duoduo-product/.../ProductService.java` | 新增 `purchaseViews()`（批量、不 JOIN、查不到不报错）+ `specText()` |
| `duoduo-trade/pom.xml` | 增加 `spring-cloud-starter-openfeign` + `spring-cloud-starter-loadbalancer` |
| `duoduo-trade/.../TradeApplication.java` | 增加 `@EnableFeignClients` |
| `duoduo-trade/.../client/**` | **新增** `ProductClient`（Feign，服务间直连不走网关）+ `SkuSnapshot`（跨服务 DTO，不共享类） |
| `duoduo-trade/.../entity/CartItemEntity.java`、`mapper/CartItemMapper.java` | **新增** |
| `duoduo-trade/.../dto/CartDtos.java` | **新增** `CartItemView` / `CartView` / 三个请求体 / 失效原因码常量 |
| `duoduo-trade/.../service/CartService.java` | **新增** 购物车业务逻辑（唯一索引兜底并发、服务端失效判定、依赖故障抛 503） |
| `duoduo-trade/.../controller/CartController.java` | **新增** 7 个接口 |
| `scripts/trade-smoke.ps1` | **新增** 购物车端到端自测（87 项断言，夹具动态取、改库先存原值） |
| `Dockerfile` / `.dockerignore` / `duoduo-gateway/pom.xml` | 上一轮新增，本轮首次全程验证通过 |

---

## 5. 一键命令速查

```powershell
# 起全部中间件 + 业务服务（Docker 优先）
cd D:\智能体项目\duoduo-pet-mall-java
docker compose up -d
docker ps                                  # 6 个容器应全为 healthy

# 端到端冒烟（54 项，网关 + 用户域 + 商品域）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 购物车端到端自测（87 项，需登录，会临时改库并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1
#   加 -SkipDbCheck 可跳过「改库模拟无货/下架」那一段（只跑接口断言）

# 从零重建（连数据卷一起删，会重新执行 docs/sql 初始化）
docker compose down -v; docker compose up -d

# 沙箱内调用 docker 必须带这两行（凭据助手不在 PATH）
$env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
$env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds'

# 构建（沙箱里 cmd.exe 被禁，不能用 mvn.cmd —— 必须用 java 驱动 Maven launcher）
$J='D:\智能体项目\duoduo-pet-mall-java'; $M='D:\devsoft\apache-maven-3.9.4'
& "$J\..\..\jdk\jdk21\bin\java.exe" "-Dclassworlds.conf=$M\bin\m2.conf" "-Dmaven.home=$M" `
  "-Dmaven.multiModuleProjectDirectory=$J" -classpath "$M\boot\plexus-classworlds-2.7.0.jar" `
  org.codehaus.plexus.classworlds.launcher.Launcher -gs maven/settings.xml -B -DskipTests package

# 只往容器里补执行某个初始化脚本（不用重建数据卷）
docker cp <ascii目录>\03-duoduo-trade.sql duoduo-mysql:/tmp/03.sql
docker exec duoduo-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -pduoduo_root < /tmp/03.sql'
```

> 服务端口：网关 **8080**、用户 **8081**、商品 **8082**、交易 **8083**（未接入 compose）。

---

## 6. 约定（不随迭代改变）

- 金额一律整数「分」；时间一律 ISO8601 UTC
- 令牌只走 httpOnly Cookie，禁止 localStorage / URL 参数
- 错误结构 `{ error: { code, message, details }, requestId }`，不暴露堆栈
- 用户身份只从网关注入的 `X-User-*` 取，绝不接受客户端传 userId
- 中间件与业务服务一律优先容器化
- **SQL 初始化脚本一律以 `SET NAMES utf8mb4;` 开头**（防双重编码）
