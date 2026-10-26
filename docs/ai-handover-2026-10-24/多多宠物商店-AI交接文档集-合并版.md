# 多多宠物商店 · AI 交接文档集（合并版，2026-10-24 · 修订）
> 由 16 篇文档合并；本次修订：09 扩为全项目待办总表，新增 15 方案原文、16 方案索引。


---

# 00 · 接手索引（30 分钟接管本项目）

> 你是接手的 AI。目标：**不依赖任何真人补充**，独立在本项目继续开发、测试、部署。
> 读法：先读本篇 → 按 §2 顺序读 01~04（约 20 分钟）→ 跑 §3 自检 → 再动手。
> 若只有 10 分钟：读本篇 §1/§3/§6，然后直接看 `docs/PLAN.md` 顶部最新一轮与《09-待办任务与后续计划》。

---

## 1. 一句话与当前基线

**多多宠物商店**：C2C 宠物电商（自营 + 第三方商家 + 用户闲置），Java 21 微服务后端 + React 三站点前端 + 微信小程序；
本地 Docker Compose 运行；支付为**沙箱渠道**（流程真实、资金不真实）。

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/1694798740/duoduo-pet-mall-java`（私有，main 分支） |
| 本地路径 | `D:\智能体项目\duoduo-pet-mall-java` |
| 工作区（文档/记忆） | `C:\Users\16947\WorkBuddy\2026-09-18-17-36-12` |
| 最新提交（交接基线） | `8e29571` — R94 商品管理重构 P0 |
| 技术底座 | Java 21 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1 / MyBatis-Plus 3.5.5 / React 18 + TS strict + Vite 5 |
| 数据 | MySQL 8（6 库）+ Redis 7 + Nacos 2.3.2；ES 与 RocketMQ 为可选 profile |
| 当前阶段 | 商品管理重构 P0 已交付（P0-1~P0-4），P0-5 并发保护与 P1 结构化待推进 |

---

## 2. 必读顺序（都在本文件夹）

| 顺序 | 文档 | 读完你能做什么 |
|---|---|---|
| 1 | `01-项目介绍与业务模型.md` | 说清业务、角色、交易闭环、资金规则 |
| 2 | `02-架构与技术栈.md` | 知道 8 个服务各自负责什么、跨服务怎么调 |
| 3 | `03-目录结构与代码地图.md` | 知道改某个功能该动哪些文件 |
| 4 | `04-环境搭建与启动运维.md` | 把系统跑起来、部署、排障 |
| 5 | `05-开发规范与工作流.md` | 按项目规矩写代码、提测、提交 |
| 6 | `06-功能清单与完成度.md` | 知道哪些已做、哪些没做 |
| 7 | `07-数据模型与接口契约.md` | 加字段、加接口、联调 |
| 8 | `08-测试体系与验收方法.md` | 提交前该跑什么、怎么判绿 |
| 9 | `09-待办任务与后续计划.md` | **全项目**近两周遗漏与待办（11 类、60+ 项，含验收） |
| 10 | `10-最终预期效果.md` | 什么算“做完” |
| 11 | `11-踩坑手册与铁律.md` | 报错时先查这里 |
| 12 | `12-AI接管操作手册.md` | 逐条照做的执行流程 + 交接提示词 |
| 13 | `13-账号与环境清单（脱敏）.md` | 测试账号、凭据位置（不含密钥） |
| 14 | `15-商品管理重构方案.md` | 商品域重构的调研、审计与分期（方案原文） |
| 15 | `16-相关功能改造方案索引.md` | 近两周所有方案/交付说明的索引与未完成项 |
| — | `skills/duoduo-takeover/SKILL.md` | 可安装为 WorkBuddy 技能，自动加载接管纪律 |

---

## 3. 三分钟自检（开工前必须全绿）

```bash
cd /d/智能体项目/duoduo-pet-mall-java

# 1) 容器：默认 15 个；含 ES/MQ 的完整环境 17~18 个
docker compose ps

# 2) 编译（本机专用姿势）
export JAVA_HOME="/c/Program Files/Java/latest/jdk-21/jdk21"
/d/devsoft/apache-maven-3.9.4/bin/mvn -o -q -pl duoduo-product -am -DskipTests package

# 3) 前端类型检查（三应用）
cd frontend && npm run typecheck

# 4) 影响面最小的一套冒烟（例）
cd .. && NO_PROXY="*" node scripts/product-manage-r93-smoke.mjs
```

任一项不通过：**先修基线，不要开始写新功能**（基线不绿时任何改动都无法被证明有效）。

端口速记：网关 8080 · 用户 8081 · 商品 8082 · 交易 8083 · 履约 8084 · 营销 8085 · AI 8086 · 社交 8087 ·
商城 8088 · 后台 8090（`admin.localhost`）· 商家 8091（`merchant.localhost`）。

---

## 4. 当前进度快照（2026-10-24）

| 域 | 状态 |
|---|---|
| 用户 / 商品 / 交易 / 履约 / 营销 / AI / 社交 | ✅ 全部可运行 |
| 买家 Web 商城（主交易闭环 + 分享圈 + 闲置 + 券 + AI 导购 + IM 客服） | ✅ |
| 商家端 Web（商品/订单/售后/结算/店员/客服/商品分析） | ✅ |
| 运营后台（报表/审核/订单干预/类目品牌/账号/审计/租户健康度） | ✅ |
| 微信小程序（33 页） | ✅ 代码就绪，**真机验收待用户** |
| 支付 | ✅ 沙箱；真实微信支付**待商户号** |
| 商品管理重构 | 🟨 P0 完成，P0-5 与 P1 待做（详见 09 篇） |

进度权威：`docs/PLAN.md` 顶部“最新一轮”；逐轮历史在 `项目简报.md`（工作区）。

---

## 5. 你绝不能做的 8 件事

1. 不要直接 `docker compose build` 前端镜像（会打旧产物）——必须 `npm run build` 或 `scripts/web-up.sh`。
2. 不要用浏览器原生弹窗（`window.confirm/prompt/alert`）——用 `useConfirm`。
3. 不要跨库 JOIN——跨域走 Feign + `X-Internal-Token`。
4. 不要用应用层“先查再插”做幂等——用唯一索引。
5. 不要接受请求体里的 `userId/sellerId`——归属只认登录态。
6. 不要把金额改成小数——全站整数分；时间用 UTC。
7. 不要修改历史测试断言来“让它变绿”。
8. 不要把真实密钥写进仓库或文档。

---

## 6. 下一步该做什么

按 `09-待办任务与后续计划.md` 的顺序：
**P0-5 并发乐观锁 → P1-1 审核历史 → P1-2 删除/回收站 → P1-3 类目属性 → P1-4 图片表 → P1-5 搜索同步 → P1-6 批量上下架**。
每项都带验收标准，做完立即更新 `docs/PLAN.md`、`README.md`、`docs/SELFTEST.md`、本文件夹对应条目与 `项目简报.md`。

---

## 7. 分发位置（本次同步结果）

| 渠道 | 位置 | 状态 |
|---|---|---|
| GitHub | `docs/ai-handover-2026-10-24/` | ✅ 已推送（含 15/16 与新版 09） |
| 飞书 | 文件夹「多多宠物商店 · AI交接文档集（2026-10-24）」 | ✅ 已上传（本次补 15/16、替换 09） |
| ima 知识库 | 「多多宠物商店」→ 合并版 | ✅ 已入库（本次重传） |
| 腾讯文档 | 文件夹「多多宠物商店 · AI交接文档集（2026-10-24）」（`LeWFJXQHjkfr`） | ✅ 已上传（本次补 15/16、替换 09） |

云端同步的 token / media_id 等见同目录 `manifest.json`。


---

# 01 · 项目介绍与业务模型

## 1. 项目是什么

**多多宠物商店**是一套宠物垂直电商：平台自营 + 第三方商家入驻 + 用户闲置（C2C）三条供给线并存。
买家可以逛、搜、加购、下单、支付、评价、售后；商家可以管商品、发货、处理售后、对账；
平台运营负责审核、干预订单、看报表与资金对账。

## 2. 角色与权限

| 角色 | 入口 | 能做什么 | 权限实现 |
|---|---|---|---|
| 买家 USER | 商城 8088 / 小程序 | 浏览、搜索、购物车、订单、支付、评价、售后、优惠券、收藏、足迹、分享圈、闲置、AI 导购、客服咨询 | JWT + 网关校验 |
| 商家 SELLER（店主/店员） | 商家端 8091 | 商品管理、订单发货、售后、结算、店员、客服、商品分析 | `role=SELLER` + `t_merchant` 归属；店员以店主主体行动 |
| 运营 OPERATOR | 后台 8090 | 商品审核、订单干预、售后仲裁、类目品牌、账号、审计日志、租户健康度 | `OperatorGuard.requireOperator()` |
| 管理员 ADMIN | 后台 8090 | 运营权限 + 改角色（仅 ADMIN） | `role=ADMIN` + 审计留痕 |
| 平台自营 | `sellerId=0` | 平台自己发布的商品，免审；订单归属自营 | `supply_type='self'` |

**三条硬规则**：
1. 归属只认登录态，任何写接口都不接受请求体里的 `userId/sellerId`；
2. 店员与店主看到同一批商品/订单（以店主主体行动），但店员不能管理店员、不能进结算；
3. 运营代第三方店铺做动作（编辑/发货/下架）必须**先开工单**（E23），否则 403。

## 3. 核心业务闭环

```
浏览/搜索 → 商品详情 → 加购/直购 → 结算（地址/优惠券/运费）
  → 下单（拆单 + 冻结库存 + 锁券）
  → 支付（沙箱二维码/收银台，回调验签 + 幂等入账）
  → 资金进平台托管（t_settlement）
  → 商家发货 → 买家确认收货（或超时自动确认）
  → 放款给卖家（扣 6% 佣金，自营不抽）+ 冻结库存转实扣
  → 评价（回写商品评分）→ 售后/退款（原路退回，券不退回）
```

**闲置（C2C）**：发布 → 审核（E14 状态机）→ 上架 → 买家咨询/想要 → 线下交易；与 SPU 商品是两套表（`t_listing`）。

## 4. 资金与结算口径

| 规则 | 值 | 代码位置 |
|---|---|---|
| 佣金 | 6%，平台自营不抽 | `duoduo-trade` 结算服务 |
| 放款时点 | 确认收货后 T+7 可提现 | 同上 |
| 金额单位 | 整数分（`long`） | 全站 |
| 退款 | 退实付金额；优惠券不退回（v1） | `SellerService.refund` |
| 支付 | 沙箱渠道（预下单/二维码/回调/验签与真实一致） | `duoduo-trade` 支付模块 |

## 5. 商品与审核规则（当前有效）

- 商品模型：SPU（`t_spu`）+ SKU（`t_sku`）；`minPrice/maxPrice` 是 SKU 冗余列，写入方负责同步。
- 可见性：`status=ON_SALE` **且** `auditStatus=APPROVED`，未过审统一 404。
- 自动审核四条规则：前置许可类目关键词 / 联系方式与站外链接 / 低于同类均价 10% / 商家风险标记。
- 违禁词：配置化硬拒绝（命中即 400），词表见 `duoduo-product/src/main/resources/application.yml`。
- **曾被驳回**：`rejected_at` 持久化标记；只要非空，内容变更一律转人工，人工通过才清空。
- **改价/改库存不重审**（2026-10-24 起），内容变更重审；价格区间随 SKU 自动重算。
- 审核台必须展示买家可见的全部内容（图集/介绍/富文本/规格/包装）。

## 6. 聊天与客服（社交域）

- 两条通道：**客服会话**（买家 ↔ 店铺客服，按 `sellerId` 分会话）与**私信**（用户 ↔ 用户）。
- 实时：原生 WebSocket 纯信号推送（`MESSAGE/READ/RECALL/TYPING`），HTTP 拉取兜底。
- 消息能力：文本/图片/视频/语音/表情/贴纸/商品卡/订单卡；撤回（120s，双方不可见）、引用、单向删除（仅本人不可见）、多选删除。
- 客服会话：买家可结束咨询；商家可筛选待回复/已处理/已关闭；30 天清理（先删对象存储再删库）。

## 7. 运营后台能力

商品审核台（含 SLA）、类目品牌维护、订单干预（取消/退款/代发货）、售后仲裁、账号与角色、审计日志、租户健康度、平台代操作工单（E23）。

## 8. 商业约束（必须知道）

| 约束 | 影响 | 现状 |
|---|---|---|
| 无营业执照/商户号 | 不能接真实微信支付 | 沙箱支付；用户待办证 |
| 小程序 AppID/隐私审核 | 真机与上线受限 | 代码就绪，待用户在微信后台配置 |
| 本机单盘 | 备份与数据库同盘有风险 | 备份脚本就绪，盘位待用户指定 |
| 无真实 WMS/物流 | 轨迹为模拟/录入 | A2 物流轨迹已做，数据源为模拟 |

> 这些不是代码缺陷，而是外部条件；接手时不要试图“用代码绕过”，要按 09 篇的“等外部条件”分类处理。


---

# 02 · 架构与技术栈

## 1. 技术栈（真实版本）

| 层 | 选型 |
|---|---|
| 语言/运行时 | Java 21（`pom.xml` + 镜像 temurin:21） |
| 框架 | Spring Boot 3.2.5、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0 |
| ORM | MyBatis-Plus 3.5.5（`id-type: input`，雪花 ID 显式赋值） |
| 数据库 | MySQL 8.0.36（6 个业务库） |
| 缓存/消息 | Redis 7.2（缓存、WS 票据、Pub/Sub 扇出）；RocketMQ 可选（profile=mq） |
| 注册中心 | Nacos 2.3.2（8848/9848） |
| 检索 | Elasticsearch 7.x（可选 profile=search；不可用自动降级 MySQL） |
| 前端 | React 18、TypeScript 5.6 strict、Vite 5、React Router 6、ECharts 6 |
| 小程序 | 原生微信小程序（33 页） |
| 测试 | JUnit5 + Mockito；接口冒烟 Node/Python；真实浏览器 e2e = playwright-core |

## 2. 服务拓扑

```
浏览器 / 小程序
   │
   ▼
