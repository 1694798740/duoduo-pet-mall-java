# 缺陷清单（独立 QA · 2026-10-30）

> 只把“实际复现/实际执行”的条目列为缺陷；代码审查但未复现的放 `code-risks.md`。
> 级别：P0 资金/数据丢失/严重越权/系统不可用；P1 核心流程不可完成/状态严重错误；P2 非核心功能错误/边界/明显体验问题；P3 体验/文案/文档/测试资产。
> 本轮 **没有确认的 P0/P1 产品缺陷**。

## DEF-001 · 商品详情主图裂图

| 项 | 内容 |
| --- | --- |
| 级别 | **P2** |
| 类型 | 已复现产品缺陷（前端） |
| 模块 | 买家商城 / 商品详情 |
| 需求来源 | 商品详情页必须展示商品主图；`docs/test-accounts.md` 与 UI 检查均要求商品图可显示 |
| 环境 | 本机 Docker，`http://localhost:8088`，商品 1001 |
| 前置 | 无（匿名即可） |
| 复现步骤 | 1. 打开 `http://localhost:8088/product/1001`；2. 观察主图；3. DevTools 看主图 `src` 与响应 |
| 预期 | 主图正常显示；`/img/products/1001.svg` 应被解析为 `/img/products/p01.jpg`，或返回真实图片 |
| 实际 | 主图裂图；`curl -I /img/products/1001.svg` 返回 `200 + Content-Type: text/html`，body 是 `index.html`；浏览器 `naturalWidth=0` |
| 证据 | `evidence/product-image-broken.txt`、`evidence/shot-product-detail.png`、`evidence/ui-product-detail-ui-check.mjs.log`（18/1） |
| 影响 | 所有走 `/img/...` 语义路径商品的主图在详情页裂图；首页本次独立取证未复现（`homeBroken=[]`） |
| 疑似根因 | `frontend/apps/mall/src/pages/ProductDetail.tsx:174-179` 在统一解析后又把未解析的 `card.mainImage` 插到首位；nginx SPA fallback 把缺失静态资源回退成 `index.html`（200） |
| 修复建议 | 主图改用 `productImage(card.id, card.mainImage)`；nginx 对 `/img/**`、`/assets/**` 缺失返回 404，禁止回退 index.html |
| 必补回归 | 首页/详情/订单/收藏/分享所有 `img` 断言 `naturalWidth>0`；`/img/**` 缺失必须 404 且 `Content-Type` 不是 text/html |

## DEF-002 · AI 推荐场景响应最长 97 秒

| 项 | 内容 |
| --- | --- |
| 级别 | **P2** |
| 类型 | 已复现产品缺陷（性能/体验） |
| 模块 | AI 导购 |
| 需求来源 | AI 对话需在可接受时间内返回；SSE 首字/整轮时延应可控 |
| 环境 | 本机 Docker，网关 8080，真实 DashScope，模型 `qwen3.8-max-0902,...` |
| 前置 | 游客新 Cookie；商品库有猫粮 |
| 复现步骤 | 新会话发送「推荐一款猫粮」，等待完整 JSON；再发「幼猫粮有什么推荐」 |
| 预期 | 首轮/推荐场景应在数秒~十几秒内完成；至少不超过前端/网关超时 |
| 实际 | 第一轮 97.0s（rec=3）；追问 51.8s（rec=1）；简单「你好」首轮 29.6s，后续 8.8/9.3s |
| 证据 | `evidence/ai-latency.txt` |
| 影响 | 用户长时间无响应；非流式 `/chat` 经 nginx 60s 会被切断；SSE 180s 也接近理论上限 |
| 疑似根因 | `AgentChatService.java:221-225` 最多 4 步，每步各自调用 `chatWithTools`；`DashScopeToolClient` 的 55s 预算是“单次调用”而非“整轮”，缺少整轮 deadline；主模型 `qwen3.8-max-0902` 推理耗时高 |
| 修复建议 | 增加整轮 deadline（如 45~60s）并在每步开始前检查剩余预算；推荐类场景减少 tool 步数；评估把 `qwen3.8-27b` 作为主模型或降低 max 模型使用 |
| 必补回归 | 固定 5 条推荐/多轮提示词的 p50/p95 时延断言；超时后必须返回可解释的降级回复，不能挂死 |

