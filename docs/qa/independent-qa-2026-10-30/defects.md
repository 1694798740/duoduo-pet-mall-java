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

## 统计

| 级别 | 数量 | 条目 |
| --- | --- | --- |
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 4 | DEF-001 商品详情主图；DEF-002 AI 97s；DEF-003 AI 冒烟假红；DEF-011 导出任务无审计 |
| P3 | 8 | DEF-004~DEF-010（测试资产 + 文档）+ DEF-012（审计冒烟 dry-run 假红） |

> 说明：DEF-003~006、DEF-012 是“测试资产缺陷”，不是产品功能缺陷；单独列出是为了避免把假红当产品问题，也避免把测试资产问题洗成产品通过。
