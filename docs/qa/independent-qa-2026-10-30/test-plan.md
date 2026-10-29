# 独立 QA 测试计划与追踪矩阵（2026-10-30）

> 角色：独立 QA 负责人 / 真人测试人员 / 后端代码审查员
> 原则：**只把实际执行过的结果标 PASS**；代码审查发现但未复现的只进 `code-risks.md`；外部依赖不可用或为避免污染生产而主动不做的标 BLOCKED。
> 环境：本机 Docker（`localhost`），不使用 `8.163.71.44` 公网试用环境做任何写入、破坏或压力测试。
> 需求基线：`README.md`、`docs/PLAN.md`、`docs/ARCHITECTURE.md`、`docs/manuals/**`、`docs/openapi/**`、`docs/sql/**`、`scripts/**`。
> 说明：仓库内**没有 PRD 原文件**（README 提到的《产品需求原型 PRD v0.1》未随仓库提供），因此需求口径以 README/PLAN/OpenAPI/页面为准；这本身是基线缺口，见 §5-G1。

## 1. 环境与入口

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-30 |
| 宿主 | Windows，Docker Desktop |
| 容器 | 18 个容器全部 healthy（mysql/redis/nacos/es/mq + gateway + 8 业务 + 3 前端 + 旧 Node 原型） |
| 买家商城 | `http://localhost:8088` |
| 商家工作台 | `http://localhost:8091` |
| 运营后台 | `http://admin.localhost:8090` |
| 网关 | `http://localhost:8080` |
| 数据库 | `duoduo-mysql:3306`，六库：user/product/trade/fulfilment/marketing/social + ai |
| ES | `http://localhost:9200`（healthy） |
| AI | `http://localhost:8086`，真实 DashScope，模型列表 3 个 |
| 测试账号 | 见 `docs/test-accounts.md`（本机测试环境明文口令；报告中不输出密码） |

### 1.1 测试数据隔离策略

- 只在本机开发库写入；**不碰公网试用机**。
- 复用文档登记的测试账号（买家 A/B、商家自营/第三方、运营 2 个），所有新增文本带唯一前缀 `QA1030-<随机4位>`。
- 订单/PK 等测试数据记录 ID 于 `evidence/test-data.txt`；测试结束按 ID 清理可清理部分，订单类保留并标注（开发库本身是夹具库）。
- 支付只用本地仿真/沙箱渠道；不做真实扣款。

### 1.2 明确不做 / BLOCKED 范围

| 范围 | 原因 | 状态 |
| --- | --- | --- |
| 微信登录 / 微信支付 | 未配置真实商户与订阅消息，项目已拍板不做微信支付 | BLOCKED |
| 支付宝真实资金链路 | 只有沙箱；本轮不产生真实扣款 | Mock/沙箱验证，真实链路 BLOCKED |
| 对象存储写入（COS） | 本地 `STORAGE_TYPE=cos`，上传会写真实生产桶，按限制不执行 | BLOCKED |
| 短信 / 订阅消息 / 邮件 | 无真实供应商可用 | BLOCKED |
| 微信小程序真机 | 无真机/开发者工具自动化，且 AppID 属生产 | BLOCKED |
| 公网试用机写入/并发/压力 | 规则明确禁止对生产或公网试用环境做破坏性/压力测试 | BLOCKED |
| 故障注入（停 ES/停 Redis/断 MQ） | 会打断同一环境的其它测试；仅做代码审查，排队到独立轮次 | NOT TESTED |

## 2. 方法

1. 文档与代码静态核对：README/PLAN/ARCHITECTURE/manuals/OpenAPI/DDL 与控制器、前端页面逐项比对。
2. 运行现有自动化：Maven 全量单测、三端 `tsc` + `vite build`、选定 smoke 脚本；记录真实退出码与断言数。
3. 独立 API 测试：绕过页面直接调接口，覆盖未登录/过期令牌/跨用户/跨商家/伪造头/空值/边界/重复请求。
4. 浏览器真人测试：用 Playwright/浏览器代理按买家、商家、运营角色走真实页面。
5. 回库核对：订单、支付、退款、库存、优惠券、多多币、结算、审计表逐项对照。
6. 代码审查：资金/订单/库存/退款/发币/导出/AI 链路的事务、幂等、CAS、并发、异常吞没、资源释放。

