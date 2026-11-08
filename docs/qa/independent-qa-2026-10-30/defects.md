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
| 修复 | `72dc1aa` 整轮预算 + 单次 30s；`eb1a1d0`：nginx 为非流式 `/api/duoduo/chat` 单独设 `proxy_read_timeout 180s`（与 SSE 对齐），不再被通用 `/api/` 的 60s 提前切断；`03cf487`：整轮预算 60→75s（60s 会在多步追问轮提前收口成 rec=0），容器内 `DUODUO_AI_TURN_BUDGET_SECONDS=75` 已确认 |
| 验证 | 单测 **7/0**；27B 主模型实测推荐 **48.3s / 42.0s / 50.9s / 67.2s**（原 97s）；经 nginx 8088 非流式请求 50.9s 返回 200（`evidence/fix-def002-nginx-live.log`）；`ai-smoke` 多轮追问段全绿（`evidence/fix-def003-budget75-smoke.log`） |
| 状态 | **已修复**（提交 `72dc1aa` + `eb1a1d0` + `03cf487`） |
| 剩余风险 | 27B 多步工具调用仍可能 50~75s（用户可感知的慢，但不再 504、不再被 60s 截断）；一次超时触发全模型冷却的问题已由 CR-09 修复 |

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
| 修复 | `a2bb053` 会话隔离/夹具；`eb1a1d0` 模型冷却探针（CR-09）；`03cf487` 整轮预算 60→75s + 冒烟客户端超时 120s（60s 会让多步追问提前收口成 rec=0） |
| 验证 | 全量 **43 PASS / 0 FAIL**（`evidence/fix-def003-budget75-smoke.log`），覆盖推荐真实性、多轮追问、预算过滤、澄清、游客 3 轮与限流 |
| 状态 | **已修复** |
| 剩余风险 | 断言依赖真实 DashScope：上游整体不可用时仍会红（属真实故障，不是假红） |

## DEF-004 · 后台入口 UI 检查 3 项假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/admin-entry-ui-check.mjs` |
| 实际 | 71 PASS / 3 FAIL：头像下拉 helper 返回 `undefined`，导致“下拉展开/进入管理后台/点击跳转”三项失败 |
| 人工复核 | 浏览器代理用平台运营账号真实点击：顶栏“管理后台”、头像下拉“🛠️ 进入管理后台”、点击跳转 `admin.localhost:8090/login` **均通过** |
| 证据 | `evidence/ui-admin-entry-ui-check.mjs.log`、`evidence/shot-admin-menu.png` |
| 建议 | 修复 helper 的返回值/选择器；该 3 项不应作为产品失败 |
| 修复 | `eb1a1d0`：`openUserMenu` 改为点 `.app-header button.icon-btn.avatar-btn`（原 `.icon-btn` first 命中的是购物车图标），并等待 `.user-menu` 可见 |
| 验证 | 修复前 **71/3**（`evidence/fix-def004-admin-entry.log`）；修复后 **74/0**（`evidence/fix-def004-admin-entry-after.log`） |
| 状态 | **已修复** |

## DEF-005 · 售后 UI 检查 1 项假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/aftersale-ui-check.mjs` |
| 实际 | 43 PASS / 1 FAIL：`订单列表能看到这笔订单` 失败，但紧接着“订单卡片出现申请售后入口”PASS，退款/托管/订单状态断言全部 PASS |
| 证据 | `evidence/ui2-aftersale-ui-check.mjs.log` |
| 建议 | 定位订单号文本选择器/渲染时序；产品侧该链路未发现异常 |
| 修复 | `eb1a1d0`：订单列表按设计**不显示订单号**（订单号只在详情页），断言改为「商品标题」识别这笔订单 |
| 验证 | 修复前 **43/1**；修复后 **44/0**（`evidence/fix-def005-aftersale.log`） |
| 状态 | **已修复** |

## DEF-006 · 首页 UI 检查 1 项偶发假红（测试资产）

| 级别 | P3 |
| --- | --- |
| 类型 | 测试资产缺陷（非产品缺陷） |
| 模块 | `scripts/e2e/home-ui-check.mjs` |
| 实际 | 8 PASS / 1 FAIL（首页商品图裂图）；独立浏览器取证 `homeBroken=[]`，同一时刻首页无破图 |
| 证据 | `evidence/ui-home-ui-check.mjs.log`、`evidence/browser-capture.log` |
| 结论 | 不据此判定首页产品缺陷；详情页裂图另立 DEF-001 |
| 修复 | `eb1a1d0`：裂图断言从「点击详情之后」移回**首页**阶段（原来实际查的是详情页 img，名不副实）；判前先等所有 `img.complete`（lazy 图慢网络下 2.5s 未完成会偶发假红）；详情页改等 `networkidle` |
| 验证 | 修复后 **9/0**（`evidence/fix-def006-home.log`），连续两次一致 |
| 状态 | **已修复** |