duoduo-gateway :8080 ── JWT 校验 · 白名单 · 身份头注入 · CORS · WS 票据预检
   ├─ user        :8081  账号/令牌/宠物档案/商家入驻/内部昵称
   ├─ product     :8082  类目品牌/SPU-SKU/库存/闲置/商家商品/审核
   ├─ trade       :8083  购物车/订单/支付/托管/结算/卖家中心
   ├─ fulfilment  :8084  评价/售后仲裁/物流轨迹/信用分
   ├─ marketing   :8085  优惠券
   ├─ ai          :8086  AI 导购（无状态）
   └─ social      :8087  客服会话/私信/通知/分享圈
        │
        └── MySQL :3306（6 库） · Redis :6379 · Nacos :8848 · ES :9200(可选) · RocketMQ(可选)
```

三个前端站点（nginx 托管构建产物，同源反代 `/api`、`/uploads`、`/ws`）：

| 站点 | 端口 | 入口文件 | 域名 |
|---|---|---|---|
| 商城 | 8088 | `index.html` | `localhost:8088` |
| 运营后台 | 8090 | `admin.html` | `admin.localhost:8090` |
| 商家端 | 8091 | `merchant.html` | `merchant.localhost:8091` |

## 3. 模块与职责

| 模块 | 端口 | 职责 | 核心表 |
|---|---|---|---|
| `duoduo-common` | — | `ApiResponse`/`PageResult`/`ErrorCode`/`BizException`/雪花ID/`UserContext`/JWT/`OperatorGuard` | — |
| `duoduo-common-web` | — | 全局异常、请求 ID、身份透传、`LongToStringJacksonModule`（ID 序列化） | — |
| `duoduo-gateway` | 8080 | 路由、JWT、白名单、身份头、CORS、WS 票据 | — |
| `duoduo-user` | 8081 | 注册登录、刷新令牌、实名、商家入驻、宠物档案、地址簿、内部昵称 | `t_user`、`t_merchant`、`t_address`、`t_pet` |
| `duoduo-product` | 8082 | 类目品牌、SPU/SKU、库存主数据、闲置、商品审核、搜索、首页聚合 | `t_spu`、`t_sku`、`t_listing`、`t_category` |
| `duoduo-trade` | 8083 | 购物车、订单、支付、托管结算、卖家中心、后台干预 | `t_order`、`t_order_item`、`t_settlement`、`t_payment` |
| `duoduo-fulfilment` | 8084 | 评价、售后仲裁、物流轨迹、信用分 | `t_review`、`t_after_sale`、`t_logistics` |
| `duoduo-marketing` | 8085 | 优惠券模板/领取/锁·核·退/补偿 | `t_coupon_template`、`t_coupon` |
| `duoduo-ai` | 8086 | AI 导购（mock/llm 双形态，无状态） | 无 |
| `duoduo-social` | 8087 | 客服会话、私信、通知、分享圈、实时 WS | `t_support_*`、`t_social_message`、`t_social_notice` |

**雪花 worker-id 分配（新增服务必须取未占用的）**：user=1、product=2、trade=3、fulfilment=4、marketing=5、social=6。

## 4. 跨服务调用（原则）

| 调用 | 方式 | 保护 |
|---|---|---|
| trade → product | Feign `lb://duoduo-product` | `X-Internal-Token`（签名，非明文密钥） |
| fulfilment → trade | 同上 | 同上 |
| social → user/trade/product | 同上 | 同上 |
| 前端 → 后端 | 只经网关 8080（nginx 同源反代） | JWT Cookie/Header |

**两条纪律**：
1. 内部接口字段按“调用方真正需要”裁剪（例如订单事实接口不含收货人姓名/电话）；
2. 批量而非逐条（避免跨服务 N+1）。

## 5. 横切机制

| 机制 | 说明 | 位置 |
|---|---|---|
| 统一响应 | `{code,message,data,requestId,timestamp}` | `duoduo-common` |
| 错误码 | 400 参数、401 未登录、403 越权、404 不存在、409 冲突、422 业务规则、429 限流、5xx 系统 | `ErrorCode` |
| ID 序列化 | 雪花 ID 一律字符串下发（防 JS 精度丢失）；金额/计数 `long` 在白名单内保持数字 | `LongToStringJacksonModule` |
| 租户过滤 | 三张租户表自动追加条件（多租户拦截器） | `ProductServiceConfig` |
| 限流 | 网关层按 IP/用户；秒杀走 Redis 预扣 | gateway / marketing |
| 审计 | 运营敏感操作写 `t_audit_log` | `@Audited` |

## 6. 部署形态

- 后端：每个服务一个镜像（共用根 `Dockerfile`，`temurin:21-jre-alpine`，非 root）。
- 前端：`frontend/Dockerfile` **只 COPY 宿主 dist**，不在容器内构建 —— 这是最容易踩的坑（见 11 篇）。
- ES / RocketMQ 用 compose profile 控制；本地默认不依赖它们（ES 关闭时搜索降级 MySQL）。

## 7. 架构不变式（改动前必读）

1. 公开可见性只有一个口径：`status=ON_SALE && auditStatus=APPROVED`。
2. 库存可售 = `stock - frozen_stock`，扣减一律原子条件更新。
3. 幂等一律唯一索引 + 撞键翻译（409），不用分布式锁。
4. 订单金额是快照：`t_order_item.price/amount` 下单即固化，改价不影响历史。
5. 审核状态与交易状态正交；`audit_status` 只能由服务端写。
6. 内容字段与运营字段分离：价格/库存不重审，内容重审。
7. 任何跨域读都必须经过对端服务，禁止跨库 JOIN。


---

# 03 · 目录结构与代码地图

## 1. 仓库总览

```
duoduo-pet-mall-java/
├── pom.xml                     聚合 POM（8 服务 + 2 公共模块）
├── docker-compose.yml          容器编排（默认 15 服务；ES/MQ 为 profile 可选）
├── Dockerfile                  后端服务共用镜像
├── AGENTS.md                   旧版 AI 入口（部分事实过时，以本交接包为准）
├── README.md                   变更日志（每轮必须更新）
├── docs/
│   ├── PLAN.md                 ★ 进度权威（最新一轮在最上面）
│   ├── SELFTEST.md             ★ 自测手册（每套脚本的用法与项数）
│   ├── ARCHITECTURE.md         早期蓝图（部分未落地，现状以本包 02 为准）
│   ├── CODE-WIKI.md            代码百科（模块/流程/任务清单）
│   ├── OPS.md                  运维手册（部署/入口开关/备份恢复）
│   ├── sql/                    初始化 SQL + incremental/ 增量脚本
│   ├── handover/               2026-09 旧交接包（部分过时）
│   └── agent/                  2026-09 旧 AI 接管包（部分过时）
├── scripts/                    冒烟与工具（99 个；e2e 40 个）
├── frontend/                   React monorepo（three apps + two packages）
├── miniprogram/                微信小程序（33 页）
└── duoduo-*/                   8 个服务 + 2 个公共模块
```

## 2. 后端：按任务定位

| 你要做的事 | 先看这里 | 关键文件 |
|---|---|---|
| 加/改接口 | 对应域 `controller/` | `SellerProductController`、`ProductController`、`OrderController`… |
| 改业务规则 | 对应域 `service/` | `SellerProductService`（商品与审核）、`ProductService`（买家读）、`OrderService`（订单） |
| 改表/加列 | `docs/sql/incremental/` + `entity/` | 增量脚本按序号递增（当前到 `46`） |
| 改鉴权/白名单 | `duoduo-gateway` | `GatewaySecurityProperties`、`application.yml` |
| 改跨服务调用 | `client/`（Feign） | `MembershipClient`、`ProductClient`、`OrderFactsClient` |
| 改库存 | `duoduo-product` | `StockService`（冻结/释放/扣减/回补） |
| 改支付/结算 | `duoduo-trade` | `PaymentService`、`SettlementService`、`SellerService` |
| 改聊天/通知 | `duoduo-social` | `support/service/SupportService`、`service/MessageService`、`realtime/` |
| 改评价/售后/物流 | `duoduo-fulfilment` | `ReviewService`、`AfterSaleService`、`LogisticsService` |
| 改券 | `duoduo-marketing` | `CouponService`、`CouponSweepJob` |

### 商品域文件地图（重构重点）