## DEF-003 · AI 冒烟 7 项假红（测试资产）

| 项 | 内容 |
| --- | --- |
| 级别 | **P2** |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | 自动化测试 / AI |
| 需求来源 | 冒烟脚本应可独立、可重复执行 |
| 环境 | 本机，`scripts/ai-smoke.ps1` |
| 复现步骤 | 直接运行 `ai-smoke.ps1`（不设 `DUODUO_SMOKE_TOKEN`） |
| 预期 | 脚本自建隔离会话/登录态，断言稳定 |
| 实际 | 37 PASS / 7 FAIL；6 项因共用同一个游客 WebSession 超过 3 轮，收到配额文案；1 项因 SQL 现场建的犬粮 SPU 未进检索索引，`search_products` 返回空 |
| 证据 | `evidence/smoke-ai-smoke.ps1.log`、`evidence/ai-latency.txt` |
| 影响 | 发布门禁假红，长期会训练团队忽略 AI 冒烟；不能证明 AI 推荐链路真实通过 |
| 修复建议 | 按测试段落使用独立游客会话或登录令牌；犬粮夹具改为走真实发布/索引同步，或直接断言 SQL 召回路径；把“游客 3 轮”断言的会话隔离到独立 device |
| 必补回归 | 同一脚本连续运行 3 次结果一致；每次断言总数守卫 |

## DEF-004 · 后台入口 UI 检查 3 项假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/admin-entry-ui-check.mjs` |
| 实际 | 71 PASS / 3 FAIL：头像下拉 helper 返回 `undefined`，导致“下拉展开/进入管理后台/点击跳转”三项失败 |
| 人工复核 | 浏览器代理用平台运营账号真实点击：顶栏“管理后台”、头像下拉“🛠️ 进入管理后台”、点击跳转 `admin.localhost:8090/login` **均通过** |
| 证据 | `evidence/ui-admin-entry-ui-check.mjs.log`、`evidence/shot-admin-menu.png` |
| 建议 | 修复 helper 的返回值/选择器；该 3 项不应作为产品失败 |

## DEF-005 · 售后 UI 检查 1 项假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/aftersale-ui-check.mjs` |
| 实际 | 43 PASS / 1 FAIL：`订单列表能看到这笔订单` 失败，但紧接着“订单卡片出现申请售后入口”PASS，退款/托管/订单状态断言全部 PASS |
| 证据 | `evidence/ui2-aftersale-ui-check.mjs.log` |
| 建议 | 定位订单号文本选择器/渲染时序；产品侧该链路未发现异常 |

## DEF-006 · 首页 UI 检查 1 项偶发假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/home-ui-check.mjs` |
| 实际 | 8 PASS / 1 FAIL（首页商品图裂图）；独立浏览器取证 `homeBroken=[]`，同一时刻首页无破图 |
| 证据 | `evidence/ui-home-ui-check.mjs.log`、`evidence/browser-capture.log` |
| 结论 | 不据此判定首页产品缺陷；详情页裂图另立 DEF-001 |

## DEF-007 · README 生产模型口径过时

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `README.md:17` 仍写“生产已切 `qwen3.8-flash`” |
| 实际 | 2026-10-29 已换新账号 Key 与 `qwen3.8-max-0902,qwen3.8-27b,kimi-k3`；旧 Key 已 403 |
| 影响 | 运维按 README 判断模型/排障会出错 |
| 建议 | README 顶部改为当前模型列表并注明“以云端 `.env` 为准” |

## DEF-008 · 测试账号文档与实际角色不一致

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `docs/test-accounts.md` 写备用运营（手机号 `136****6850`）为 OPERATOR |
| 实际 | 数据库该账号 `role=USER`；用它做“跨运营下载”测试得到 403（门禁正常） |
| 影响 | 测试前置错误，导致误判产品越权逻辑 |
| 建议 | 更新文档或改用真实 OPERATOR 账号；测试前先查库校验角色 |

## DEF-009 · PRD 原文件缺失

| 级别 | P3 |
| --- | --- |
| 类型 | 需求基线缺口 |
| 位置 | README 引用《多多宠物商店 · 产品需求原型 PRD v0.1》，仓库内不存在 |
| 影响 | 验收只能以 README/PLAN/OpenAPI/页面推断，存在需求歧义 |
| 建议 | 把 PRD 纳入仓库或明确“以接口契约+页面为验收基线” |