## DEF-007 · README 生产模型口径过时

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `README.md:17` 仍写“生产已切 `qwen3.8-flash`” |
| 实际 | 2026-10-29 已换新账号 Key 与 `qwen3.8-max-0902,qwen3.8-27b,kimi-k3`；旧 Key 已 403 |
| 影响 | 运维按 README 判断模型/排障会出错 |
| 建议 | README 顶部改为当前模型列表并注明“以云端 `.env` 为准” |
| 修复 | `eb1a1d0`：README 改为 `DUODUO_AI_MODEL=qwen3.8-27b,qwen3.8-max-0902,kimi-k3`、单次 30s / 整轮 60s，并注明以云端 `.env` 为准，不再写死“已切某模型” |
| 状态 | **已修复** |

## DEF-008 · 测试账号文档与实际角色不一致

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `docs/test-accounts.md` 写备用运营（手机号 `136****6850`）为 OPERATOR |
| 实际 | 数据库该账号 `role=USER`；用它做“跨运营下载”测试得到 403（门禁正常） |
| 影响 | 测试前置错误，导致误判产品越权逻辑 |
| 建议 | 更新文档或改用真实 OPERATOR 账号；测试前先查库校验角色 |
| 修复 | `eb1a1d0`：`docs/test-accounts.md` 把 `13632356850` 标注为 **USER**（回库复核 `13632356850=USER`、`13684295254=OPERATOR`），并写明需要第二运营时的提权/降回步骤 |
| 状态 | **已修复** |

## DEF-009 · PRD 原文件缺失

| 级别 | P3 |
| --- | --- |
| 类型 | 需求基线缺口 |
| 位置 | README 引用《多多宠物商店 · 产品需求原型 PRD v0.1》，仓库内不存在 |
| 影响 | 验收只能以 README/PLAN/OpenAPI/页面推断，存在需求歧义 |
| 建议 | 把 PRD 纳入仓库或明确“以接口契约+页面为验收基线” |
| 修复 | `eb1a1d0`：README 顶部明确 **PRD 原文件不在本仓库**（DEF-009），验收基线 = README 接口清单 + PLAN 验收标准 + 运行中的 OpenAPI/页面行为；PRD 需另行归档 |
| 状态 | **已修复**（按“明确基线”方案，未伪造 PRD 文件） |

## DEF-010 · AGENTS/README 自动化数字过时

| 级别 | P3 |
| --- | --- |
| 类型 | 文档不一致 |
| 位置 | `AGENTS.md` 写“10 healthy 容器 / 19 套 1424 断言 / 6 套 244 浏览器断言” |
| 实际 | 18 容器、41 个 smoke 脚本、约 60 个 UI 脚本；本轮 11 套冒烟 1053 断言、9 套 UI 289 断言 |
| 影响 | 用旧数字当验收基线，容易得出“全绿”的错误结论 |
| 建议 | 以 `scripts/run-all-smoke.ps1` 与 `scripts/e2e/run-ui-checks.ps1` 的动态统计为准，文档只写入口不写固定数字 |
| 修复 | `eb1a1d0`：AGENTS.md 容器数与冒烟/UI 套数改为“以 compose 文件与脚本动态统计为准”（实测 18 容器、59 个脚本文件、58 个 UI 脚本），删除 10 healthy / 1424 断言 / 244 断言等过时数字 |
| 状态 | **已修复** |

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
| 验证 | 新增 `AgentChatServiceClarifyTest` **5/0**；`ai-smoke` 3f 四项全绿：触发澄清、选项 ≥2、不夹带推荐、`clarified=true` 不再反问（`evidence/fix-def014-smoke.log`，41 PASS / 2 FAIL 的两项是模型冷却导致的 503，与本缺陷无关）；重建 web 容器后浏览器实测 `http://localhost:8088/ai`：发送「推荐一下」→ 反问文本 + 4 个可点按钮（宠物主粮/宠物零食/宠物用品/健康医疗）、无商品卡；点「宠物主粮」正常发送并得到回复；API 复测「宠物主粮」rec=1 |
| 状态 | **已修复**（提交 `b3a8ac4`） |
| 剩余风险 | 类目服务不可用（选项 <2）时退回模型自由回答；前端 `clarified` 标记按标签页会话保存，刷新后可能再次反问一次；浏览器实测出现一次 `chat/stream` `ERR_ABORTED`（页面仍正常完成，未复现为功能问题） |

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