| 文件 | 作用 |
|---|---|
| `SellerProductController` | 卖家：列表/详情/发布/编辑/上下架/重提/**SKU 改价改库存** |
| `SellerProductService` | 商品写路径与审核状态机（create/update/audit/resubmit/rejectBySample/updateSkuPriceStock） |
| `ProductService` | 买家读路径（可见性、详情、搜索、首页、评分回写） |
| `AdminProductAuditController` | 运营审核：列表/详情/结论 |
| `support/SpuRiskJudge` | 自动审核四条规则（纯函数，可单测） |
| `support/BannedWordGuard` | 违禁词硬拒绝（配置化） |
| `support/ProductContent` | 富文本消毒（jsoup）+ KV 序列化/反序列化 |
| `entity/SpuEntity`、`SkuEntity` | 商品与规格（含审核列、rejected_at、内容字段） |

## 3. 前端：按任务定位

```
frontend/
├── apps/
│   ├── mall/        商城（买家）  → /product/:id、/cart、/orders、/support、/ai …
│   ├── admin/       运营后台      → /admin/product-audits、/admin/orders …
│   └── merchant/    商家端        → /seller/products、/seller/orders、/seller/product-insights …
├── packages/
│   ├── api-sdk/     端点与类型（src/endpoints.ts、src/types.ts）
│   └── shared/      复用组件与样式（components/*、styles.css、store/*）
└── Dockerfile       只 COPY apps/<APP>/dist（★ 必须先在宿主构建）
```

| 你要改 | 文件 |
|---|---|
| 商城商品详情 | `apps/mall/src/pages/ProductDetail.tsx` |
| 商家商品管理/编辑 | `apps/merchant/src/pages/seller/Products.tsx` + `ProductPublishModal.tsx` |
| 商品分析页 | `apps/merchant/src/pages/seller/ProductInsights.tsx` |
| 审核台 | `apps/admin/src/pages/admin/ProductAudits.tsx` |
| 富文本编辑器 | `packages/shared/src/components/RichText.tsx` |
| 聊天/客服 UI | `packages/shared/src/components/chat-media.tsx` + 三端页面 |
| 接口与类型 | `packages/api-sdk/src/endpoints.ts`、`types.ts` |

## 4. 小程序

```
miniprogram/
├── app.json          tabBar 5 项（首页/分类/分享圈/购物车/我的）
├── pages/            33 页：index、category、product、cart、orders、support、chat、share…
└── utils/            api.js（请求封装）、realtime.js、media.js、images.js
```

规则：非 tab 页**不要**自绘导航栏；tabBar 最多 5 项；`switchTab` 只对 tab 页有效。

## 5. 脚本地图

| 类型 | 位置 | 用法 |
|---|---|---|
| 接口冒烟 | `scripts/*.mjs` / `*.py` / `*.ps1` | 见 `docs/SELFTEST.md` |
| 浏览器 e2e | `scripts/e2e/*.mjs` | `NO_PROXY="*" node scripts/e2e/xxx.mjs` |
| 一键全量冒烟 | `scripts/run-all-smoke.ps1` | PowerShell 运行 |
| 前端一键部署 | `scripts/web-up.sh` / `.ps1` | 构建 + 镜像 + 起容器 |
| 对账/守卫 | `scripts/check-*.mjs` | 例：库存冻结对账、主题、二维码 |

## 6. 文档权威顺序（冲突时按此裁决）

1. 代码与数据库实物；
2. `docs/PLAN.md`（最新一轮）；
3. 本交接包（2026-10-24）；
4. `docs/SELFTEST.md`、`README.md`；
5. `docs/handover/`、`docs/agent/`（2026-09 历史，可能过时）。


---

# 04 · 环境搭建与启动运维

> 本机为 Windows 10 + Git Bash + Docker Desktop。以下命令全部按本机实际路径与已验证姿势书写。

## 1. 前置

| 依赖 | 本机路径 / 版本 | 说明 |
|---|---|---|
| JDK 21 | `C:\Program Files\Java\latest\jdk-21\jdk21` | 必须设 `JAVA_HOME`，否则 Maven 直接 127 |
| Maven 3.9.4 | `D:\devsoft\apache-maven-3.9.4` | 本项目用 `mvn -o`（离线）；本地仓库已备齐依赖 |
| Node 22 | `C:\Users\16947\.workbuddy\binaries\node\versions\22.22.2-6` | 前端构建 |
| Docker Desktop | 需**手动启动** | 沙箱无法代启 Docker |
| Git | 已配置 SSH key | push 走 `git@github.com` |

## 2. 起服务

```bash
cd /d/智能体项目/duoduo-pet-mall-java

# 默认环境（15 个容器）
docker compose up -d
docker compose ps          # 期望全部 healthy

# 需要 ES 检索（可选）
docker compose --profile search up -d

# 需要 RocketMQ（可选）
docker compose --profile mq up -d
```

容器名（排障用）：`duoduo-mysql`、`duoduo-redis`、`duoduo-nacos`、`duoduo-app-gateway`、
`duoduo-app-user/product/trade/fulfilment/marketing/ai/social`、`duoduo-app-web`、`duoduo-app-admin-web`、`duoduo-app-merchant-web`。

入口：
- 商城 `http://localhost:8088`
- 后台 `http://admin.localhost:8090`（**必须用域名**，Cookie 按主机隔离）
- 商家 `http://merchant.localhost:8091`

## 3. 构建后端

```bash
export JAVA_HOME="/c/Program Files/Java/latest/jdk-21/jdk21"

# 单模块（含依赖）编译打包
/d/devsoft/apache-maven-3.9.4/bin/mvn -o -q -pl duoduo-product -am -DskipTests package

# 全量
/d/devsoft/apache-maven-3.9.4/bin/mvn -o -q -DskipTests package
```

**改公共模块（duoduo-common / duoduo-common-web）后必须先 install**，否则下游服务读的是旧包：

```bash
mvn -o -q -pl duoduo-common,duoduo-common-web -am -DskipTests install
```

## 4. 构建与部署前端（★ 最容易出错）

镜像**只 COPY 宿主 dist，不在容器内构建**。所以顺序必须是：

```bash
cd frontend
npm run build:mall        # 或 build:admin / build:merchant / build（三个都构建）
# 或一键：bash ../scripts/web-up.sh

cd ..
docker compose build duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app
docker compose up -d --force-recreate duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app
```

**判据**：容器内 `assets/*.js` 的 mtime 必须晚于源码修改时间；且产物里能 grep 到本轮新增类名。
只跑 `npm run typecheck` 不产生 dist —— 这是 R92 事故的根因。

## 5. 部署单个后端服务

```bash
export JAVA_HOME="/c/Program Files/Java/latest/jdk-21/jdk21"
mvn -o -q -pl duoduo-product -am -DskipTests package
docker compose build duoduo-product-app
docker compose up -d --force-recreate duoduo-product-app
sleep 8 && docker logs --tail 50 duoduo-app-product
```

> 不要用 `up -d --build` 代替：本机实测偶发不重建镜像；`build` + `--force-recreate` 才可靠。

## 6. 数据库

| 库 | 内容 |
|---|---|
| `duoduo_user` | 用户、商家、地址、宠物、实名 |
| `duoduo_product` | 类目、品牌、SPU、SKU、闲置、审核抽样 |
| `duoduo_trade` | 购物车、订单、明细、支付、结算 |
| `duoduo_fulfilment` | 评价、售后、物流、信用 |
| `duoduo_marketing` | 券模板、券实例 |
| `duoduo_social` | 客服会话/消息、私信、通知、分享圈 |

本地连接：`docker exec -it duoduo-mysql mysql -uroot -pduoduo_root --default-character-set=utf8mb4`
（查询中文务必带 `--default-character-set=utf8mb4`）。

**增量脚本**：`docs/sql/incremental/NN-*.sql`，按序号执行；当前最新 `46`。
新环境先跑 `docs/sql/00~07`，再按序跑 incremental。

## 7. 环境变量（脱敏）

| 变量 | 本地默认 | 生产要求 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `duoduo_root` | 必须覆盖 |
| `JWT_SECRET` | 内置默认 | 必须覆盖（≥32 字节随机） |
| `INTERNAL_TOKEN` | 内置默认 | 必须覆盖 |
| `COS_*`（腾讯云对象存储） | 桶已配置，密钥在 compose env | 轮换 |
| `DASHSCOPE_API_KEY` | 未配置 | 未配置时 AI 走 mock/降级 |
| `DUODUO_SEARCH_ENABLED` | `false` | 开 ES 时设 true |
| `WECHAT_APPID/SECRET` | 空 | 用户提供 |

**禁止**把真实密钥写入仓库或文档；只写变量名与用途。

## 8. 备份与恢复

```bash
# 备份（脚本已就绪，盘位待用户指定）
powershell -File scripts/db-backup.ps1
# 演练/恢复见 docs/OPS.md §3
```

## 9. 常用排障命令

```bash
docker compose ps
docker logs --tail 200 duoduo-app-<svc>
docker exec duoduo-mysql mysql -uroot -pduoduo_root -e "SHOW TABLES FROM duoduo_product;"
curl -s --noproxy '*' http://localhost:8088/ | head
```

**必须加 `NO_PROXY="*"`**：本机系统代理 9894 会把 `*.localhost` 拦成 502。

## 10. CI

`.github/workflows/ci.yml`：push 后编译 + 冒烟子集（不跑浏览器 e2e）。本地提交前仍需自行跑受影响套件。


---

# 05 · 开发规范与工作流

> 这些规则不是“建议”，是项目已固化并被反复验证的纪律。违反会导致可复现的事故。

## 1. 分层与接口

| 层 | 规则 |
|---|---|
| Controller | 只做参数绑定、调 Service、包 `ApiResponse`；不写业务分支 |
| Service | 业务规则唯一归属；不依赖 `HttpServletRequest`；写操作先校验归属 |
| Mapper | 参数化查询；禁止字符串拼接 SQL；跨库禁止 JOIN |
| DTO | 用 record；新增字段要同步前端 `api-sdk/types.ts` |

**状态码语义**：400 参数错误 / 401 未登录 / 403 越权 / 404 不存在（含“不是你的”，防探测）/ 409 冲突 / 422 业务规则 / 429 限流。
**对外动词 ≠ 库内状态**（`APPROVE` → `APPROVED`），必须显式映射。

## 2. 数据与领域约定

1. **金额**：整数分（`long`），禁止浮点。
2. **时间**：UTC 存储与比较；展示层转 UTC+8。
3. **主键**：雪花 ID；`id-type: input` 的模块插入必须显式赋 id。
4. **ID 序列化**：雪花 ID 一律字符串下发；新增金额/计数 `long` 必须加入 `NUMERIC_LONG_FIELDS` 白名单，否则会被误转成字符串。
5. **幂等**：唯一索引 + 撞键翻译；不要“先查再插”。
6. **库存**：可售 = `stock - frozen_stock`；扣减用 `UPDATE ... WHERE stock - frozen_stock >= ?`。
7. **软删**：标记位 + 读取过滤；不要物理删除业务数据。

## 3. 审核与商品（当前规则）

- 内容字段（标题/副标题/主图/详情图/介绍/富文本/规格结构/类目/品牌）变更 → **重审**。
- 运营字段（价格/库存）变更 → **不重审**，但校验边界、同步 `minPrice/maxPrice`、写日志。
- 曾驳回标记（`rejected_at`）非空时，任何内容变更都必须转人工；人工通过才清空。
- 违禁词命中 → 400 硬拒绝；风险规则命中 → 转人工（PENDING）。
- 富文本必须服务端消毒（jsoup 白名单）后才落库；端侧渲染前再洗一遍。

## 4. 前端规范

| 项 | 规则 |
|---|---|
| 类型 | `tsc --noEmit` 必须 0 错误（三应用） |
| 弹窗 | **禁止** `window.confirm/prompt/alert`；用 `@duoduo/shared/components/confirm` 的 `useConfirm` |
| 样式 | 只用 CSS 变量（`--text/--surface/--border/--brand`），语义色随主题；不要硬编码颜色 |
| 主题 | 暗色必须验证：文字与背景对比度 ≥ 4.5:1 |
| 图片 | 走 `ImageUploader`；商品图集**主图恒第一** |
| 分页窗口 | 依赖“末条 id”而非 `items.length`（长度在窗口内恒定） |
| 构建 | 改完必须 `npm run build:<app>`；只 typecheck 不算完成 |

## 5. 小程序规范

- tabBar 最多 5 项；非 tab 页保留原生导航栏。
- `wx.switchTab` 只对 tab 页有效；移出 tabBar 时必须全局排查引用。
- 富文本用 `<rich-text nodes="{{html}}">`（服务端已消毒）。
- 真机验收是唯一能覆盖录音/相机/支付的地方，必须交给用户。

## 6. Git 与文档工作流（每轮固定动作）

1. 开发 + 自测；
2. 更新 `docs/PLAN.md`（最新一轮置顶）、`README.md`（同一提交）、`docs/SELFTEST.md`（新增脚本/项数）；
3. 更新工作区《项目简报.md》（新在上，大白话）与《待做清单/两周待办》状态；
4. 提交（提交信息写清根因与验证）+ 推送；
5. 如涉及外部交付，更新本交接包对应篇目。

**提交纪律**：一次提交只做一件事；提交信息包含“问题 → 根因 → 改动 → 验证”；不跳过 hooks；不 amend 已推送提交。

## 7. 完成定义（DoD）

- [ ] 接口/单测/UI 三层至少覆盖改动面；
- [ ] 正向 + 越权 + 边界三类断言齐全；
- [ ] 回库断言（不能只看接口 200）；
- [ ] 受影响既有套件回归不降级；
- [ ] 文档四处同步（PLAN/README/SELFTEST/简报）；
- [ ] 容器 healthy，产物为新（前端看 mtime 与 hash）。

## 8. 禁止清单

| 禁止 | 原因 |
|---|---|
| 直接 `docker compose build` 前端 | 会打旧 dist（R92 事故） |
| 用 `--no-verify` 跳 hooks | 绕过质量门 |
| 改历史断言使其变绿 | 掩盖回归 |
| 在请求体接收 `sellerId` | 越权风险 |
| 跨库 JOIN | 破坏域边界 |
| 把密钥写入仓库/文档 | 泄露 |
| 用原生弹窗 | 产品规范 |
| 为通过测试而放宽断言 | 假绿 |


---

# 06 · 功能清单与完成度（2026-10-24）

> 状态：✅ 已完成并验证 / 🟨 部分 / ⬜ 未做 / ⛔ 等外部条件。

## 1. 买家端（商城 Web + 小程序）

| 功能 | 状态 | 关键位置 | 备注 |
|---|---|---|---|
| 首页（轮播/分类/推荐/秒杀/猜你喜欢） | ✅ | `apps/mall/src/pages/Home.tsx`、`HomeController` | 首页卡片用 `mainImage` |
| 搜索（关键词/类目/排序/价格区间） | ✅ | `Search.tsx`、`ProductService.search` | ES 可选，关闭时 MySQL |
| 商品详情（图集/规格/介绍/富文本/规格参数/包装/评价） | ✅ | `ProductDetail.tsx` | 图集主图恒第一 |
| 购物车（按店铺分组/批量/全选） | ✅ | `Cart.tsx`、trade | 店名可跳店 |
| 结算（地址/优惠券/运费/拆单预览） | ✅ | `Checkout.tsx` | 券锁定失败 422 带人话 |
| 订单（列表/详情/取消/确认收货/物流） | ✅ | `Orders.tsx`、`OrderDetail.tsx` | 超时自动关单/确认 |
| 支付（沙箱二维码/收银台/回调） | ✅ | trade 支付模块 | 回调验签 + 幂等 |
| 评价（提交/追评/我的评价/晒图） | ✅ | `ReviewNew.tsx`、`MyReviews.tsx` | 一单一商品一评 |
| 售后（申请/进度/举证） | ✅ | `AfterSales.tsx`、`AfterSaleDetail.tsx` | 仲裁在后台 |
| 优惠券（领券中心/券包） | ✅ | `Coupons.tsx`、marketing | 锁·核·退 + 补偿扫描 |
| 地址簿 | ✅ | `Addresses.tsx`、user 域 | 下单快照 |
| 收藏/足迹/浏览历史 | ✅ | `Favorites.tsx`、`History.tsx` | 足迹按天分组 |
| 分享圈（发布/点赞/评论/举报） | ✅ | `ShareCircle.tsx`、social | 图片走 COS |
| 闲置（发布/列表/详情/我的/想要） | ✅ | `Listings.tsx`、`ListingDetail.tsx` | 独立审核状态机 |
| 关注/粉丝/用户主页 | ✅ | `Follow.tsx`、`UserProfile.tsx` | 互关才能私信 |
| 宠物档案 | ✅ | `Pets.tsx`、user 域 | 用于适配度提示 |
| AI 导购 | ✅ | `AiGuru.tsx`、`duoduo-ai` | 未配 Key 走 mock/降级 |
| 客服/私信（实时/图片/视频/语音/引用/撤回/单向删除） | ✅ | `Support.tsx`、`Chat.tsx`、social | WS 纯信号 + HTTP 兜底 |
| 通知中心（分类未读/全部已读） | ✅ | `Notifications.tsx` | 5 类分桶 |
| 登录/注册/微信登录/注销 | ✅ | `Login.tsx`、user 域 | 微信待 AppID |
| 暗夜模式 | ✅ | `ThemeToggle`、`store/theme` | 三态：日/夜/跟随系统 |

## 2. 商家端（8091）

| 功能 | 状态 | 位置 |
|---|---|---|
| 经营概览（KPI + 5 图表） | ✅ | `Dashboard.tsx` |
| 商品列表（审核四筛选/上下架/重提/编辑） | ✅ | `Products.tsx` |
| 商品发布（类目/品牌/主图/详情图/介绍/富文本/规格参数/包装/SKU） | ✅ | `ProductPublishModal.tsx` |
| 商品编辑（同上 + **价格与库存**） | ✅ | `Products.tsx` 编辑弹窗 |
| 商品分析（销量/成交额/退款/评分/好评率 + 评价弹窗） | ✅ | `ProductInsights.tsx` |
| 订单管理（列表/详情/发货/退款） | ✅ | `Orders.tsx`、`OrderDetail.tsx` |
| 售后处理 | ✅ | `AfterSales.tsx` |
| 结算对账（钱包四栏/账单/提现） | ✅ | `Settlements.tsx` |
| 店员管理（店主专属） | ✅ | `Staff.tsx` |
| 客服信息（四档筛选/回复/关闭） | ✅ | `SupportInbox.tsx` |

## 3. 运营后台（8090）

| 功能 | 状态 | 位置 |
|---|---|---|
| 经营概览与图表 | ✅ | `Dashboard.tsx` |
| 商品审核台（全量内容 + SLA） | ✅ | `ProductAudits.tsx`、`AuditSla.tsx` |
| 类目/品牌维护 | ✅ | `Catalog.tsx` |
| 订单干预（取消/退款/代发货） | ✅ | `Orders.tsx` |
| 售后仲裁 | ✅ | `AfterSales.tsx` |
| 账号与角色（仅 ADMIN 改角色） | ✅ | `Operators.tsx` |
| 审计日志 | ✅ | `AuditLogs.tsx` |
| 租户健康度 | ✅ | `TenantHealth.tsx` |
| 平台代操作工单 | ✅ | `ProxyOps` 相关页 |

## 4. 商品管理现状（重构进行中）

| 能力 | 状态 | 说明 |
|---|---|---|
| 发布/编辑内容（含富文本/规格/包装） | ✅ | 服务端消毒 |
| SKU 改价/改库存 | ✅ | 不重审、同步价格区间 |
| 审核防绕过（改两次） | ✅ | `rejected_at` 持久化 |
| 审核台看全内容 | ✅ | 图集/富文本/规格/包装 |
| 并发保护（乐观锁） | ⬜ | P0-5，见 09 篇 |
| 删除/回收站 | ⬜ | P1-2 |
| 审核历史/变更留痕 | ⬜ | P1-1 |
| 类目属性模板 | ⬜ | P1-3 |
| 图片表 | ⬜ | P1-4 |
| 搜索索引自动同步 | ⬜ | P1-5 |
| 批量上下架 | ⬜ | P1-6 |

## 5. 已知缺口的准确表述

- **评价审核流**：`t_review.audit_status` 恒 `APPROVED`，无审核入口。
- **商家回复评价**：已落地（A-16）：一条评价一次回复（重复 409、不可改删），仅 `APPROVED` 可回复；商家端「评价管理」三页签 + 站内回复弹窗，买家商品详情与「我的评价」展示回复；`SellerReviewView` 不含买家身份字段，匿名昵称脱敏；跨店 404、买家 403、回复过机审。
- **SKU 增删**：只能在发布时创建；编辑不能增删规格。
- **商品删除**：只有下架，无删除/回收站（P1-2）。
- **真实支付**：沙箱；等微信商户号。
- **小程序真机**：待用户按清单验收。


---

# 07 · 数据模型与接口契约

## 1. 库与表（核心）

### duoduo_product
| 表 | 关键列 | 说明 |
|---|---|---|
| `t_spu` | `id, title, subtitle, category_id, brand_id, supply_type, main_image, images, detail, intro, detail_html, spec_params, packaging, status, audit_status, audit_reason, audit_at, audit_by, audit_source, rejected_at, reject_count, sales_count, rating, rating_count, min_price, max_price, seller_id` | 商品主档；`images` 为逗号串（历史兼容） |
| `t_sku` | `id, spu_id, sku_code, spec_json, image, price, market_price, stock, frozen_stock, status` | 价格/库存唯一事实源；可售 = stock - frozen |
| `t_listing` | 闲置（C2C） | 独立审核状态机 |
| `t_category` / `t_brand` | 类目树 / 品牌 | 类目属性模板尚未建表（P1-3） |
| `t_audit_sample` | 自动放行抽查 | E28 |

### duoduo_trade
| 表 | 说明 |
|---|---|
| `t_order` | 订单主表（`order_no, user_id, seller_id, status, discount_amount, idempotent_key`） |
| `t_order_item` | 明细快照（`spu_id, sku_id, title, price, quantity, amount, refund_status`）——**改价不影响历史** |
| `t_payment` | 支付单（渠道幂等键） |
| `t_settlement` | 托管/放款/退回（CUSTODY/RELEASED/REFUNDED） |

### duoduo_fulfilment
`t_review`（评分 1-5、图片 JSON、追评、audit_status 恒 APPROVED）、`t_after_sale`、`t_logistics`。

### duoduo_marketing
`t_coupon_template`、`t_coupon`（`uk_coupon_user_tpl`）。

### duoduo_social
`t_support_session`、`t_support_message`、`t_social_message`、`t_social_notice`、`t_social_notice_read`、分享圈表。

### duoduo_user
`t_user`（`role: USER/SELLER/OPERATOR/ADMIN`）、`t_merchant`、`t_merchant_member`、`t_address`、`t_pet`。

## 2. 状态机

**商品交易态**：`ON_SALE` ⇄ `OFF_SHELF`
**商品审核态**：`PENDING → APPROVED | REJECTED`（人工/自动）；`REJECTED` 可重提；`rejected_at` 记录“曾被驳回”
**订单**：`PENDING_PAYMENT → PAID → SHIPPED → COMPLETED`；旁支 `CLOSED / REFUNDED`
**售后**：`APPLIED → APPROVED/REJECTED → REFUNDED/CLOSED`（仲裁）
**券**：`AVAILABLE → LOCKED → USED`；`LOCKED → AVAILABLE`（释放）；过期回收

## 3. 关键接口（节选）

### 商品（卖家 / 公共 / 运营）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/seller/products` | 列表（`keyword/auditStatus/page`） |
| GET | `/api/seller/products/{id}` | 编辑回填（含 skus/富文本/规格/包装） |
| POST | `/api/seller/products` | 发布（内容 + SKU） |
| PUT | `/api/seller/products/{id}` | 编辑内容（触发重审） |
| PUT | `/api/seller/products/{id}/skus/price-stock` | **改价/改库存（不重审）** |
| POST | `/api/seller/products/{id}/status` | 上下架（上架要求 APPROVED） |
| POST | `/api/seller/products/{id}/resubmit` | 重新提审 |
| GET | `/api/products/{id}` | 买家详情（未过审 404；图集主图第一） |
| GET | `/api/seller/product-stats` | 按商品经营统计（trade） |
| GET | `/api/admin/products` | 审核列表 |
| POST | `/api/admin/products/{id}/audit` | `{action: APPROVE|REJECT, reason}` |

### 交易
`POST /api/cart/items`、`POST /api/orders`、`POST /api/orders/{no}/pay`、`POST /api/payments/callback`、
`POST /api/orders/{no}/confirm`、`POST /api/seller/orders/{no}/ship`、`POST /api/seller/orders/{no}/refund`。

### 评价/售后/券
`POST /api/reviews`、`GET /api/reviews/product/{spuId}`、`GET /api/reviews/product/{spuId}/summary`、
`POST /api/aftersales`、`GET /api/coupons`、`POST /api/coupons/{id}/claim`。

### 社交
`GET/POST /api/support/messages`、`GET /api/seller/support/sessions`、`POST /api/support/messages/delete`、
`GET /api/socials/messages/conversations/{peerId}`、`POST /api/socials/messages/recall`、`GET /ws/**`（WS 票据鉴权）。

完整契约：`docs/openapi/*.json`（125+ 端点历史导出）+ `docs/agent/04-接口契约全集.md`；
**以代码注解为准**，新接口必须同步 `frontend/packages/api-sdk/src/endpoints.ts` 与 `types.ts`。

## 4. 约定与易错点

| 约定 | 细节 |
|---|---|
| 金额 | 全部整数分；前端显示 `fen2yuan` |
| 时间 | 后端 UTC；前端按 UTC+8 展示 |
| ID | 雪花 ID 字符串；**禁止 Number() 比较/排序**（精度丢失），需要排序用 `createdAt` |
| 分页 | `page` 从 1；`pageSize` 上限 50/100（按接口） |
| 错误体 | `{code, message, data, requestId, timestamp}`；`message` 是给人看的中文 |
| 内部接口 | `/api/**/internal/**`，`X-Internal-Token` 签名；外部直调 403 |
| 幂等键 | 下单/支付/评价/领券均有唯一索引 |
| 空值语义 | 更新接口“不传=不改、空串=清空”；服务端用显式 `set(col,null)` 落库 |

## 5. 新增字段的完整改动链（照此执行）

1. `docs/sql/incremental/NN-xxx.sql` 加列（幂等，可重复执行）；
2. `entity/XxxEntity` 加字段 + getter/setter；
3. `dto` 请求/视图 record 加字段（**同时补旧参数个数的兼容构造**，否则测试全炸）；
4. `service` 写入/读取/校验（可空字段要记账并显式 set null）；
5. `frontend/packages/api-sdk/types.ts` + `endpoints.ts`；
6. 前端页面/表单；
7. 单测 + 冒烟 + UI e2e；
8. 执行 SQL 到本地库，重建服务，验证回库。


---

# 08 · 测试体系与验收方法

## 1. 四层测试

| 层 | 工具 | 位置 | 用途 |
|---|---|---|---|
| 单元测试 | JUnit5 + Mockito | `duoduo-*/src/test/java` | 业务规则、纯函数（风险判定/消毒/KV） |
| 接口冒烟 | Node `fetch` / Python urllib / PowerShell | `scripts/*.mjs|.py|.ps1` | 真实容器上的端到端接口断言 |
| 浏览器 e2e | playwright-core（本机 Chrome） | `scripts/e2e/*.mjs` | 真实页面交互、暗色、布局、弹窗 |
| 守卫脚本 | Node | `scripts/check-*.mjs` | 库存对账、主题、二维码、冻结量 |

## 2. 当前关键基线（最近一次实测）

| 套件 | 命令 | 结果 |
|---|---|---|
| 商品管理冒烟 | `node scripts/product-manage-r93-smoke.mjs` | **34/0** |
| 商家端商品 UI | `node scripts/e2e/product-manage-r93-ui-check.mjs` | **22/0** |
| 审核台内容 UI | `node scripts/e2e/admin-audit-content-ui-check.mjs` | **11/0** |
| 商品模块单测 | `mvn -o -q -pl duoduo-product test` | **124/0** |
| 既有商品审核 e2e | `node scripts/e2e/product-audit-ui-check.mjs` | **36/0** |
| 聊天撤回/引用/语音 | `node scripts/chat-recall-quote-voice-smoke.mjs` | **25/0** |
| 消息单向删除/定位 | `node scripts/message-self-delete-around-smoke.mjs` | **30/0** |
| 实时推送 | `node scripts/realtime-smoke.mjs` | **17/0** |
| 客服生命周期/已读/分批 | `support-lifecycle-smoke.py` 等 | 32/0、24/0、22/0 |
| 表单 UI 回归 | `support-ux-ui-check.mjs` | **53/0** |

> 其余套件见 `docs/SELFTEST.md`（按脚本逐条列出前置、覆盖点、历史结果）。数字会随迭代变化，**以最近一次运行为准**，不要照抄成本轮结论。

## 3. 运行约定

1. **必须 `NO_PROXY="*"`**：否则 `*.localhost` 走系统代理 502。
2. **冒烟与 e2e 不并行**：共享夹具库，会互相污染。
3. 前端 e2e 打**容器产物**（8088/8091/8090），不是 dev server；改完前端必须先 build + 重建镜像。
4. 单测与冒烟分工：单测管“代码有没有发对语句”，冒烟管“语句到库里有没有生效”，两者都要。
5. 夹具账号用临时手机号（`197${时间戳后8位}`），测完不清理账号（只降权）。

## 4. 断言纪律（决定测试是否可信）

| 纪律 | 反例 |
|---|---|
| 断言测**原始报文**，不测前端加工值 | 只断言 toast 文案，不断言接口字段 |
| 状态码写具体数字 | “不是 200 就算拒绝”会把 500 当成拦截成功 |
| 正向 + 越权 + 边界三件套 | 只测正向，越权漏洞测不出 |
| 回库断言 | 接口 200 但库没改（`= null` 不写）必须被抓 |
| 反向验证 | 故意放开一条规则，确认恰好对应断言变红、其余仍绿、总数不减 |
| 不用 Number 处理雪花 ID | 精度丢失导致排序断言假红 |

## 5. 常见假红/假绿

| 现象 | 根因 | 处理 |
|---|---|---|
| 前端页面“改了没生效” | 只 `tsc` 未 `npm run build`，镜像装旧 dist | 见 11 篇第 1 条 |
| 录音条永远不出现 | headless 无麦克风 | 启动参数加 `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream` |
| 菜单被裁 | 绝对定位在 `overflow:auto` 容器内 | 组件已改 Portal + fixed，别改回去 |
| 暗色文字看不清 | 用了不存在的 `--text-1` | 只用 `--text/--text-2/--text-3` |
| 通知/公告类测试空列表 | 种子数据被清理 | 重放 `incremental/17` 种子 |
| 商品审核 e2e 失败 | 夹具未命中风险规则（自动放行） | 标题里放“微信abc12345”强制转人工 |
| ES 检索冒烟失败 | 未起 ES | `--profile search` 或按脚本设计跳过 |

## 6. 提交前门禁（DoD）

1. `mvn -o -q -pl <改动模块> test`（0 失败）；
2. `cd frontend && npm run typecheck`（0 错误）+ 受影响 app `npm run build:*`；
3. 受影响冒烟 + e2e 至少各一套（跨服务改动跑上下游两套）；
4. 前端重建镜像并 `docker compose ps` healthy；
5. 文档四处同步（PLAN/README/SELFTEST/简报）；
6. 提交信息写“问题→根因→改动→验证”。

## 7. 测试账号（本地固定夹具）

| 用途 | 手机号 | 密码 |
|---|---|---|
| 官方客服（自营客服账号） | 13900000002 | duoduo12345 |
| 运营（admin 入口） | 13684295254 | duoduo12345 |
| 第三方商家（店主，商品夹具） | 13600003333 | duoduo12345 |
| 商家店员夹具 | 13952746517 | duoduo12345 |

> 这些都是本地库的测试账号，不含任何云服务凭据。真实账号/密钥见 13 篇（只写位置，不写值）。


---

# 09 · 近两周功能遗漏与待办总表（2026-10-10 ~ 2026-10-24）

> **本文件是“这两周待办”的唯一权威清单**，覆盖商品、交易/支付、客服 IM、评价售后、营销、用户/商家、小程序、运营后台、基础设施、测试债、文档共 11 类。
> 商品管理重构只是其中一类，不再是全部。商品域的逐项执行明细另见《商品管理重构-两周待办-2026-10-24.md》。
>
> 状态：✅ 已完成 / 🟨 部分完成 / ⬜ 未开始 / ⛔ 等外部条件 / ❓ 待确认口径
> 优先级：P0 数据·资金·安全；P1 合规·核心体验；P2 效率；P3 可选
> 口径：不写工时；“本周/下周”只表示时间窗。

---

## 0. 为什么此前文档里看不到重构方案

两个原因，都是我的处理错误：

1. **交接文档集只做了“摘要”，没有把方案原文收进去**。《商品管理重构方案》《商品管理重构-两周待办》生成在工作区根目录，交接集的 09 篇只写了“见工作区某某文件”——而新的 AI 账号拿到交接集后，并不会自动去根目录找这两份文件，等于没交。
2. **我把“两周待办”错误地缩成了“商品管理待办”**。你原话是“近两周内所有相关功能的遗漏点与待办”，我却按“本轮正在做的模块”去写，导致客服、支付、小程序、基础设施等同期遗留被漏掉。

修正方式（本次已执行）：
- 新增 `15-商品管理重构方案.md`（方案原文入集）；
- 新增 `16-相关功能改造方案索引.md`（近两周所有方案/交付说明的索引与未完成项）；
- 用本文件**替换**交接集原来的 09 篇，范围扩到全项目；
- 四渠道（本地 / GitHub / 飞书 / 腾讯文档 / ima）同步更新。

---

## 1. 待办总表

### A. 商品域（17 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 / 依赖 |
|---|---|---|---|---|---|
| A-01 | 审核“改两次绕过” | 已用 `rejected_at` 持久化修复 | P0 | ✅ | 冒烟断言两次编辑仍 PENDING |
| A-02 | SKU 改价/改库存 | 新接口 + 商家端入口，不重审 | P0 | ✅ | 价格区间同步、审核态不变 |
| A-03 | 审核台看全内容 | 图集/介绍/富文本/规格/包装已渲染 | P0 | ✅ | e2e 11/0 |
| A-04 | 富文本详情 + 介绍 + 规格/包装 | 已上线，服务端 jsoup 消毒 | P1 | ✅ | 注入串被剥离 |
| A-05 | 详情首图 = 主图 | 服务端归一 + 端侧兜底，Web/小程序 | P1 | ✅ | `images[0]===main_image` |
| A-06 | 商品评价与经营分析 | 商家端「商品分析」页 + `/product-stats` | P1 | ✅ | 销量取自订单明细 |
| **A-07** | **编辑/审核并发保护（乐观锁）** | 实体无 version，整行覆盖可把 REJECTED 写回 APPROVED | **P0** | ⬜ | 旧版本提交返回 409；单测覆盖冲突 |
| **A-08** | **审核历史 + 变更留痕** | 只有当前四列，改判/多次驳回不可追溯 | **P1** | ⬜ | `t_spu_audit_log`；审核台可看历史 |
| **A-09** | **商品删除 / 回收站** | 卖家只能下架，无删除/恢复；违规品清不掉 | **P1** | ⬜ | 软删 + 回收站 + 30 天清理；恢复后 id/评价不变 |
| **A-10** | **类目属性模板** | 规格参数是自由 KV，不可校验/筛选/比价 | P1 | ⬜ | 必填缺失拒绝发布；老商品不阻断 |
| **A-11** | **图片表（排序/类型/审核态）** | `images` 为逗号串，无排序与图片审核 | P1 | ⬜ | `t_spu_image`；双写兼容 CSV |
| **A-12** | **搜索索引自动同步** | 发布/编辑不触发 ES；依赖手工重建 | P1 | ⬜ | 变更 5s 内可搜；失败重试 |
| **A-13** | **批量上下架** | 无批量能力 | P2 | ⬜ | 逐条结果；部分失败不回滚 |
| **A-14** | **SKU 增删改（结构）** | 只能发布时创建；新增/删除规格无入口 | P1 | ⬜ | 有订单的 SKU 禁止删除；改规格触发重审 |
| **A-15** | **评价审核流** | `t_review.audit_status` 恒 APPROVED | P2 | ⬜ | 机审+人审；违规评价下架 |
| **A-16** | **商家回复评价** | 已落地（第一百零六轮）：`t_review` 补 `reply_content / reply_at / replied_by`；`GET /api/reviews/seller`（只含 APPROVED，replied / rating / keyword 筛选）+ `POST /api/reviews/{id}/reply`；一条评价一次回复（重复 409），仅 APPROVED 可回；跨店 404、买家 403、未登录 401，平台自营允许运营代回复；回复复用 `ReviewRiskJudge`；商家端 DTO 无买家身份字段，匿名昵称脱敏；商家端「评价管理」三页签 + 回复弹窗，买家详情与「我的评价」展示回复 | P2 | ✅ | 单测 13 条（模块 64/0）、冒烟 55/0、UI e2e 29/0、审计 `REVIEW_REPLY` |
| **A-17** | **商品导入导出 / 复制 / 诊断** | 已落地（第一百零七轮）：`POST /{id}/copy`（复用 create，标题「（副本）」、默认下架、销量评分归零）；`GET /export` + `POST /import` + `GET /import-template`（16 列分组模板、BOM、公式注入防护、逐组独立事务、部分成功）；`t_product_daily_stat` 记公开曝光/点击，`GET /diagnostics?days=30` 返回点击率与建议；交易域 `product-stats` 补 `orderCount`，前端合并算点击成交率 | P3 | ✅ | 单测 20 条（模块 259/0）、trade 119/0、三端 tsc 全绿；A-17 冒烟 **53/0**、A-17 UI e2e **25/0**；回归 A-13 **53/0**、A-16 冒烟 **55/0**、A-16 UI **29/0** |

### B. 交易 / 支付 / 资金（7 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 / 依赖 |
|---|---|---|---|---|---|
| B-01 | 真实微信支付（Native + JSAPI） | 代码可切换，缺商户号/证书/回调域名 | **P0** | ⛔ | 办个体工商户 → 商户号 → 配 `.env` → 真机支付 |
| B-02 | 支付回调公网 HTTPS | 本地沙箱无公网域名 | P0 | ⛔ | 依赖部署（I-01） |
| **B-03** | **库存冻结/释放/扣减不带 orderId** | 形参存在但未进请求体，理论可动到别的订单冻结量 | **P0** | ⬜ | 台账按 orderId 归属 + 幂等；对账守卫 |
| B-04 | 重复收款自动退款 | 当前“检出+人工退”，自动退取决于你是否接受 | P1 | ❓ | 你拍板后再做 |
| B-05 | 电子面单 / 真实物流 | 已落地（第一百零八轮）：`t_waybill`（一单一面单，幂等）+ `LogisticsProvider` 适配层 + `SimulatedLogisticsProvider`；卖家/运营申请面单，发货后补推揽收，定时任务默认 30s 一步推进到签收；商家端发货弹窗生成并自动回填；真实渠道待接快递鸟/菜鸟 | P2 | ✅ | 单测 15 条（模块 79/0）、冒烟 **36/0**、UI e2e **20/0**；真实运单号与快递回调仍缺凭据 |
| B-06 | 发票（买家申请/商家开票） | 未做 | P3 | ❓ | 是否需要先确认 |
| B-07 | 预售 / 定金 | 明确范围外 | P3 | ⛔ | 本期不做 |

### C. 客服 / IM（6 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 / 依赖 |
|---|---|---|---|---|---|
| C-01 | 撤回 / 引用 / 语音 / 单向删除 / 时间分隔 | 全部落地，双通道双端 | P1 | ✅ | 冒烟 25/0、30/0 |
| C-02 | 实时 WebSocket + 已读 + 正在输入 | 已落地（票据鉴权、Pub/Sub 扇出） | P1 | ✅ | realtime 17/0 |
| C-03 | 小程序聊天真机验收 | 录音/播放/滚动定位需真机 | P1 | ⛔ | 你在开发者工具与真机点一遍 |
| **C-04** | **客服工单认领 / 转交 / 审计** | 已落地（第一百零九轮）：`t_support_session` 增 `assignee_id / assigned_at`，新增 append-only `t_support_ticket_log`；认领条件 UPDATE（并发仅一人成功），转交/释放限当前处理人或店主，目标须为本店在职成员；工单视图返回处理人 + 成员 + 处理记录；商家端会话行/头部展示处理人，弹窗内认领/转交/释放 | P2 | ✅ | 单测 10 条（模块 26/0）、冒烟 **32/0**、UI e2e **17/0**；回归客服生命周期 **32/0** |
| C-05 | 聊天内容质检 / 敏感词拦截 | 已落地（第一百一十轮）：`ChatRiskJudge` 四类风险（联系方式 / 站外链接 / 资金诈骗词 / 配置敏感词）；链接、诈骗词、敏感词 422 拦截且零落库，手机号默认仅告警（可配置拦截）；买家客服消息、客服回复、私信三处统一过 `ChatModeration`，日志记录脱敏分类 | P2 | ✅ | 单测 10 条（模块 36/0）、冒烟 **16/0**、UI e2e **11/0**；回归客服生命周期 **32/0**、C-04 **32/0** |
| C-06 | 订阅消息（离线推送） | 代码就绪，缺模板 ID | P2 | ⛔ | 你在公众平台申请模板 |

### D. 评价 / 售后（5 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| D-01 | 评价基础能力（评分/晒图/追评/我的评价） | 已上线 | P1 | ✅ | 一单一商品一评 |
| D-02 | 商品评分回写与对账 | 事务后事件 + 每小时对账 | P1 | ✅ | 评分与评价区一致 |
| D-03 | 评价审核流 | 同 A-15 | P2 | ⬜ | 违规评价可下架 |
| D-04 | 商家回复 | 已落地（A-16）：一条评价一次回复，仅 APPROVED 可回复，匿名不泄身份 | P2 | ✅ | 一次回复 |
| D-05 | 售后按商品/SKU 退款统计 | 已落地（第一百一十一轮）：`GET /api/seller/product-refunds` 按 `spu+sku` 汇总退款件数 / 金额 / 涉及订单数，口径取订单明细快照；售后页新增统计卡片与合计 | P2 | ✅ | 冒烟 **17/0**、UI e2e **12/0**；trade 119/0、回归 A-17 **53/0** |

### E. 营销（3 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| E-01 | 优惠券（领/锁/核/退/补偿） | 已上线 | P1 | ✅ | 券口径固化 |
| E-02 | 券叠加与活动互斥规则 | 基础互斥有，复杂叠加未定义 | P2 | ❓ | 需先定规则 |
| E-03 | 满减 / 拼团 / 会员价 | 未做，范围外 | P3 | ⛔ | 本期不做 |
| **E-04** | **多多币（签到 / 购物返币 / 站内任务 / 节日活动 + 结算抵扣）** | **按 2026-10-26 指令暂不启动**；方案已细化并保留（`docs/PLAN.md` §7 R-P1） | P2 | ⏸ | 100 币 = 1 元、与券叠加、单笔上限 50%、12 个月有效；8 条验收见 PLAN §7 |

### F. 用户 / 商家端（5 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| F-01 | 账号注销（两端 + 后端） | 已上线（`account-delete` 页 + 阻断项校验） | P1 | ✅ | 注销后令牌吊销 |
| F-02 | 商家入驻 / 资质 | 入驻与审核已上线 | P1 | ✅ | 资质状态可见 |
| **F-03** | **资质到期提醒 / 自动下架** | 已落地（E15）：`license_expire_at + expire_notify_stage`，`MerchantLicenseJob` T30/T7 通知、EXPIRED 自动停业并联动商品下架；商家端有到期横幅 | P1 | ✅ | `LicenseExpiryTest` + `merchant-license-ui-check.mjs` 三档覆盖；原表状态陈旧，已更正 |
| **F-04** | **店员权限细分** | 已落地（第一百一十二轮）：6 个操作级权限键（商品编辑 / 改价 / 上下架 / 发货 / 退款 / 客服回复）；`permissions` 列 NULL=历史全量、空串=仅查看；员工页逐人配置，商品 / 交易 / 社交三域后端门禁即时生效 | P2 | ✅ | 冒烟 **37/0**、UI e2e **11/0**；回归 C-04 **32/0**、E13 **24/0**；“只能发货、不能改价”回库断言通过 |
| **F-05** | **商家端 2FA / IP 白名单** | 2FA 已落地（第一百一十六轮）：后台与商家端共用面板，账号中心自助绑定；商家端与商城 Web 登录均识别 `TOTP_REQUIRED` 支持验证码 / 恢复码。IP 白名单未启用（P3 可选，属部署层）；小程序手机号登录未处理 2FA | P3 | 🟨 | F-05 UI e2e **17/0** |

### G. 小程序（5 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| G-01 | 商品详情主图/富文本/规格包装 | 已对齐 Web（主图第一、rich-text） | P1 | ✅ | 真机确认渲染 |
| G-02 | 暗夜模式四端 | Web 三端 + 小程序 token 全覆盖 | P2 | ✅ | 真机切系统暗色 |
| **G-03** | **小程序整体真机验收** | 登录/支付/定位/暗色/录音/富文本 | **P1** | ⛔ | 你按清单走一遍并反馈 |
| **G-04** | **三类 UI 对齐（未读标记 / 二级菜单 / 灯箱）** | 已审计确认（第一百一十三轮）：买家 / 商家 Web 共用聊天组件，未读、二级菜单、灯箱均已对齐；小程序有 tabBar 合并角标、会话 / 逐条未读、表情 / 贴纸页签与 `wx.previewImage` | P2 | ✅ | `support-ux-ui-check.mjs` **53/0**；小程序为代码级对照，真机视觉归 G-03 |
| G-05 | 小程序订阅消息 | 同 C-06 | P2 | ⛔ | 模板 ID |

### H. 运营后台（4 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| H-01 | 商品审核台 + SLA | 已上线（含全量内容） | P1 | ✅ | e2e 36/0、11/0 |
| H-02 | 评价管理页 | 已落地（第一百零四轮）：后台「评价管理」页，审核态 / 星级 / 关键词筛选 + 下架 / 恢复；下架原因必填，公开口径与商品评分同步重算 | P2 | ✅ | 冒烟 47/0、UI e2e 18/0 |
| **H-03** | **商品变更历史页** | 已落地（第一百一十四轮）：跨商品 `change-history` 接口按商品 / 标题 / 事件 / 店铺过滤并分页；后台独立页 +「查看改动」弹窗；仍取 A-08 append-only 历史 | P2 | ✅ | 单测 **265/0**、冒烟 **19/0**、UI **11/0** |
| **H-04** | **后台待办角标聚合** | 已落地（第一百一十九轮）：五类队列导航各显示服务端 PENDING 总数（>99 → 99+），30 秒轮询 + 路由重取 | P3 | ✅ | UI e2e **14/0** |

### I. 基础设施 / 运维（9 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 / 依赖 |
|---|---|---|---|---|---|
| I-01 | 生产部署（云主机/域名/HTTPS） | 仅本机 Docker；同事/客户访问不到 | **P0** | ⛔ | 你指定主机与域名，我部署 |
| I-02 | 备份第二存储 | 单盘，库与备份同盘 | **P0** | ⛔ | 你指定盘位/对象存储 |
| I-03 | 147 家夹具店铺清理 | 等你点头（只降权或删号） | P2 | ⛔ | 先备份再跑清理脚本 |
| I-04 | 历史测试账号只降权策略 | 每轮新增约 37 个账号 | P2 | 🟨 | 清理脚本执行 |
| I-05 | 吊销只读 GitHub 令牌 | 曾在聊天出现 | **P0** | ⛔ | 你在 GitHub 设置里 Revoke |
| I-06 | 腾讯云密钥轮换 | 曾在聊天/日志出现 | **P0** | ⛔ | 你在控制台轮换 |
| **I-07** | **ES 检索启用** | 已启用并回归（第一百二十轮）：索引 `duoduo-search` 已建，九类变更 ≤5s 反映；停 ES 可编辑、恢复自愈、删索引自动重建 | P2 | ✅ | `search-sync-a12-smoke.mjs` **56/0**；生产显式 `--profile search` |
| I-08 | XXL-Job 调度平台 | 现用 DB 选主锁，够用 | P3 | ⛔ | 暂缓 |
| I-09 | 压测合格线 | 用业界通用值，未定业务线 | P3 | ❓ | 你给数字我接基线 |

### J. 测试债（5 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| J-01 | product-audit e2e | 已修复并通过 | P2 | ✅ | 36/0 |
| **J-02** | **share-circle e2e** | 已复跑修绿（第一百一十五轮）：历史失败是脚本默认打 Vite dev server（5173）导致连接拒绝；默认值改为容器站点 8088，删除软删后列表立即消失 | P2 | ✅ | 复跑 **21/0**，0 console 报错 |
| **J-03** | **前端 e2e 覆盖面** | 已补下单支付主链路（第一百二十一轮）：新增 UI e2e **12/0** + 回库断言；50+ 脚本覆盖其余核心页 | P2 | ✅ | `checkout-payment-j03-ui-check.mjs` |
| J-04 | 小程序自动化 | 无真实浏览器/设备自动化 | P2 | ⬜ | 需开发者工具/真机 |
| **J-05** | **反向验证覆盖率** | 已系统化（第一百二十一轮）：`docs/manuals/negative-test-matrix.md` 40 条规则逐条对应反向断言 | P3 | ✅ | 矩阵可检索 |

### K. 文档 / 流程（4 项）

| ID | 事项 | 现状 / 缺口 | 优先级 | 状态 | 验收 |
|---|---|---|---|---|---|
| K-01 | AI 交接文档集（四渠道） | 已完成，本次补方案与全量待办 | P1 | ✅ | 四渠道可查 |
| **K-02** | **OpenAPI 契约导出** | 已重导（第一百一十八轮）：补入 social，7 个服务 **362 个端点**；F-04/H-03/D-05/B-05/C-04/C-02 新端点全覆盖 | P2 | ✅ | 导出全部 200；网关不在范围 |
| **K-03** | **商家商品管理操作手册** | 已编写（第一百一十七轮）：`docs/manuals/merchant-product-guide.md` | P2 | ✅ | 按现有实现核对 |
| **K-04** | **客服 / 运营 SOP** | 已编写（第一百一十七轮）：`docs/manuals/support-ops-sop.md` | P3 | ✅ | 按 C/H/E18/E23 核对 |

---

## 2. 近两周已完成（避免重复做）

| 日期 | 批次 | 结果 |
|---|---|---|
| 10-21 | 暗夜模式四端 | Web 三端 + 小程序全量，守卫 55 项 |
| 10-21 | 地址三级级联 + 地图选点 | 两端 87 项 |
| 10-21 | 问客服直连 | 两端直达，503 兜底 |
| 10-22 | 购物车按店铺分组 / 批量操作 / 顺序修复 | 全绿 |
| 10-22 | 店铺主页 + 店名可改 + 供货标签 | 全绿 |
| 10-22 | 支付入账事故（拆单幂等键） | 修复 + 退款 148.80 |
| 10-22 | 十问题批次（搜索/排序/未读/菜单/商品卡） | 9/0 + 五套 e2e |
| 10-23 | 聊天实时化（WS/已读/正在输入） | 17/0 |
| 10-23 | 撤回 / 引用 / 语音 | 25/0 |
| 10-24 | 消息终版（点击录音/单向删除/时间分隔/引用跳转） | 30/0 + UI 29/0 |
| 10-24 | 商品四项改造（审核防绕过/富文本/主图/评价分析） | 25/0 + UI 19/0 |
| 10-24 | 商品重构 P0（审核台全内容/改价改库存/分级重审） | 34/0 + 22/0 + 11/0 |
| 10-24 | 账号注销、隐私指引、订阅消息代码侧 | 已上线 / 待模板 |

---

## 3. 建议顺序（按依赖与风险）

```
第 1 优先（数据/资金/安全）
  I-05 令牌吊销 → I-06 密钥轮换 → B-03 库存台账带 orderId → A-07 乐观锁
第 2 优先（上线前置）
  I-01 部署 → I-02 备份盘 → B-01/B-02 微信支付 → I-03 夹具清理
第 3 优先（商品可治理）
  A-08 审核历史 → A-09 删除/回收站 → A-10 类目属性 → A-11 图片表 → A-12 搜索同步
第 4 优先（体验/效率）
  A-14 SKU 增删 → A-13 批量 → H-02 评价管理 ✅ → A-15 评价审核 ✅ → A-16 商家回复 ✅ → H-03 变更历史页 ✅
第 5 优先（增长/留存）
  E-04 多多币：账户与抵扣闭环 → 签到/日常任务 → 节日活动
第 6 优先（可选）
  B-05 电子面单 ✅、F-04 权限细分 ✅、C-04 工单 ✅、I-08/J-04/K-04
```

---

## 4. 需要你拍板 / 操作的清单（汇总）

| 事项 | 你要做什么 | 影响 |
|---|---|---|
| 生产部署 | 指定云主机 / 域名（或说“先不部署”） | 决定能否对外访问 |
| 备份盘位 | 说一个路径或对象存储桶 | 防单盘故障 |
| 微信支付 | 办个体工商户 → 申请商户号 → 给证书 | 真实收款 |
| 小程序 | 真机走一遍（登录/支付/定位/暗色/录音） | 上线前最后一道 |
| 订阅消息 | 公众平台申请模板 ID | 离线推送 |
| 夹具清理 | 回复“只降权”或“删号” | 后台数据干净 |
| 令牌/密钥 | 吊销 GitHub 只读令牌、轮换腾讯云密钥 | 安全 |
| 压测线 | 给 QPS / P95 目标（可不说） | 性能基线 |
| 业务口径 | B-04 自动退款、E-02 券叠加、G-04 剩余 UI | 决定是否排期 |
| 多多币口径 | 确认 `docs/PLAN.md` §7 R-P1 的默认值（50% 单笔上限 / 12 个月有效 / T+7 返币 / 平台承担成本 / 退款负余额） | 决定 E-04 是否按此开工 |


---

# 10 · 最终预期效果与验收标准

## 1. 目标状态（做完是什么样）

### 买家
- 打开商城/小程序：能搜到、能看到真实主图与完整详情（图集主图第一、富文本正常渲染、规格与包装清晰）。
- 下单-支付-收货-评价闭环无阻断；订单状态与资金状态一致；售后可追踪。
- 客服/私信实时到达，图片/视频/语音/引用/撤回/删除符合预期。
- 暗色模式可读，移动端布局不溢出。

### 商家
- 商品全生命周期可运营：发布 → 审核 → 上下架 → **改价/改库存** → 编辑内容（重审）→ 下架/（未来）删除。
- 能看每件商品的销量、成交额、退款、评分、好评率，并能下钻看评价。
- 订单发货、售后处理、结算对账数据一致（商家看到的数与运营看到的数逐分一致）。

### 运营
- 审核台能看到买家将看到的**全部内容**（图集/介绍/富文本/规格/包装），能一键通过/驳回并留原因。
- 被驳回过的商品，卖家无论改多少次都回到人工队列。
- 能看到审核 SLA、租户健康度、审计日志；代第三方操作必须走工单。

### 工程
- 新 AI 按本包 §0 自检即可运行；改动有单测 + 冒烟 + UI e2e 三层证据。
- 任何"修好了"的结论都能用一条命令复现（脚本在 `scripts/`）。
- 文档与代码同步：PLAN/README/SELFTEST/简报四处不落后。

## 2. 验收场景（按角色走一遍）

| # | 场景 | 通过标准 |
|---|---|---|
| 1 | 商家发布商品（含富文本/规格/包装/SKU） | 审核态正确（干净=自动通过，命中风险=待审） |
| 2 | 运营驳回 → 商家改两次 | 第二次仍是待审；审核台能看到全部内容 |
| 3 | 商家改价/改库存 | 立即生效、列表价与详情价一致、**审核态不变** |
| 4 | 商城详情页 | 首图=主图；富文本/规格/包装正确显示 |
| 5 | 下单支付 | 拆单正确、库存冻结、资金托管 |
| 6 | 发货 → 确认收货 | 放款给卖家、佣金 6%、冻结转实扣 |
| 7 | 评价 | 评分回写、详情页评分与评价区一致 |
| 8 | 客服对话（含语音/图片/撤回/删除） | 双端一致、实时到达、越权被拒 |
| 9 | 商家商品分析 | 销量/金额/退款/评分/好评率与数据库独立复算一致 |
| 10 | 暗色模式 | 所有文字对比度 ≥4.5:1，无深底深字 |

## 3. 质量门槛

| 维度 | 门槛 |
|---|---|
| 单测 | 改动模块 0 失败；新增规则必须有单测 |
| 接口 | 正向/越权/边界三类断言齐全；关键写操作有回库断言 |
| UI | 关键页面 e2e 通过；无 console error / pageerror |
| 性能 | 首屏与核心接口在本地容器可接受；秒杀并发有专项冒烟 |
| 安全 | 越权 403/404、富文本消毒、内部接口鉴权、无原生弹窗 |
| 文档 | 四处同步；新增脚本进 SELFTEST |

## 4. 非目标（不要做）

- 不做真实资金清算与对公结算；
- 不做多仓/WMS/跨境/直播；
- 不为了“看起来完整”而编造数据或跳过验证；
- 不以“接口 200”代替“业务正确”。

## 5. 交接完成的定义

接手 AI 能做到以下三件事，即视为交接成功：
1. 独立启动系统并通过 §0 自检；
2. 独立完成一个小需求（含单测/冒烟/文档）；
3. 独立解释一处历史决策的根因（例如“为什么改价不重审”）。


---

# 11 · 踩坑手册与铁律（按症状查）

> 每一条都在本项目真实发生过。遇到相似症状先查这里，能省掉大量试错。

## 1. 前端改了但页面没变（P0 事故）

**症状**：代码已改、容器 healthy、接口也对，页面仍是旧版。
**根因**：`frontend/Dockerfile` **只 COPY 宿主 `apps/<app>/dist`，不在容器内构建**。只跑 `tsc --noEmit` 不产生 dist。
**判据**：容器内 `assets/*.js` 的 mtime 早于源码修改时间；产物 grep 不到新类名；`docker compose build` 只用 16 秒。
**正确做法**：
```bash
cd frontend && npm run build:mall   # 或 build:admin / build:merchant
cd .. && docker compose build duoduo-web-app && docker compose up -d --force-recreate duoduo-web-app
```
**永久纪律**：改前端一律 `scripts/web-up.sh`，禁止直接 build 前端镜像。

## 2. `*.localhost` 502 / 接口空 body

**根因**：系统代理 9894 拦截 `*.localhost`；容器 CORS 白名单只有 5173/3000，用 `Origin:8088` 手动 curl 会 403。
**做法**：所有命令加 `NO_PROXY="*"`；浏览器访问用 `localhost:8088`，不要用 `127.0.0.1`。

## 3. 构建命令“成功”但产物是旧的

**根因**：`mvn ... | tail` 取到的是 tail 的退出码；`JAVA_HOME` 未设时 mvn 直接 127。
**做法**：先 `export JAVA_HOME=...`；退出码单独判；构建后核对产物 mtime。

## 4. 公共模块改了，下游不生效

**根因**：`mvn package` 不安装到本地仓库，下游仍用旧 jar。
**做法**：`mvn -o -q -pl duoduo-common,duoduo-common-web -am -DskipTests install`，再打下游。

## 5. 更新可空字段“成功”但库里没变

**根因**：MyBatis-Plus 默认 `NOT_NULL` 更新策略，`updateById` 不写 null。
**做法**：显式 `LambdaUpdateWrapper.set(col, null)`；可空字段用“清空标记”记账。
**同类坑**：`eq(col, null)` 会生成 `= null` 永不匹配，必须 `.isNull(col)`。

## 6. 前端排序/ID 比较错乱

**根因**：雪花 ID 超过 JS `Number.MAX_SAFE_INTEGER`，`Number(id)` 丢精度。
**做法**：ID 一律字符串；排序用 `createdAt`；断言不要转 Number。

## 7. 菜单/弹层被裁剪

**根因**：绝对定位元素在 `overflow:auto` 容器内。
**做法**：Portal 到 body + `position:fixed` + 视口坐标夹取（已实现，勿改回）。

## 8. 暗色模式文字看不清

**根因**：使用了不存在的 CSS 变量（如 `--text-1`），fallback 是深色。
**做法**：只用 `--text/--text-2/--text-3`；新增组件在暗色下测对比度。

## 9. 录音“太短”误报

**根因**：`onstop` 只读累计时长，正在录的最后一段未结算。
**做法**：`finish()` 在 stop 前补结算当前段；暂停态不重复加。

## 10. 商品审核 e2e 一直红

**根因**：夹具内容干净 → 自动放行，根本没进人工队列。
**做法**：标题里放 `微信abc12345` 之类命中 `CONTACT_PATTERN` 的内容强制转人工。
**同类**：商品审核测试若被“改两次自动通过”绕过，检查 `rejected_at` 是否落库。

## 11. 通知测试列表为空

**根因**：`incremental/17` 的 3 条种子公告被清理。
**做法**：重放该脚本的种子段（`INSERT IGNORE`）。

## 12. Maven 离线解析失败（jsoup 等）

**根因**：本地仓库有 jar 但 `_remote.repositories` 标记的仓库与当前 mirror 不一致。
**做法**：`mvn -o install:install-file -Dfile=... -DgroupId=... -DartifactId=... -Dversion=... -Dpackaging=jar`。

## 13. 构建被 safe-delete 守卫拦

**症状**：`SAFE_DELETE_BULK_CONFIRM_REQUIRED`（vite 清空 dist 超过阈值）。
**做法**：先 `rm -rf apps/<app>/dist` 再构建。

## 14. Docker 重建不生效

**根因**：`up -d --build` 偶发复用旧镜像。
**做法**：`docker compose build <svc>` + `up -d --force-recreate <svc>`。

## 15. 测试互相污染

**根因**：冒烟/e2e 共享库与夹具账号，并行跑会串。
**做法**：串行执行；夹具用唯一手机号/唯一标题；测完只降权不删号。

## 16. 原生弹窗

**纪律**：`window.confirm/prompt/alert` 一律禁止；web 用 `useConfirm`，小程序用 `wx.showModal`。

## 17. 小程序 tabBar/switchTab

- tabBar 最多 5 项；超出直接编译失败。
- `switchTab` 对非 tab 页静默失败；移出 tabBar 要全局排查引用。
- 非 tab 页不要自绘导航栏（没有返回键）。

## 18. 时间与时区

- 后端一律 UTC；小程序显示需 +8。
- 秒杀时间按北京时间解析，别用 UTC 直接比。

## 19. 审计/通知类“动作变 UNKNOWN”

**根因**：枚举翻译代码跑在收件方服务，但只重建了发件方。
**做法**：跨服务新增枚举值，**两端都要重建**。

## 20. 端口与容器命名

- 容器名从 `docker-compose.yml` 的 `container_name` 读，绝不猜。
- 前端 dev server 若要用：必须以 `frontend/apps/mall` 为根，端口 5173（或 3000，视脚本），且后台运行。


---

# 12 · AI 接管操作手册

> 这是给“下一个 AI”的**可执行流程**，不是背景阅读。照做即可接管。

## 0. 接管第一步（复制给新会话的开场提示词）

```text
你是多多宠物商店项目的接手 AI。工作区：C:\Users\16947\WorkBuddy\2026-09-18-17-36-12
仓库：D:\智能体项目\duoduo-pet-mall-java（Windows + Git Bash）。

先做三件事，不要写代码：
1) 读工作区 AI交接文档集-2026-10-24 的 00/01/02/04/05/09 六篇；
2) 执行 00 篇 §3「三分钟自检」，把容器/编译/类型检查/一套冒烟的实际输出贴出来；
3) 读 docs/PLAN.md 顶部最新一轮与《09-待办任务与后续计划》，给出你准备执行的下一项（含验收标准），等我确认。

硬规矩：不用原生弹窗；前端必须 npm run build 后再重建镜像；跨库禁止 JOIN；
幂等用唯一索引；金额整数分、时间 UTC、ID 字符串。
```

## 1. 接手自检清单

- [ ] `docker compose ps` 全部 healthy（缺哪个先起哪个）
- [ ] `mvn -o -q -pl duoduo-product -am -DskipTests package` 成功
- [ ] `cd frontend && npm run typecheck` 0 错误
- [ ] 任一套冒烟通过（建议 `product-manage-r93-smoke.mjs`）
- [ ] 能打开三个站点：8088 / 8090 / 8091
- [ ] 读完 00~05、09、11 篇

## 2. 单任务执行循环（每个待办都按此走）

```
① 读任务卡（09 篇 + 两周待办）→ 明确验收标准
② 定位代码（03 篇代码地图）→ 读现有实现与调用方
③ 给最小改动方案（不新增无必要抽象）→ 若涉及表结构先写增量 SQL
④ 实现 → 单测
⑤ 冒烟扩展（正向/越权/边界 + 回库断言）
⑥ 涉及界面 → 重建前端 + UI e2e
⑦ 回归受影响套件 + 记录结果
⑧ 更新文档四处 + 两周待办状态
⑨ 提交推送（问题→根因→改动→验证）
```

**任何一步失败**：先判断是“实现错”还是“断言错”；不要为了让测试变绿而放宽断言。

## 3. 写代码时最容易忘的四件事

1. 新字段要同步 5 处：SQL → Entity → DTO → Service → 前端 types。
2. record 加参数会让所有旧构造调用编译失败 → **补旧参数个数的兼容构造**。
3. 可空字段更新要显式 `set(col, null)`（MP 的 NOT_NULL 策略）。
4. 前端新页面要注册路由 + 导航项 +（商家端）权限入口。

## 4. 验证证据怎么给

| 结论 | 必须附 |
|---|---|
| “已修复” | 命令 + 通过/失败数 + 关键日志行 |
| “页面已生效” | 容器内产物 mtime / hash + e2e 通过项 |
| “数据正确” | 回库 SQL 结果（不是接口返回） |
| “没有越权” | 具体状态码（403/404）+ 回库确认未被改 |
| “性能可接受” | 实测数字（并发数/耗时/错误率） |

## 5. 与用户协作

- 用户偏好：结论先行、证据优先、不写寒暄；输入极简，尽量给可点选项。
- 需要用户做的事（办证、开发者工具、控制台点按钮）：给**一步步指令**，不要让用户自己探索。
- 每轮完成写一段大白话进《项目简报.md》（工作区根目录，新在上）。
- 不编造数据/链接/结论；不确定就标注“待验证”并说明怎么验证。

## 6. 什么时候可以宣布“完成”

同时满足：
1. 代码 + 单测 + 冒烟（+ UI）全绿；
2. 回库断言通过；
3. 文档四处同步；
4. 提交已推送；
5. 没有“待确认”的关键假设（若有，列出来）。

## 7. 交接包结构（本文件夹）

```
AI交接文档集-2026-10-24/
├── 00-接手索引.md
├── 01-项目介绍与业务模型.md
├── 02-架构与技术栈.md
├── 03-目录结构与代码地图.md
├── 04-环境搭建与启动运维.md
├── 05-开发规范与工作流.md
├── 06-功能清单与完成度.md
├── 07-数据模型与接口契约.md
├── 08-测试体系与验收方法.md
├── 09-待办任务与后续计划.md
├── 10-最终预期效果与验收标准.md
├── 11-踩坑手册与铁律.md
├── 12-AI接管操作手册.md（本篇）
├── 13-账号与环境清单（脱敏）.md
├── manifest.json（云端同步位置与结果）
└── skills/duoduo-takeover/SKILL.md（可安装技能）
```

## 8. 技能安装（可选但推荐）

把 `skills/duoduo-takeover` 复制到 `~/.workbuddy/skills/`，重启会话后，凡是涉及本项目的任务会自动加载接管纪律
（自检、构建顺序、禁止事项、验证要求）。也可直接在对话中引用该目录的 `SKILL.md`。

## 9. 交接完成后的第一次汇报模板

```
已完成：<任务名>
- 改动：<文件/接口/页面>
- 验证：单测 x/0、冒烟 y/0、e2e z/0、回库断言通过
- 部署：<服务/前端> 已重建，容器 healthy
- 文档：PLAN/README/SELFTEST/简报 + 两周待办状态已更新
- 风险/待确认：<无 / 列出>
```


---

# 13 · 账号与环境清单（脱敏）

> 只写“有什么、在哪、怎么用”，**不写真实密钥**。真实值在对应平台的凭据管理里，由用户持有。

## 1. 本地开发账号（测试夹具，可公开）

| 用途 | 手机号 | 密码 | 备注 |
|---|---|---|---|
| 官方客服（自营客服） | 13900000002 | duoduo12345 | 商家端可登录，接待自营咨询 |
| 运营（可审核商品） | 13684295254 | duoduo12345 | 后台必须 `admin.localhost:8090` |
| 第三方商家（店主） | 13600003333 | duoduo12345 | 已有 APPROVED 店铺，商品夹具用它 |
| 商家店员 | 13952746517 | duoduo12345 | 子账号夹具店 |

临时买家：脚本自行注册 `197${时间戳后8位}`。

## 2. 本地基础设施

| 项 | 值 |
|---|---|
| MySQL | `duoduo-mysql:3306`，root 密码 `duoduo_root`（仅本地） |
| Redis | `duoduo-redis:6379`，无密码（仅本地） |
| Nacos | `duoduo-nacos:8848`，控制台 `http://localhost:8848/nacos` |
| ES（可选） | `duoduo-es:9200` |
| RocketMQ（可选） | namesrv 9876 / broker 10909、10911 |

## 3. 外部平台（凭据在用户手中）

| 平台 | 用途 | 凭据位置 | 状态 |
|---|---|---|---|
| GitHub | 代码托管 | 用户 SSH key（`~/.ssh`） | 已连接，可推送 |
| 腾讯云 COS | 图片/视频存储 | compose 环境变量 `COS_*` | 已配置（曾欠费 451，充值恢复） |
| 阿里云 DashScope | AI 导购大模型 | `DASHSCOPE_API_KEY` | 未配置（AI 走 mock/降级） |
| 微信小程序 | 登录/支付 | `WECHAT_APPID/SECRET` | 未配置（用户待提供） |
| 微信支付 | 真实收款 | 商户号/证书 | 未申请（需个体工商户） |
| 飞书 | 文档分发 | `lark-cli` 已授权（本机） | 可用 |
| 腾讯文档 | 文档分发 | WorkBuddy 连接器 | 需授权（可能要求重新连接） |
| ima 知识库 | 文档入库 | MCP `ima-mcp` | 可用，知识库「多多宠物商店」 |

## 4. 凭据纪律

1. 任何密钥不写入代码、文档、提交信息、聊天回复；
2. 只用环境变量/平台凭据管理读取；
3. 曾在聊天/日志出现过的凭据，视为已泄露，必须轮换（GitHub 只读令牌、腾讯云子账号密钥）；
4. 生产环境必须覆盖所有默认口令（`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`、`INTERNAL_TOKEN`）。

## 5. 云端文档位置

| 位置 | 名称 | 说明 |
|---|---|---|
| 飞书云空间 | 「多多宠物商店」文件夹 | 本次交接文档集上传于此 |
| 腾讯文档 | 「多多宠物商店 · 项目资产（按时间归档）」 | 见 `docs/handover/manifest.json` 的历史 file_id |
| ima | 知识库「多多宠物商店」（`ima_kb_d6LoVzYa`） | 本次文档入库 |
| GitHub | 仓库 `docs/ai-handover-2026-10-24/` | 与本地同一份 |

## 6. 本机路径速查

| 内容 | 路径 |
|---|---|
| 仓库 | `D:\智能体项目\duoduo-pet-mall-java` |
| 工作区文档 | `C:\Users\16947\WorkBuddy\2026-09-18-17-36-12` |
| 项目简报（唯一汇报入口） | 工作区 `项目简报.md` |
| 两周待办 | 工作区 `商品管理重构-两周待办-2026-10-24.md` |
| 本交接包 | 工作区 `AI交接文档集-2026-10-24\` |
| WorkBuddy 记忆 | 工作区 `.workbuddy\memory\`（每日流水 + MEMORY.md + 铁律手册） |


---

# 商品管理重构方案（2026-10-24）

> 依据：商品域（`duoduo-product`）、交易域（`duoduo-trade`）、商家端（`frontend/apps/merchant`）、
> 运营后台（`frontend/apps/admin`）、小程序商品页的完整代码梳理，以及淘宝/天猫/京东等平台公开规则与行业实践。
> 配套执行清单：《商品管理重构-两周待办-2026-10-24.md》。

---

## 一、调研结论：主流平台怎么做商品管理

### 1.1 平台做法对照

| 维度 | 淘宝 / 天猫 | 京东（京麦） | 对本项目的启示 |
|---|---|---|---|
| 商品模型 | **SPU 为中心**：类目 → 关键属性定 SPU，销售属性生成 SKU；切规格时标题/详情不变 | **SKU 为中心**：每个 SKU 独立价格/库存，部分类目标题随 SKU 变化 | 我们现状接近 SPU 但 SKU 不可编辑 —— 必须补 SKU 运营能力，且不必推翻 SPU 模型 |
| 类目与属性 | **类目驱动属性模板**：必填属性缺失无法发布；关键属性/销售属性/普通属性分层 | 类目模板同样强制，红*必填；属性越全搜索命中越高 | 我们用自由 KV 代替属性模板 —— 能填但不可校验、不可筛选，是中期必须补的结构性缺口 |
| 资质与品牌 | 品牌库 + 类目资质（化妆品批准文号、食品经营许可等），资质过期自动下架 | 品牌/产品线先审、质检报告按产品线绑定 | 我们只有类目名关键词兜底，无资质模型 —— 高风险类目应接资质表 |
| 审核 | 系统初审（机审）+ 人工；图文质检、极限词、类目错放、品牌授权都在机审范围 | 类目/品牌资质先审，商品提交后 1–3 个工作日审核 | 我们机审只有 4 条规则、不看图片 —— 需补内容面与图片面 |
| 审核范围 | **分级**：改价格/库存不重审；改标题/主图/详情/SKU/类目才重审 | 同上，价格提交后通常仅允许上调 | 我们「任何内容变更都重审、价格库存无入口」—— 与行业正好相反 |
| 价格 | SKU 价 + 活动价（一口价/区间价），价格标示合规（禁止虚高原价） | 供货价/建议零售价/活动价分离，价格改动留痕 | 我们只有 SKU 单价，无价格历史、无活动价模型（秒杀另行覆盖） |
| 库存 | SKU 库存 + 仓；拍下减/付款减两种策略 | 多仓库存，入仓模式同步 WMS | 我们只有单仓 SKU 库存；冻结/释放/扣减不带订单号（已知风险） |
| 生命周期 | 仓库中 / 出售中 / 已下架 / 违规下架 / 定时上架 / 预售 | 待审核 / 审核通过 / 驳回 / 上架 / 下架 | 我们只有 ON_SALE/OFF_SHELF + 审核三态，无定时、无违规态、无回收站 |
| 批量能力 | 批量发布、批量改价、批量上下架、导入导出、商品复制 | 批量添加（模板导入）、批量工具 | 我们全部缺失，商品多起来后运营效率不可接受 |
| 数据与诊断 | 曝光/点击/转化、商品体验分、评价与 DSR、诊断建议 | 商品诊断、质量分、流量来源 | 我们刚补了销量/金额/评分/好评率，仍缺流量侧与转化侧 |
| 变更治理 | 修改留痕、版本、申诉；部分平台限制单月改款次数 | 修改留痕与审批 | 我们无版本、无历史表，只有当前四列 |

### 1.2 五条核心设计思路（可直接借鉴）

1. **类目是商品数据的骨架**：属性模板决定“能不能发、能不能筛、能不能比”，自由文本只能做补充。
2. **审核按变更风险分级**：内容（标题/主图/详情/SKU 结构/类目）重审，价格与库存不重审但要留痕与阈值校验。
3. **价格与库存是运营字段，不是内容字段**：必须可高频修改、可批量、可回滚，且与订单快照解耦。
4. **状态机要覆盖异常路径**：违规下架、资质过期、库存售罄、定时上架、卖家主动下架必须可区分，因为处置动作不同。
5. **变更可追溯**：谁改了什么、改前改后、审核结论，必须能查；否则纠纷与复盘无从下手。

---

## 二、现有代码的主要问题

### 2.1 重大（阻断经营或造成数据错误）

| # | 问题 | 代码事实 | 后果 |
|---|---|---|---|
| M1 | **价格无法修改** | 编辑 DTO 无 `skus/price`；卖家/运营均无改价端点；前端无输入 | 商家只能「下架 + 重发新 SPU」，评价/销量/收藏全部归零 |
| M2 | **库存无法由商家调整** | 编辑不含库存；`StockController` 仅内部冻结/释放/扣减/回补 | 缺货只能等下架，补货需走内部接口 |
| M3 | **SKU 结构不可维护** | 无 SKU 增删改接口；`spec_json` 只在创建时写入 | 新增规格/改规格名 = 重新发布 |
| M4 | **编辑与审核并发可互相覆盖** | 实体无 `@Version`，`updateById` 整行写；审核也是整行写 | 卖家编辑与运营驳回并发时，可能把 `REJECTED` 覆盖回 `APPROVED/ON_SALE` |
| M5 | **审核范围与行业相反** | 任何内容变更（含错别字）都重审；价格库存反而无入口 | 审核队列被低风险改动淹没，真正该审的改价改库存绕开制度 |

### 2.2 高风险（合规或体验）

| # | 问题 | 代码事实 | 后果 |
|---|---|---|---|
| H1 | 自动审核不看图片 | `SpuRiskJudge.Context` 只有类目/标题/副标题/正文/价格/商家标记 | 违规图、二维码导流图直接放行 |
| H2 | 联系方式规则不含富文本 | `CONTACT_PATTERN` 只拼 title/subtitle/detail（本轮新增的 `detail_html`/规格/包装未纳入） | 富文本写微信号可绕过转人工 |
| H3 | 审核台看不到全量内容 | `ProductAudits.tsx` 只渲染主图 1 张、标题、副标题、类目、品牌、SKU 摘要、`detail` 纯文本 | 运营“看都没看全”就点通过，人工审核形同虚设 |
| H4 | 违禁词表过窄且匹配脆弱 | 仅 7 个词（保护动物/象牙/犀牛角/虎骨/人用处方药/氰化物/毒鼠强），`contains` 匹配 | 「处方药」「象牙制品（插零宽字符）」等可绕过 |
| H5 | 驳回不进商家风险计数 | 人工驳回只改商品，不改用户域违规标记 | 反复违规商家仍被视为“干净商家”自动放行 |
| H6 | 无审核/变更历史 | 只有当前 `audit_*` 四列 + `reject_count` | 改判、多次驳回、谁改的不可追溯 |
| H7 | 无商品删除/回收站 | 卖家控制器无删除端点 | 违规或废弃商品只能下架，列表长期膨胀 |
| H8 | 搜索索引与商品变更不同步 | 卖家发布/编辑不触发 ES 索引；依赖后台手工重建 | ES 开启时新品搜不到、下架仍可搜 |

### 2.3 结构性（中期）

| # | 问题 | 说明 |
|---|---|---|
| S1 | 无类目属性模板 | 规格参数/包装规格是自由 KV，不可校验、不可筛选、不可比价 |
| S2 | 图片是逗号串 | 无图片表、无排序/alt/审核态/视频，图片治理能力为零 |
| S3 | 无草稿与版本 | 编辑即生效（或即重审），无法“先存草稿再提交” |
| S4 | 状态机不完整 | 无定时上架、无违规态、无资质过期自动下架、无售罄自动下架 |
| S5 | 无批量与导入导出 | 商品数量上升后运营不可用 |
| S6 | 权限粒度粗 | 店员与店主都能编辑全部商品；无字段级/操作级权限 |
| S7 | 价格模型单一 | 无市场价/活动价/成本价/价格历史，无法做价格风控与利润分析 |
| S8 | 数据分析缺流量侧 | 只有成交/退款/评分，无曝光/点击/转化 |

---

## 三、重构方案

### 3.1 目标模型

```
类目(Category) ──< 属性模板(AttrTemplate) ──< 属性值
     │
     └──< SPU ──< SKU(价格/库存/规格) ──< 图片(排序/类型/审核态)
            │
            ├──< 内容版本(ContentVersion)：草稿 / 已发布 / 审核中
            ├──< 审核单(AuditTicket)：提交内容快照 + 结论 + 操作人
            ├──< 变更记录(ChangeLog)：字段级 改前/改后
            └──< 状态机：DRAFT / PENDING / ON_SALE / OFF_SHELF / VIOLATED / SOLD_OUT / SCHEDULED
```

**关键决策**：

| 决策 | 选择 | 理由 |
|---|---|---|
| SPU/SKU 模型 | 保留 SPU 为中心，SKU 补齐增删改 | 现有数据与交易侧已按 SPU 聚合，推倒成本高、收益低 |
| 审核分级 | 内容类字段重审；价格/库存**不重审**，但校验 + 留痕 + 异常阈值告警 | 与有赞/淘宝一致；避免审核队列被高频运营动作淹没 |
| 价格修改 | 新增 `PUT /skus/price-stock`，同步 `minPrice/maxPrice` | 直接解决 M1/M2，不动订单快照 |
| 并发 | 引入 `version` 乐观锁；审核/编辑改为条件更新 | 解决 M4 的覆盖写 |
| 草稿 | 先做“编辑暂存”，不引入完整版本树 | 控制范围；版本树放 P2 |
| 属性模板 | P1 引入类目属性表，KV 降级为“自定义属性” | 兼容历史数据 |
| 图片 | P1 引入 `t_spu_image` 表，CSV 保留只读兼容 | 支持排序/审核/视频 |
| 删除 | 软删 + 回收站（30 天） | 可恢复，符合平台惯例 |

### 3.2 范围（本期做 / 不做）

**做**：改价改库存、审核台完整内容、审核规则补富文本、并发保护、删除/回收站、审核历史、变更留痕、编辑分级重审、批量上下架。

**不做（明确排除）**：多仓库存与 WMS 对接、预售/定金、跨境、供应链采购、直播带货、类目资质证书全流程（只留接口位）。

### 3.3 分阶段

| 阶段 | 目标 | 内容 |
|---|---|---|
| **P0 止血**（本周） | 让商家能正常经营、让审核真正有效 | 改价改库存、审核台全量内容、联系方式规则纳入富文本、并发乐观锁、编辑分级重审 |
| **P1 结构化**（下周） | 数据可控、可追溯 | 审核历史表 + 变更留痕、删除/回收站、类目属性模板（只读校验）、图片表、ES 同步 |
| **P2 效率**（后续） | 运营规模化 | 批量改价/上下架、导入导出、商品复制、定时上架、商品诊断（曝光/点击/转化） |

### 3.4 验收口径

- 每个批次必须有：接口冒烟（正向 + 越权 + 边界）、UI e2e（真实容器产物）、既有回归不降级；
- 数据类改动必须有回库断言（不能只看接口 200）；
- 审核相关改动必须有反向验证（故意放开一条规则，确认恰好对应断言变红）。

---

## 四、风险与依赖

| 风险 | 说明 | 应对 |
|---|---|---|
| 改价与在途订单 | 订单项已快照，改价不影响历史；但购物车展示价会变 | 改价后购物车实时重算（现有逻辑已按实时价），提示语明确 |
| 乐观锁与存量数据 | 存量行 `version` 为 0，首次更新会 +1 | 默认值 0，不回溯 |
| 审核历史表数据量 | 每次提交一条，长期增长 | 按 180 天归档 |
| 类目属性模板迁移 | 历史自由 KV 与新模板并存 | 新模板只做“建议 + 必填校验”，旧数据不阻断编辑 |
| ES 同步 | 本地 ES 常关，无法稳定验证 | 同步失败降级 MySQL，冒烟标记跳过 |


---

# 16 · 相关功能改造方案索引（近两周，2026-10-10 ~ 10-24）

> 交接时最容易丢的不是代码，是“当时为什么这么做、还有哪半截没做”。
> 本索引把近两周所有方案/交付说明列全，并标注**未完成部分**；未完成项已汇总进 `09-近两周功能遗漏与待办总表`。
> 路径约定：`工作区/` = `C:\Users\16947\WorkBuddy\2026-09-18-17-36-12`；`仓库/` = `D:\智能体项目\duoduo-pet-mall-java`。

## 1. 商品域

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| 商品管理重构方案（2026-10-24） | `工作区/商品管理重构方案-2026-10-24.md`（本集 `15`） | 平台调研 + 代码审计 + 目标模型 + P0/P1/P2 分期 | P0-5 并发、P1-1~P1-7（见总表 A-07~A-13） |
| 商品管理重构·两周待办 | `工作区/商品管理重构-两周待办-2026-10-24.md` | 商品域逐项执行清单（P0-1~P0-4 已完成） | P0-5、P1-1~P1-7 |
| 商品管理改造与近两周待办（2026-10-24） | `工作区/商品管理改造与近两周待办-2026-10-24.md` | 四项改造（审核绕过/富文本/主图/评价分析）的结论与验证 | 已被 09 总表覆盖，保留作历史记录 |

## 2. 客服 / IM

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| 客服会话二期改动说明与小程序对齐（10-23） | `工作区/客服会话二期-改动说明与小程序对齐-2026-10-23.md` | 视频封面/未读定位/商家筛选/关闭规则/30 天清理 | 小程序真机手感确认 |
| 客服 IM 后续能力设计（10-23） | `工作区/客服IM后续能力设计-语音-引用-撤回-2026-10-23.md` | 语音/引用/撤回的设计与边界 | 已全部落地；工单模型未做（C-04） |
| 实时化迭代计划 | `仓库/docs/im-realtime-iteration-plan.md` | WS 票据、已读、正在输入、信号模型 | 无 |
| 未读徽标设计 | `仓库/docs/notification-unread-badge-design.md` | 三端未读口径（通知/私信/客服分列） | 小程序端对齐情况待逐页核（G-04） |
| 十问题验收说明（10-22） | `工作区/十问题验收说明-2026-10-22.md` | Q1~Q10 处理结果（Q8/Q9 后已补做） | 无（Q8/Q9 已在后续轮完成） |
| 二次修复方案与结果（10-23） | `工作区/二次修复方案与处理结果说明-2026-10-23.md` | 二次验收 6 项修复 + 未选会话禁发 | 无 |

## 3. 交易 / 支付 / 购物车

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| 购物车店铺分组与结算明细（10-22） | `工作区/购物车店铺分组与结算明细-交付说明-2026-10-22.md` | 按店分组、明细实时算优惠 | 无 |
| 微信支付接入方案（10-18） | `工作区/微信支付接入方案-2026-10-18.md` | Native/JSAPI 接入设计 | 商户号、证书、回调域名（B-01/B-02） |
| 微信支付配置与联调（10-18） | `工作区/微信支付接入-配置项与联调说明-2026-10-18.md` | 环境变量与联调步骤 | 同上 |
| 支付资质对比（10-21） | `仓库/docs/payment-qualification-alipay-vs-wechat.md` | 支付宝不能替代小程序收款；个体工商户是根因解 | 用户办证 |
| 微信支付小微商户方案 | `仓库/docs/wechat-pay-micro-merchant-plan.md` | 小微商户路径 | 同 B-01 |

## 4. 用户 / 地址 / 小程序

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| A 组实施方案与执行记录（10-20） | `工作区/A组实施方案与执行记录-2026-10-20.md` | 地址三级级联 + 地图选点 + 暗夜模式 | 小程序真机手感、定位域名白名单 |
| 小程序全面排查 / 修复清单（09-25） | `工作区/小程序全面排查报告-2026-09-25.md`、`小程序修复清单-2026-09-25.md` | 历史问题清单 | 大部分已修，逐条状态需按 G 组复核 |
| 小程序全流程验收（09-28） | `工作区/小程序全流程验收报告-2026-09-28.md` | 验收路径与结论 | 真机验收仍未闭环（G-03） |
| 账号注销设计 | `仓库/docs/account-deletion-design.md` | 注销阻断项与流程 | 已实现（F-01） |

## 5. 运营后台 / 部署 / 运维

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| 管理后台拆分需求 / 任务清单（10-08） | `工作区/管理后台拆分需求设计-2026-10-08.md`、`管理后台拆分-任务清单与排期-2026-10-08.md` | 三站点拆分方案 | 已落地，遗留见 H 组 |
| 三平台独立部署方案（10-08） | `工作区/三平台独立部署-架构调整方案-2026-10-08.md` | 8088/8090/8091 三站点 | 无 |
| 阿里云部署方案（10-13） | `仓库/docs/阿里云部署方案-2026-10-13.md` | 云上拓扑与步骤 | 未执行（I-01） |
| 运维手册 | `仓库/docs/OPS.md` | 部署/入口开关/备份恢复 | 备份盘位待定（I-02） |
| S9 对象存储方案（10-13） | `工作区/S9-COS接入执行方案-2026-10-13.md`、`S9-对象存储-评估与推荐方案-2026-10-13.md` | COS 已接入 | 图片表/审核态未做（A-11） |

## 6. 测试与验收

| 文档 | 路径 | 结论 | 未完成部分 |
|---|---|---|---|
| 完整验收·测试报告与执行指引 | `工作区/完整验收-测试报告与执行指引.md` | 全量验收步骤 | 需按最新批次复跑 |
| 自测手册 | `仓库/docs/SELFTEST.md` | 每套脚本用法与历史结果 | 随每轮更新 |
| 测试账号 | `仓库/docs/test-accounts.md` | 夹具账号 | 147 家店铺待清理（I-03） |

## 7. 使用建议

1. **先读 09 总表**拿到“还剩什么”，再用本索引回溯“当时怎么设计的”。
2. 任何一项开工前，先确认对应方案文档里的**边界与不变式**，不要凭直觉改。
3. 方案与实现冲突时，以**代码 + `docs/PLAN.md` 最新一轮**为准；方案文档可能滞后，但其中的“为什么不这么做”仍然有效。