## DEF-010 · AGENTS/README 自动化数字过时

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `AGENTS.md` 写“10 healthy 容器 / 19 套 1424 断言 / 6 套 244 浏览器断言” |
| 实际 | 18 容器、41 个 smoke 脚本、约 60 个 UI 脚本；本轮 11 套冒烟 1053 断言、9 套 UI 289 断言 |
| 影响 | 用旧数字当验收基线，容易得出“全绿”的错误结论 |
| 建议 | 以 `scripts/run-all-smoke.ps1` 与 `scripts/e2e/run-ui-checks.ps1` 的动态统计为准，文档只写入口不写固定数字 |

## DEF-011 · 异步导出任务创建/下载没有审计留痕

| 项 | 内容 |
| --- | --- |
| 级别 | **P2** |
| 类型 | 已复现审计缺口（自动化断言 + 代码静态） |
| 模块 | 交易域 / 运营导出任务 |
| 需求来源 | 后台写操作/敏感数据操作应可审计；`audit-log-smoke` 的 S4 规则要求后台写端点带 `@Audited` |
| 复现 | 运行 `scripts/audit-log-smoke.ps1`：57 PASS / 1 FAIL，失败项 `S4 后台写端点 @Audited 覆盖 69/72`，明细含 `AdminExportJobController.java(写=1 注解=0)` |
| 实际 | `AdminExportJobController#create` 没有 `@Audited`；`ExportJobService` 也没有任何审计调用。类注释写“导出是只读动作，不写审计日志”，但**创建任务本身是写操作**，且导出内容含全量收货信息，只有下载次数记录，无法回答“谁在何时导出了哪些筛选条件的数据”。 |
| 证据 | `evidence/smoke2b-audit-log-smoke.ps1.log`；`AdminExportJobController.java:37,50`；`ExportJobService.java`（无 audit） |
| 影响 | 敏感数据导出缺少操作人/时间/筛选条件审计，合规与事后追责存在空洞 |
| 修复建议 | 为创建/下载增加 `AuditAction.EXPORT_JOB_CREATE/EXPORT_JOB_DOWNLOAD` 并落审计；或在 `ExportJobService` 内显式写审计 |
| 必补回归 | 创建任务→查审计表有记录；他人下载仍 404；审计记录不可删改 |

## DEF-012 · 审计冒烟把 import dry-run 计为“写端点”（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/audit-log-smoke.ps1` |
| 实际 | S4 明细还包含 `AdminCatalogController.java(写=16 注解=14)`；差额 2 是 `categories/import/dry-run` 与 `brands/import/dry-run`，两者只预演、不落库，按语义不应要求 `@Audited` |
| 证据 | `evidence/smoke2b-audit-log-smoke.ps1.log` |
| 影响 | 把“只读预演”误计为写操作，导致审计覆盖断言假红；真正的缺口（DEF-011）反而被噪声掩盖 |
| 建议 | 脚本按“是否真正写库”分类，排除 dry-run；或维护一份显式豁免清单 |

## DEF-013 · AI 预算约束可被模型绕过（1 元预算仍推荐 129 元猫粮）

| 级别 | P2 |
| --- | --- |
| 类型 | 已复现产品缺陷（约束未在服务端强制） |
| 模块 | AI 导购 / 商品搜索 |
| 复现 | 同一游客发送「预算 1 元以内的猫粮」（`clarified=true`），实际返回麦富迪 129 元起、皇家 158 元起 |
| 证据 | `evidence/fix-def003-final2.log`；工具日志中先有一次 `search_products` 带 `budgetMax=100`，随后又有一次不带预算的调用并返回商品 |
| 根因 | `ToolContext` 不携带 `SlotState`；`SearchProductsTool` 完全信任模型传入的 `budgetMax`。模型可以“带预算查一次 -> 不带预算再查一次”，服务端没有强制以本轮槽位为准 |
| 影响 | 用户明确设置的预算可能被绕过，属于导购可信度问题 |
| 修复建议 | 把本轮 `SlotState` 传入工具上下文，`search_products` 用服务端槽位覆盖/补齐预算；或在输出护栏按预算过滤 recommendations |
| 修复 | `6f88ad3`：新增 `ToolConstraintContext`（ThreadLocal）承载本轮槽位；`SearchProductsTool` 用 `SlotState.budgetMin/MaxCents` 覆盖模型参数（模型只能收紧、不能放宽）；`AgentChatService` 在槽位合并后 `set`、`finally` 中 `clear`，避免线程池串味 |
| 验证 | 新增 `SearchProductsToolTest` 两条用例（模型不传预算 / 传更宽预算），修复后 **2/0**（`evidence/fix-def013-test.log`）；重建 AI 容器后真机复测「预算 1 元以内的猫粮」→ **22.9s，recommendations=0**，不再返回 129 元猫粮 |
| 状态 | **已修复**（提交 `6f88ad3`） |
| 剩余风险 | 仅有预算下限时不会拦截低于下限的商品（区间语义与 SQL 过滤一致，属既有设计）；品类约束仍由检索层负责 |