## 3. 追踪矩阵（需求—模块—角色—页面—接口—用例）

> “现有测试”列：单测 / 冒烟 / UI；空白表示没有找到对应自动化。
> “本轮”列：A=自动化复跑，M=手工/API 实测，C=代码审查，TBD=待执行。

### 3.1 账号、角色与权限

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AUTH-01 | 注册/登录/登出 | 买家 | /login | `/api/auth/register`、`/api/auth/login/token`、`/api/auth/logout` | 登录 200、Cookie HttpOnly、登出后令牌失效 | smoke、auth-isolation | A/M |
| AUTH-02 | 游客身份与 3 轮配额 | 未登录 | /ai | `/api/duoduo/capabilities`、`/api/duoduo/chat` | 第 4 轮拦截并给 loginUrl；Redis 故障 fail-open | ai-smoke、Newman A | A/M |
| AUTH-03 | 三端登录态隔离 | 买家/商家/运营 | 三端 | 三套 Cookie | `dd_at` / `dd_at_m` / `dd_at_a` 不串号 | test-accounts 手工 | M |
| AUTH-04 | 角色越权 | 买家/店员/运营 | — | `/api/seller/**`、`/api/admin/**` | 买家访问商家/运营接口 401/403；店员按权限键拒绝 | privilege-matrix、tenant-isolation | A |
| AUTH-05 | 账号注销 | 买家/商家 | /me | `/api/users/me/deactivate` | 有未终态订单/售后/商家身份时拒绝 | account-deletion-smoke | A |
| AUTH-06 | 地址簿 CRUD 与越权 | 买家 | /addresses | `/api/addresses/**` | 只能读写自己的地址；默认地址唯一 | address-smoke、address-ui-check | A |
| AUTH-07 | 管理员/商家 2FA | 运营/商家 | 后台/商家端 | `/api/admin/2fa/**` | 未带码 TOTP_REQUIRED、错码拒绝、同码重放拒绝 | admin-2fa-smoke、merchant-2fa-ui | A |
| AUTH-08 | 商家入驻与资质 | 商家 | /merchant/onboarding | `/api/merchant-materials/**` | 状态机、材料权限、到期提醒 | 单测 3 个 + 冒烟 | A/C |
| AUTH-09 | 店员权限 | 店员 | 商家端 | `/api/seller/staff/**` | 权限键生效、停用即时生效、不能自提权 | merchant-staff 单测/冒烟/UI | A |
| AUTH-10 | IP 白名单与锁定救援 | 商家 | 商家端 | `/api/seller/security/**` | 非白名单拒绝、救援流程可用、发布到网关 | merchant-ip-whitelist-smoke | A/C |

### 3.2 商品、搜索与商家商品管理

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PRD-01 | 首页/列表/搜索/筛选/分页 | 买家 | /、/search | `/api/products/**` | 结果真实、分页无重复/漏行、筛选一致 | search-smoke、home/pagination UI | A/M |
| PRD-02 | 商品详情与 SKU | 买家 | /product/:id | `/api/products/{id}` | 价格/库存/规格正确；下架不可见 | product-detail-ui | M |
| PRD-03 | 商品创建/编辑/审核/驳回/重提 | 商家/运营 | 商家商品页、后台审核 | `/api/seller/products/**`、`/api/admin/product-audits/**` | 驳回后再改仍 PENDING；审核中重复提交 422；乐观锁 409 | 单测多个、product-manage/product-audit smoke | A |
| PRD-04 | 上下架/批量/软删/恢复 | 商家 | 商品管理 | `/api/seller/products/**` | 未过审不能上架；批量逐条结果；软删后列表/详情/搜索一致 | 单测、A-13/A-09 冒烟/UI | A |
| PRD-05 | 改价改库存 | 商家/店员 | 商品管理 | `updateSkuPriceStock` | 权限、价格上限、库存非负、min/max 重算 | SellerProduct 单测、A-14 冒烟 | A |
| PRD-06 | 类目/品牌/属性 | 运营 | 后台类目/品牌 | `/api/admin/categories|brands/**` | 两级树、属性必填、审核 | 单测 + admin-smoke | A |
| PRD-07 | 价格库存导入 | 商家 | 商品管理 | `/api/seller/products/price-stock-import` | 逐行结果、部分成功不回滚、行号=CSV 物理行 | import-export-batch-smoke | A |
| PRD-08 | 商品 CSV/XLSX 导入导出 | 商家/运营 | 商品/类目/品牌 | 模板/导入/导出 | 空文件/错表头/超限/公式注入/19 位 ID/中文编码 | ProductCsv/SpreadsheetRows 单测、冒烟 183 项 | A |
| PRD-09 | 检索索引同步与降级 | 系统 | — | ES `duoduo_spu_ai` | 增删改后索引一致；ES 不可用回退 MySQL | SearchSyncJobTest、search-smoke | A/C（故障注入 NOT TESTED） |