## DEF-016 · 主模型免费配额耗尽（403 FreeTierOnly）直接 503，不回退到有配额的备选模型（2026-11-08 云端实测）

| 项 | 内容 |
| --- | --- |
| 级别 | P1 |
| 类型 | 已复现产品缺陷（模型回退分类） |
| 模块 | AI 导购 / 模型回退 |
| 复现 | 云端实测（2026-11-08）：`qwen3.8-27b` 免费额度耗尽返回 403 `AllocationQuota.FreeTierOnly`；同 Key 下 `qwen3.8-max-0902`、`kimi-k3` 仍可用，但 `DashScopeToolClient#ensureSuccess` 把 403 归为「换模型也没用」直接抛 DEGRADED → AI 推荐全量 503（澄清功能不依赖模型，仍正常） |
| 根因 | 百炼免费额度**按模型独立计费**，而错误分类把所有 403 当成凭据级错误；与 404 `model_not_found` 的既有例外同类，漏了配额类 403 |
| 证据 | `evidence/cloud-demo-verify-20261108-final.log`（29/1）；云端日志 `LLM 调用失败 status=403 ... FreeTierOnly` 后直接 `DEGRADED`；模型探测 `27b=403 / max=200 / kimi=200` |
| 修复 | `ensureSuccess` 新增：403 且响应含 `AllocationQuota` 标识 → 归为 `RetryableModelException`（按模型配额耗尽，换下一个）；账号级封禁仍不回退。云端临时缓解：`.env` 模型顺序改为 `kimi-k3,qwen3.8-max-0902,qwen3.8-27b`（kimi 有配额，备份 `.env.bak-quota-20261108`） |
| 验证 | 失败测试先行：`fallsBackOnPerModelQuotaExhausted` 修复前红（`fix-def016-before.log`），修复后 **9/0**（`fix-def016-after.log`）；`duoduo-ai` 模块 **120/0**；部署后云端复测见下 |
| 状态 | **已修复** |
| 剩余风险 | 免费额度总有一天全部耗尽（当前仅 kimi/max 存活）；长期建议百炼账号充值或关闭仅免费模式 |

## DEF-017 · 消息中心对「闲置咨询」会话显示「商品咨询」标签（低优先级观察项，2026-11-08）

| 项 | 内容 |
| --- | --- |
| 级别 | P3（纯文案，功能不受影响：会话内容、listingId 上下文、回复全正确） |
| 类型 | 前端展示语义瑕疵 |
| 现象 | 浏览器实访（A3，`evidence/browser-20261108/messages-page.png`）：对闲置卖家的 LISTING 咨询会话，列表标签显示「商品咨询」；API 层面 `ConversationView.listingId` 正确回显、`consulting=true`，仅标签映射未区分 listing 与 product 会话 |
| 定位方向 | 前端消息中心对会话标签的映射按 `lastBizType`/`consulting` 渲染时未看 `listingId` 上下文；修法是在标签函数里优先判 `listingId`（闲置咨询）再判 `productId`（商品咨询） |
| 状态 | **待修**（演示不受阻塞；下次前端小修顺手处理） |

## 本批修复记录（2026-10-30）

