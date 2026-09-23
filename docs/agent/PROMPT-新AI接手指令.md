# 新 AI 接手 · 启动提示词（直接复制给下一个 AI）

> 用法：把下面 **【提示词正文】** 整段粘贴给接手的 AI（新会话第一条消息）。
> 配套：本仓库根 `AGENTS.md` 是 AI 的默认入口；本提示词要求它先读 `docs/agent/00~08` 再动手。

---

## 【提示词正文】

```
你现在接手一个已在开发中的真实项目，第一步不是写代码，而是**先读懂它并给出计划**。
请严格按下面的阶段推进，不要在「阶段 1、2 未完成」之前产出任何业务代码。

──────── 工作空间与边界 ────────
· 代码工作空间：D:\智能体项目（所有读写、构建、测试都在此空间内进行，不要到别处找代码）
· 主仓库（本任务唯一目标）：D:\智能体项目\duoduo-pet-mall-java
· 同目录下另有 D:\智能体项目\duoduo-pet-mall（Node 原型工程，**与主仓库无关，不要改、不要混用**）
· 该仓库是「多多宠物商店」：Java 微服务后端（Spring Boot 3 + Spring Cloud Alibaba）+ React 前端，
  本地用 Docker Compose 起 9 个容器（3 中间件 + 6 业务服务），资金走平台托管担保交易，支付用沙箱渠道（不接真实商户）。

──────── 阶段 1：读文件（按顺序读完，不要跳）────────
必读（项目自带的 AI 接管资料，先读这些能省掉大量猜测）：
1. D:\智能体项目\duoduo-pet-mall-java\AGENTS.md                     ← AI 入口：自检命令、硬规矩、交付清单
2. D:\智能体项目\duoduo-pet-mall-java\docs\agent\00-AGENT-接管指令.md  ← 硬规矩 C1~C10、工作循环、完成定义 DoD
3. D:\智能体项目\duoduo-pet-mall-java\docs\agent\01-项目目标与领域模型.md ← 业务目标、参与者、领域对象、主流程
4. D:\智能体项目\duoduo-pet-mall-java\docs\agent\02-代码地图与现状.md  ← 文件级索引（要改什么去哪个文件）
5. D:\智能体项目\duoduo-pet-mall-java\docs\agent\03-架构与不变式.md    ← INV-1~16（绝不能破的规则）
6. D:\智能体项目\duoduo-pet-mall-java\docs\agent\04-接口契约全集.md    ← 端点/认证/DTO 字段/枚举/错误码
7. D:\智能体项目\duoduo-pet-mall-java\docs\agent\05-开发任务手册.md    ← 六类开发场景的配方与代码骨架
8. D:\智能体项目\duoduo-pet-mall-java\docs\agent\06-测试与验收门禁.md  ← 什么改动跑哪些测试、断言怎么写
9. D:\智能体项目\duoduo-pet-mall-java\docs\agent\07-待办事项与验收标准.md ← 待办 A1~A13（含接口草案与验收标准）
10. D:\智能体项目\duoduo-pet-mall-java\docs\agent\08-环境运维与踩坑禁忌.md ← T-01~T-38 症状→根因→做法、禁忌
11. D:\智能体项目\duoduo-pet-mall-java\AGENTS.md 同级：docs\PLAN.md   ← 进度权威（里程碑/任务/变更记录/命令速查）
12. D:\智能体项目\duoduo-pet-mall-java\README.md                     ← 运行方式 + 全量接口清单
13. D:\智能体项目\.workbuddy\memory\MEMORY.md 与 memory\2026-09-*.md  ← 项目长期记忆与「值钱的坑」

按需读（用于核实事实，不要只信文档）：
14. docker-compose.yml（9 容器与端口）、pom.xml（9 模块 reactor、Java 21）、Dockerfile
15. docs\sql\00~06*.sql（五库 DDL + 商品种子 + 交易域增量列）、各模块 src\main\resources\db\schema.sql
16. 源码：先读你将要改的模块的 controller / service / dto / entity，再读依赖它的上游
17. scripts\*.ps1（14 套接口冒烟，1204 项断言）、scripts\e2e\*.mjs（5 套真实浏览器自测，180 项；另有 1 套 .ps1 夹具）
18. frontend\src\api\{client,endpoints,types}.ts 与 frontend\src\pages\（若要改前端）

阶段 1 的产出（必须先给我看，不要直接开始改代码）：
A. **理解报告**：技术栈与版本 / 服务与模块拓扑（谁调谁、端口、库）/ 主要模块职责 /
   关键数据流（下单→支付→发货→收货放款→评价、以及退款与补偿分支）/ 依赖关系与不变式清单。
B. **进度判断**：已完成到哪一步（引用 PLAN.md 的里程碑）、当前基线是什么（容器数、冒烟项数、最新提交）。
C. **待完成功能**：从 docs\agent\07 的 A1~A13 中列出，并指出**你判断的实现难点**与理由（例如跨服务一致性、幂等、并发、状态机边界）。
D. **问题清单**：任何不明确的需求、缺失的外部依赖、需要人决策的事项（例如真实支付资质、小程序 AppID、
   LLM 密钥、生产部署方案）。**有阻塞性问题就先问我，不要自行假设后编码**。
E. **开发计划**：优先级排序后的任务表，每项含「目标 / 涉及文件 / 交付物 / 验收标准 / 风险 / 依赖」。

──────── 阶段 2：等我确认计划 ────────
我会确认或调整优先级与范围。确认后你才开始写代码。若我的指令与仓库既有不变式冲突，
先指出冲突再改，不要默默绕过。

──────── 阶段 3：编码（确认后执行）────────
· 遵守 docs\agent\03 的全部不变式与 docs\agent\05 的代码骨架（分层：薄控制器 → 服务层业务规则 → 数据访问）。
· 每完成一个模块做一次编译检查；改动后必须重建受影响容器并等它 healthy。
· 每项任务都要有端到端断言覆盖（含反向场景），且夹具自建自毁、不污染种子数据。
· 交付前跑 docs\agent\06 的完整门禁；交付总结必须写清「跑了几套、各多少项、结果数字、自测暴露了什么缺陷、怎么修的」。
· 每轮结束更新 docs\PLAN.md（进度与变更记录）+ README（同一次提交），并按 docs\agent\00 §4 的清单收尾。

──────── 约束（必须遵守）────────
· 依赖版本：Java 21、Spring Boot 3.2、Spring Cloud 2023、Spring Cloud Alibaba 2023、MyBatis-Plus、
  MySQL 8.0.36、Redis 7.2、Nacos 2.3.2、React 18 + TypeScript 5.6(strict) + Vite 5。
  **新增任何第三方依赖前先说明用途与必要性**，不要随手引入。
· 接口约定：统一响应包络 {code,message,data,requestId,timestamp}；分页 {items,total,page,pageSize,pageCount}；
  身份只来自登录态（请求体不得出现 userId/sellerId）；服务间调用走内部接口 + X-Internal-Token 且裁剪字段；
  参数写错用 400（VALIDATION_ERROR），业务规则不满足用 422（BUSINESS_RULE），冲突 409，越权 403，不存在 404；
  新增公开接口要同时改网关白名单（只放行只读路径）。改接口必须同步 docs\agent\04 与 README §三。
· 代码规范：控制器只做绑定+调用+包响应；服务层不依赖 HttpServletRequest；SQL 全部参数化；
  金额整数分、时间 UTC、主键雪花 ID（id-type=input 的模块插入时必须显式赋 id）；
  注释写「为什么这么做」而非「做了什么」。
· 测试要求：跨服务改动至少跑上下游两套冒烟；前端改动跑 tsc --noEmit + vite build，UI 改动加真实浏览器自测；
  严禁修改既有断言使其通过（除非断言本身写错，且须在变更记录里说明旧/新口径）。
· 环境特例：Docker Desktop 需人工启动；Maven 必须用项目内 maven\settings.xml 并以 java 驱动 launcher
  （不能直接调 mvn.cmd）；单模块打包必须带 -am；前端必须用 http://localhost:3000（不能用 127.0.0.1；
  本机 5173 落在 Windows 排除端口段内，用不了）。
  以上细节与更多坑见 docs\agent\08（含 T-37「改了源码必须重建容器」、T-38「Node 无法创建子进程」），遇错先查那张表。
· 禁止：删改 .workbuddy\ 目录、把真实密钥/口令写进任何文件、跨库 JOIN、在网关白名单放写接口、
  跳过 Git hooks、未跑冒烟就交付。

现在请开始阶段 1：读文件 → 输出 A~E 五项 → 等我确认。
```

---

## 附：给使用者的两点提醒

1. **如果接手的 AI 有自动读取仓库入口的能力**（很多工具会优先读 `AGENTS.md`），可以只发一句：
   > 读 `D:\智能体项目\duoduo-pet-mall-java\AGENTS.md`，按其指引完成接管（先理解、后计划、再编码，
   > 编码就在 `D:\智能体项目` 这个工作空间内进行）。
2. **若你只想要某一块工作**（例如只做 `A1 历史订单托管单回补`），把提示词末尾替换成：
   > 本次范围仅 `docs\agent\07` 的 `A1`，其余任务只做理解、不实现；请按该条目的验收标准逐条对齐。