### 3.3 购物车、订单、支付与结算

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TRD-01 | 加购/改数量/删除/按店铺分组 | 买家 | /cart | `/api/cart/**` | 重复加购合并；库存上限；多店分组 | cart 单测、cart-batch-ui | A/M |
| TRD-02 | 结算与下单 | 买家 | /checkout | `/api/orders` | 金额=商品+运费-优惠；拆单正确；库存预占 | order-smoke | A/M |
| TRD-03 | 重复提交/幂等 | 买家 | /checkout | `/api/orders`、支付回调 | 同幂等键不重复下单/入账 | order/payment 冒烟 | A/M |
| TRD-04 | 库存不足/商品下架/改价 | 买家 | 结算 | `/api/orders` | 明确 422/409，不产生半单 | order-smoke | A/M |
| TRD-05 | 支付（沙箱/Mock） | 买家 | /pay | `/api/payments/**` | 状态推进、回调验签、重复支付拦截 | payment-smoke、Alipay 单测 | A（Mock 标注） |
| TRD-06 | 订单列表/详情/取消/确认收货 | 买家 | /orders | `/api/orders/**` | 状态机正确、非法流转拒绝 | order-smoke、OrderStatusTest | A/M |
| TRD-07 | 订单导出（买家脱敏） | 买家 | /orders | `/api/orders/export` | 只导自己、手机号/地址脱敏、≤5 万行 | import-export-smoke | A |
| TRD-08 | 商家发货/售后/结算 | 商家 | 商家订单 | `/api/seller/orders/**`、`/api/seller/settlements/**` | 跨店 403/404、发货状态、结算金额 | seller/settlement 冒烟 | A |
| TRD-09 | 运营订单干预/导出/异步任务 | 运营 | 后台订单 | `/api/admin/orders/**`、`/api/admin/export-jobs/**` | 干预留痕、导出行数一致、跨人下载 404 | export-p3、import-export 冒烟 | A/C（ExportJob 无单测） |
| TRD-10 | 自动退款/对账 | 系统/运营 | 后台 | `/api/admin/payment-refunds/**`、`/api/admin/recon/**` | 退款金额一致、重复回调幂等 | payment-auto-refund/recon 冒烟、单测 | A/C |

### 3.4 营销、优惠券与多多币

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MKT-01 | 领券/我的券/锁券/核销 | 买家 | /coupons | `/api/coupons/**` | 重复领取拒绝、跨用户拒绝、重复核销拒绝 | coupon-smoke、CouponAllocationTest | A |
| MKT-02 | 满减券/折扣券 | 买家 | 结算 | `/api/coupons/**` | 门槛/上限/叠加规则（一单一券） | DiscountCoupon 单测、discount-coupon-smoke | A |
| MKT-03 | 秒杀 | 买家/运营 | /seckill、后台 | `/api/seckill/**` | 库存原子扣减、每人限购、活动时间窗、批量上下架 | seckill-smoke/UI | A/C（SeckillService 无单测） |
| MKT-04 | 多多币账户/流水 | 买家 | /coins | `/api/coins/**` | 余额=Σ流水、冻结/解冻、导出 | coins-smoke、CoinServiceTest | A/M |
| MKT-05 | 签到/任务发币 | 买家 | /coins | `/api/coins/signin`、`/tasks/report` | 重复签到/重复事件不重复发币、连续天数 | coins-signin-smoke、CoinTaskService 无单测 | A/C |
| MKT-06 | 活动预算/每人上限/并发 | 运营/系统 | 后台 | `/api/admin/coins/**` | 预算耗尽停止、超发可对账 | CoinAllocationTest、coins smoke | A/C（并发未实测） |