| 缺陷 | 状态 | 修复提交 | 修改文件 | 验证 | 剩余风险 |
| --- | --- | --- | --- | --- | --- |
| DEF-001 商品详情主图 | **已修复** | `480fc43` | `ProductDetail.tsx`、`product-detail-ui-check.mjs` | 修复前 18/2 红；修复后 **20/0**，截图 `shot-product-detail.png` | 首页/订单/收藏等使用统一解析，未发现同根因 |
| DEF-002 AI 整轮时延 | **已修复** | `72dc1aa`、`eb1a1d0`、`03cf487` | `AgentChatService`、`DashScopeToolClient`、`LlmToolClient`、`application.yml`、`docker-compose.yml`、`nginx.conf`、`DashScopeToolClientTest` | 单测 **7/0**；27B 实测推荐 **48.3s / 42.0s / 50.9s / 67.2s**（原 97s）；nginx 非流式 180s、50.9s 返回 200；整轮预算 75s 后多轮追问段全绿 | 27B 多步仍可能 50~75s（慢但不再 504/截断） |
| DEF-003 AI 冒烟假红 | **已修复** | `a2bb053`、`eb1a1d0`、`03cf487` | `ai-smoke.ps1`、`catalog-fixture.ps1`、`DashScopeToolClient` | 全量 **43 PASS / 0 FAIL**（`evidence/fix-def003-budget75-smoke.log`） | 上游 DashScope 整体不可用时仍会红（真实故障） |
| DEF-004 后台入口 UI 假红 | **已修复** | `eb1a1d0` | `admin-entry-ui-check.mjs` | 71/3 → **74/0**（`evidence/fix-def004-admin-entry-after.log`） | 无（测试资产） |
| DEF-005 售后 UI 假红 | **已修复** | `eb1a1d0` | `aftersale-ui-check.mjs` | 43/1 → **44/0**（`evidence/fix-def005-aftersale.log`） | 无（测试资产） |
| DEF-006 首页 UI 偶发假红 | **已修复** | `eb1a1d0` | `home-ui-check.mjs` | 8/1 → **9/0 连续两次**（`evidence/fix-def006-home.log`、`fix-def006-home2.log`） | 依赖真实图片加载时序 |
| DEF-011 导出审计 | **已修复** | `cb14828` | `AuditAction`、`AdminExportJobController`、`AuditActionTest` | `audit-log-smoke` **58/0**；回库确认 `EXPORT_JOB_CREATE`/`EXPORT_JOB_DOWNLOAD` 两条记录 | 无 |
| DEF-012 审计冒烟 dry-run 假红 | **已修复** | `cb14828` | `audit-log-smoke.ps1` | 同上 58/0 | 无 |
| DEF-013 预算约束被绕过 | **已修复** | `6f88ad3` | `ToolConstraintContext`、`SearchProductsTool`、`AgentChatService`、`SearchProductsToolTest` | 单测 **2/0**（`evidence/fix-def013-test.log`）；真机「预算 1 元以内的猫粮」→ 28.1s、rec=0（`evidence/fix-def015-live.log`） | 仅有下限时不过滤低于下限商品（区间语义，既有设计） |
| DEF-014 结构化澄清缺失 | **已修复** | `b3a8ac4` | `AgentResult`、`AgentEvent`、`AgentCommand`、`AiController`、`AgentChatService`、`AiGuru.tsx`、SDK 类型、`AgentChatServiceClarifyTest` | 单测 **5/0**；ai-smoke 3f 四项全绿（触发/选项≥2/无推荐/不再反问） | 类目不可用（选项<2）时退回模型；刷新页面后可能再反问一次 |
| DEF-015 预算未解析文案 | **已修复** | `b3a8ac4` | `AgentChatService`、`AgentChatServiceBudgetNoteTest` | 单测 **3/0**；真机 reply 含「没听清你的预算」（`evidence/fix-def015-live.log`） | 该轮 67.2s，属 DEF-002 残余 |
| DEF-007 README 模型口径 | **已修复** | `eb1a1d0` | `README.md` | 模型列表/超时与 `.env` 对齐，注明以云端为准 | 云端 `.env` 仍是唯一权威 |
| DEF-008 测试账号角色 | **已修复** | `eb1a1d0` | `docs/test-accounts.md` | 回库复核 `13632356850=USER`、`13684295254=OPERATOR` | 需要第二运营时先提权并重登 |
| DEF-009 PRD 缺失 | **已修复** | `eb1a1d0` | `README.md` | 明确“PRD 不在仓库，验收基线=契约+PLAN+OpenAPI/页面” | PRD 交付物仍需另行归档 |
| DEF-010 自动化数字过时 | **已修复** | `eb1a1d0` | `AGENTS.md` | 改为以 compose/脚本动态统计为准，删除旧数字 | 无 |

**未修复**：无（DEF-001~015 已全部收口；未确认风险 CR-02/05/06/08 仍在 `code-risks.md`）。
**回归**：全量 Maven **1133 tests / 0 fail / 0 error / 5 skip / BUILD SUCCESS**（`evidence/fix-all-full-regression.log`）；前端 `typecheck` 0 错误、`build:mall` 通过；UI：后台入口 **74/0**、售后 **44/0**、首页 **9/0 连续两次**、商品详情 **20/0**；审计冒烟 **58/0**；AI 冒烟 **43/0**。
**云端**：2026-10-30 已部署 AI + 商城 Web 到 `8.163.71.44`（14 容器 healthy），云端验证澄清 4 选项 / 1 元 rec=0 / 多轮 rec=3→2 全通过，详见 `cloud-deploy-2026-10-30.md`。

> 说明：DEF-003~006、DEF-012 是“测试资产缺陷”，不是产品功能缺陷；单独列出是为了避免把假红当产品问题，也避免把测试资产问题洗成产品通过。

## 统计

| 级别 | 数量 | 条目 |
| --- | --- | --- |
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 6 | DEF-001、DEF-002、DEF-003、DEF-011、DEF-013、DEF-014 |
| P3 | 9 | DEF-004~DEF-010、DEF-012、DEF-015 |