## DEF-014 · 「推荐一下」没有返回结构化澄清选项

| 级别 | P2 |
| --- | --- |
| 类型 | 已复现产品/模型行为 |
| 模块 | AI 导购 / 澄清 |
| 复现 | 游客发送「推荐一下」：回复文本在追问“猫/狗、年龄、预算”，但 `data.clarify=null`，`options=0` |
| 证据 | `evidence/fix-def003-final2.log`；`scripts/ai-smoke.ps1` 断言 `clarify.options >= 2` 失败 |
| 影响 | 前端无法渲染可点选项，用户只能手动输入；与澄清交互设计不一致 |
| 修复建议 | 在 IntentRouter/AgentChatService 中为短句/无约束查询强制生成结构化 `clarify`，不依赖模型自由文本 |
| 修复 | `b3a8ac4`：`AgentResult` 增加 `clarify`、`AgentEvent` 增加 `clarify` 事件、非流式响应透传；`AgentChatService.clarifyFor` 在「PRODUCT_SEARCH + 无宠物/预算/品类 + 未澄清过」时直接反问，选项取真实一级类目（≥2 个才反问，否则退回模型）；放在模型调用之前，不依赖模型自由文本 |
| 验证 | 新增 `AgentChatServiceClarifyTest` **5/0**；`ai-smoke` 3f 四项全绿：触发澄清、选项 ≥2、不夹带推荐、`clarified=true` 不再反问（`evidence/fix-def014-smoke.log`，41 PASS / 2 FAIL 的两项是模型冷却导致的 503，与本缺陷无关） |
| 状态 | **已修复**（提交 `b3a8ac4`） |
| 剩余风险 | 类目服务不可用（选项 <2）时退回模型自由回答；前端 `clarified` 标记按标签页会话保存，刷新后可能再次反问一次 |

## DEF-015 · 「预算没听懂」文案与既有验收口径不一致

| 级别 | P3 |
| --- | --- |
| 类型 | 已复现（文案/行为） |
| 模块 | AI 导购 / 预算解析 |
| 复现 | 发送「有没有便宜划算的猫粮」，回复是“没拿到可推荐商品，请补充预算…”，不含既有验收要求的「没听清你的预算」 |
| 证据 | `evidence/fix-def003-final2.log` |
| 影响 | 用户仍得到追问，但既有自动化断言红；文案口径不统一 |
| 修复建议 | 明确预算未解析的统一话术，或把断言改为语义断言（包含“预算/金额”追问即可） |
| 修复 | `b3a8ac4`：`ConstraintParser` 已有的「提了预算但没解析出数字」状态此前在 Agent 链路被丢弃；`AgentChatService` 注入解析器，在输出护栏之后用 `withBudgetNote` 追加「（我没听清你的预算数字，这次没按预算筛）」，同义句已存在时不重复追加 |
| 验证 | 新增 `AgentChatServiceBudgetNoteTest` **3/0**；真机「有没有便宜划算的猫粮」→ 200，reply 含「没听清你的预算」（`evidence/fix-def015-live.log`） |
| 状态 | **已修复**（提交 `b3a8ac4`） |
| 剩余风险 | 该轮实测 67.2s（27B 多步工具调用），属 DEF-002 残余时延，不是文案问题 |

## 本批修复记录（2026-10-30）