### 3.5 售后、评价、物流与分享

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AFS-01 | 申请售后/商家处理/运营仲裁 | 买家/商家/运营 | /aftersales、后台 | `/api/aftersales/**` | 状态机、金额、库存回补、权限 | aftersale-smoke、aftersale-ui | A/M |
| AFS-02 | 退款与结算台账一致 | 系统 | — | 退款/结算 | 订单-支付-退款-结算金额一致 | payment-auto-refund、settlement 单测 | A/C |
| AFS-03 | 物流轨迹/电子面单 | 商家/买家 | 订单详情 | `/api/logistics/**` | 轨迹推进、面单模拟、异常态 | logistics/waybill 冒烟/UI | A |
| AFS-04 | 评价/回复/晒单/举报 | 买家/商家/运营 | 评价页/晒单 | `/api/reviews/**`、`/api/shares/**` | 未完成订单不可评、重复回复 409、举报下架 | review/share 冒烟/UI、单测 | A |
| AFS-05 | 信用分/违规 | 商家 | 商家端 | `/api/seller/credit/**` | 扣分规则、申诉 | credit-smoke | A |

### 3.6 消息、客服与未读

| ID | 需求/功能 | 角色 | 页面 | 主要接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MSG-01 | 通知列表/已读/红点 | 买家/商家/运营 | 通知中心、铃铛 | `/api/socials/notices/**` | 只标记展示 N 条、角标=服务端权威值 | ai Newnan C、Notice 单测、UI | A/M |
| MSG-02 | 客服会话认领/转交/回复 | 商家/店员 | 商家客服 | `/api/seller/support/**` | 并发认领一个 409、非本店转交 400、权限键 | SupportTicketServiceTest、C-04 冒烟/UI | A |
| MSG-03 | 聊天敏感词/联系方式 | 买家/商家 | 客服 | 发消息 | 违规 422 且零落库；默认告警/可拦截 | ChatRiskJudgeTest、C-05 冒烟 | A |
| MSG-04 | 私信与客服边界 | 买家 | /messages | `/api/messages/**` | 私信列表不含客服会话 | support 冒烟/UI | A |
| MSG-05 | 多角色角标一致性 | 三端 | 各页 | 角标聚合 | 页面切换/刷新后不丢不串 | admin-badges、live-refresh UI | A |

### 3.7 导入导出（横向）

| ID | 场景 | 接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- |
| IMP-01 | 空文件/错误表头/超行/超大 | 各 import | 整体 4xx，不落库 | import-export 冒烟 | A |
| IMP-02 | 重复数据/部分失败 | 各 import | 逐行结果、部分成功不回滚、行号准确 | import-export 冒烟 | A |
| IMP-03 | 公式注入/超长字段/中文 | CSV/XLSX | `=+-@` 转义、编码正确、字段截断规则 | ProductCsv 单测 | A |
| IMP-04 | 数据权限 | 各 export | 买家只看自己；商家只看本店；运营按角色 | import-export/privilege 冒烟 | A |
| IMP-05 | 导出行数/筛选一致性 | 各 export | 导出行数=列表 count；同一筛选 | import-export 冒烟 | A |
| IMP-06 | 异步导出任务 | `/api/admin/export-jobs/**` | 状态 CAS、7 天过期、跨人 404 | 仅冒烟，无单测 | A/C |

### 3.8 AI 导购

| ID | 需求/功能 | 角色 | 页面 | 接口 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AI-01 | 能力探测/无 Key 降级 | 未登录 | /ai | `/api/duoduo/capabilities` | Key 空→`chatAvailable=false`；有 Key→llm | ai-smoke | A（无 Key 场景 NOT TESTED） |
| AI-02 | 普通问答/多轮上下文 | 买家/游客 | /ai | `/api/duoduo/chat` | 回复真实、上下文连续 | ai-smoke、Newman A | A/M |
| AI-03 | SSE 流式/中断 | 买家 | /ai | `/chat/stream` | 事件顺序、逐 token、断连不写坏状态 | ai-smoke、手工 | A/M |
| AI-04 | 工具调用/卡片 | 买家 | /ai | tools + `cards` | 订单/券/通知/导航卡真实、无卡不伪造 | CardAssemblerTest、Newman C | A |
| AI-05 | 加购二次确认 | 买家 | /ai | `/api/duoduo/confirm` | 无令牌/错令牌/重复确认拒绝 | ai-smoke | A |
| AI-06 | 记忆 CRUD/高风险确认 | 买家 | /ai/memory | `/api/duoduo/memory/**` | 低风险直写；高风险两步；越权 401 | AiMemoryServiceTest、Newman B | A |
| AI-07 | 防注入/外链/医疗红线 | 任意 | /ai | chat | DataFence、LinkGuard、医疗拒答 | 单测、Newman D | A |
| AI-08 | 模型回退/额度/超时 | 系统 | — | DashScope | 429/5xx/404 回退；401/400 不回退；流式已输出不回退 | DashScopeToolClientTest 6 项 | A/C（真实额度耗尽 NOT TESTED） |
| AI-09 | 限流/游客配额 | 游客 | /ai | chat | 3 轮限制、IP 限流 | IpRateLimiterTest、ai-smoke | A |

### 3.9 小程序与移动端

| ID | 需求/功能 | 角色 | 页面 | 验收点 | 现有测试 | 本轮 |
| --- | --- | --- | --- | --- | --- | --- |
| MP-01 | 登录/2FA/购买主链路 | 买家 | 小程序 | 真机可用 | 无自动化 | BLOCKED（无真机/开发者工具） |
| MP-02 | 页面返回/登录过期/失败态 | 买家 | 小程序 | 状态正确 | 无 | BLOCKED |
| MP-03 | 小屏/大屏适配 | 买家 | Web | 375/768/1440 可用 | 部分 UI 检查 | M（抽样） |

## 4. 现有自动化覆盖评价

| 类型 | 现状 | 问题 |
| --- | --- | --- |
| 单测 | 10 模块共 114 个 `*Test.java` | `ExportJobService`、`SeckillService`、`SupportService`、`CoinTaskService` 无单测，只靠冒烟 |
| 接口冒烟 | `scripts/` 下 41 个 smoke 脚本 | 多数脚本同时承担“造数+断言”，依赖历史夹具；部分只断言 200 |
| UI 检查 | `scripts/e2e/` 约 60 个 `.mjs` | 依赖固定账号/固定商品；未覆盖小屏与真机 |
| 负向矩阵 | `docs/manuals/negative-test-matrix.md` 50 条规则 | 文档声称覆盖，需逐条抽验；并发类多数未实测 |
| AI E2E | `docs/qa/ai-butler-e2e.postman_collection.json` 24 请求/25 断言 | 依赖通知夹具，需先重置未读；无并发/断流用例 |

## 5. 基线缺口（文档 vs 实现）

| 编号 | 类型 | 内容 | 影响 |
| --- | --- | --- | --- |
| G1 | 文档缺失 | README 引用《产品需求原型 PRD v0.1》，仓库内不存在该文件 | 需求验收只能以 README/PLAN/OpenAPI 为准，存在口径歧义 |
| G2 | 文档过时 | README 顶部仍写“生产已切 `qwen3.8-flash`”；实际 2026-10-29 已换新 Key 与 3 模型列表 | 运维/验收误判 |
| G3 | 文档过时 | AGENTS.md 写“10 个 healthy 容器 / 19 套 1424 断言 / 6 套 244 浏览器断言”；实际 18 容器、41 个 smoke 脚本、约 60 个 UI 脚本 | 基线数字不可作为验收依据 |
| G4 | 设计漂移 | ARCHITECTURE 描述“8 业务 + 4 基础服务”，实际为 8 业务 + 网关 + 3 前端 + 5 基础设施；无独立搜索服务（ES 嵌在商品域） | 架构文档与部署形态不一致 |
| G5 | 文档口径 | PLAN 顶部“AI 单测 67/0”为阶段数字，当前 AI 测试类 18 个、用例数更多 | 数字过时，不影响功能 |
| G6 | 覆盖缺口 | 无单测：ExportJobService / SeckillService / SupportService / CoinTaskService | 仅冒烟覆盖，回归精度低 |
| G7 | 覆盖缺口 | 秒杀预算/每人上限/并发发币、异步导出跨人下载、SSE 断流、ES/Redis 故障降级 | 本轮只做代码审查或有限实测，不能宣称已验证 |

> 以上 G1-G7 在 `execution-report.md` 中逐项给出实际核对结果；功能缺陷另立 `defects.md` 编号。