| 缺陷 | 状态 | 修复提交 | 修改文件 | 验证 | 剩余风险 |
| --- | --- | --- | --- | --- | --- |
| DEF-001 商品详情主图 | **已修复** | `480fc43` | `ProductDetail.tsx`、`product-detail-ui-check.mjs` | 修复前 18/2 红；修复后 **20/0**，截图 `shot-product-detail.png` | 首页/订单/收藏等使用统一解析，未发现同根因 |
| DEF-002 AI 整轮时延 | **部分修复** | `72dc1aa` | `AgentChatService`、`DashScopeToolClient`、`LlmToolClient`、`application.yml`、`docker-compose.yml`、`DashScopeToolClientTest` | 单测 **7/0**；27B+60s 预算实测推荐 **48.3s / 42.0s / 67.2s**（原 97s）；预算耗尽返回收口话术而非 503 | 27B 多步工具调用仍可到 60s+（非流式经 nginx 60s 会 504；页面走 SSE 180s 不受影响）；一次模型超时会让三个模型全部进入 60s 冷却，见 CR-09 |
| DEF-003 AI 冒烟假红 | **基本修复** | `a2bb053` | `ai-smoke.ps1`、`catalog-fixture.ps1` | 游客会话隔离、多轮改用 `sessionId`、移除不可即时索引的犬粮夹具；最新全量 **41 PASS / 2 FAIL**，两项均为模型冷却导致的 503（`evidence/fix-def014-smoke.log`）；1 元预算与文案已由 DEF-013/015 单独真机验证通过 | 模型冷却窗口内的整段冒烟仍会假红，需等冷却或调整冷却策略（CR-09） |
| DEF-011 导出审计 | **已修复** | `cb14828` | `AuditAction`、`AdminExportJobController`、`AuditActionTest` | `audit-log-smoke` **58/0**；回库确认 `EXPORT_JOB_CREATE`/`EXPORT_JOB_DOWNLOAD` 两条记录 | 无 |
| DEF-012 审计冒烟 dry-run 假红 | **已修复** | `cb14828` | `audit-log-smoke.ps1` | 同上 58/0 | 无 |
| DEF-013 预算约束被绕过 | **已修复** | `6f88ad3` | `ToolConstraintContext`、`SearchProductsTool`、`AgentChatService`、`SearchProductsToolTest` | 单测 **2/0**（`evidence/fix-def013-test.log`）；真机「预算 1 元以内的猫粮」→ 28.1s、rec=0（`evidence/fix-def015-live.log`） | 仅有下限时不过滤低于下限商品（区间语义，既有设计） |
| DEF-014 结构化澄清缺失 | **已修复** | `b3a8ac4` | `AgentResult`、`AgentEvent`、`AgentCommand`、`AiController`、`AgentChatService`、`AiGuru.tsx`、SDK 类型、`AgentChatServiceClarifyTest` | 单测 **5/0**；ai-smoke 3f 四项全绿（触发/选项≥2/无推荐/不再反问） | 类目不可用（选项<2）时退回模型；刷新页面后可能再反问一次 |
| DEF-015 预算未解析文案 | **已修复** | `b3a8ac4` | `AgentChatService`、`AgentChatServiceBudgetNoteTest` | 单测 **3/0**；真机 reply 含「没听清你的预算」（`evidence/fix-def015-live.log`） | 该轮 67.2s，属 DEF-002 残余 |

**未修复**：DEF-004~010（UI 假红、文档口径，P3）。
**回归**：全量 Maven **1132 tests / 0 fail / 0 error / 5 skip / BUILD SUCCESS**（`evidence/fix-def014-full-regression.log`）；前端 `typecheck` 0 错误、`build:mall` 通过（`evidence/fix-def014-*.log`）；商品详情 UI **20/0**；审计冒烟 **58/0**；AI 冒烟 **41/2**（2 项为模型冷却 503，已单列 CR-09）。

> 说明：DEF-003~006、DEF-012 是“测试资产缺陷”，不是产品功能缺陷；单独列出是为了避免把假红当产品问题，也避免把测试资产问题洗成产品通过。

## 统计

| 级别 | 数量 | 条目 |
| --- | --- | --- |
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 6 | DEF-001、DEF-002、DEF-003、DEF-011、DEF-013、DEF-014 |
| P3 | 9 | DEF-004~DEF-010、DEF-012、DEF-015 |
