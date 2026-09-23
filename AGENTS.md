# AGENTS.md — AI Agent 接管入口

> 本文件是**给 AI 编码代理**的入口，不是给人读的概览。人读版在 `docs/handover/`。
> 你（AI）的目标：**独立在本仓库继续开发**，不依赖任何人的口头补充。

## 0. 三分钟自检（开工前必须全部通过）

```bash
cd <repo>/duoduo-pet-mall-java

# 1) 容器基线：必须 9 个 healthy（mysql/redis/nacos + gateway/user/product/trade/fulfilment/marketing）
docker compose ps

# 2) 编译基线：BUILD SUCCESS（构建命令必须用下面这条，原因见 docs/agent/08）
java -classpath "D:/devsoft/apache-maven-3.9.4/boot/plexus-classworlds-2.7.0.jar" \
  "-Dclassworlds.conf=D:/devsoft/apache-maven-3.9.4/bin/m2.conf" \
  "-Dmaven.home=D:/devsoft/apache-maven-3.9.4" \
  "-Dmaven.multiModuleProjectDirectory=<repo>/duoduo-pet-mall-java" \
  "-Dmaven.legacyLocalRepo=true" \
  org.codehaus.plexus.classworlds.launcher.Launcher -ntp -DskipTests package

# 3) 冒烟基线：14 套 1204 项接口断言 + 5 套 180 项浏览器断言全绿，至少跑受你改动影响的那几套
```

任何一条不通过 → **先修基线，不要开始写新功能**。基线不绿时的改动无法验证。

## 1. 必读顺序（按此顺序读，不要跳）

| 顺序 | 文件 | 什么时候读 | 读完你能做什么 |
|---|---|---|---|
| 1 | `docs/agent/00-AGENT-接管指令.md` | 立刻 | 知道硬规矩、工作循环、完成定义（DoD） |
| 2 | `docs/agent/01-项目目标与领域模型.md` | 立刻 | 知道业务在做什么、谁是参与者、有哪些领域对象 |
| 3 | `docs/agent/02-代码地图与现状.md` | 立刻 | 知道每个文件在哪、当前完成到哪一步 |
| 4 | `docs/agent/03-架构与不变式.md` | 动代码前 | 知道哪些规则绝不能破 |
| 5 | `docs/agent/07-待办事项与验收标准.md` | 接任务前 | 知道下一步做什么、怎么算做完 |
| 6 | `docs/agent/04-接口契约全集.md` | 写/改接口时 | 端点、字段、枚举、错误码的精确契约 |
| 7 | `docs/agent/05-开发任务手册.md` | 开始写代码时 | 照抄的代码骨架与端到端配方 |
| 8 | `docs/agent/06-测试与验收门禁.md` | 提交前 | 该跑哪些命令、断言怎么写 |
| 9 | `docs/agent/08-环境运维与踩坑禁忌.md` | 报错时 | 症状→根因→正确做法 |

> 换会话/换 AI 的启动提示词（可直接粘贴给下一个 AI）：`docs/agent/PROMPT-新AI接手指令.md`
> —— 它要求新的 AI **先理解、给计划、提问题，经确认后才编码**，并明确「代码工作空间是 `D:\智能体项目`」。

## 2. 十条硬规矩（违反其一即为不合格交付）

1. **不动基线**：`docker compose ps` 非 9 healthy、或编译不通过时，先修基线。
2. **改完必跑冒烟**：跨服务改动至少跑上下游两套；前端改动跑 `tsc --noEmit` + `vite build`。
3. **幂等靠唯一索引**，不靠应用层「先查再插」，不引入分布式锁。
4. **不跨库查询**：跨域数据一律走对端服务的内部接口（带 `X-Internal-Token`），且**裁剪字段**。
5. **金额整数分、时间 UTC、主键雪花 ID**；`id-type: input` 的模块插入时必须显式赋 id。
6. **400 与 422 分清**：参数写错 = 400 `VALIDATION_ERROR`；业务规则不满足 = 422 `BUSINESS_RULE`。
7. **对外动词 ≠ 库内状态**（如 `APPROVE` vs `APPROVED`），必须显式映射。
8. **写接口在服务层校验归属**，绝不接受请求体里的 `userId` / `sellerId`。
9. **每轮开发完成**要更新 `docs/PLAN.md` + README（同一次提交）+ 云端台账 + 事项表状态。
10. **禁止**：删 `.workbuddy/`、改历史测试断言使其通过、跳过 Git hooks、把真实密钥写入仓库。

## 3. 仓库结构速览

```
duoduo-pet-mall-java/
├── pom.xml                    聚合父 POM（9 模块，Java 21）
├── docker-compose.yml         9 容器编排
├── Dockerfile                 业务服务共用镜像（temurin:21-jre-alpine，非 root）
├── docs/
│   ├── PLAN.md                ★ 进度权威（里程碑/任务/变更记录/命令速查）
│   ├── ARCHITECTURE.md        长期架构蓝图（12 服务，部分未落地）
│   ├── PROJECT-STATUS.md      已废弃（见文首横幅）
│   ├── sql/                   00~06 容器初始化 SQL（五库 DDL + 商品种子 + 交易域增量列）
│   ├── handover/              人读交接包 9 篇（含 manifest.json 云端 id）
│   └── agent/                 ★ 本 AI 接管包（00~08）
├── scripts/                   14 套接口冒烟 .ps1 + e2e/ 5 套真实浏览器自测（另 1 套夹具脚本）
├── duoduo-common/             响应/错误码/异常/雪花ID/UserContext/JWT/OperatorGuard
├── duoduo-common-web/         全局异常处理/请求ID/身份透传
├── duoduo-gateway/            路由 + JWT 校验 + 白名单 + 身份头注入（WebFlux）
├── duoduo-user/       :8081   账号/令牌/宠物档案/内部批量昵称
├── duoduo-product/    :8082   类目品牌/SPU-SKU/库存/闲置/商家商品/后台类目品牌审核
├── duoduo-trade/      :8083   购物车/订单/冻结/支付/托管/卖家中心/后台干预
├── duoduo-fulfilment/ :8084   评价与晒单 / 物流轨迹 / 售后仲裁 / 信用分
├── duoduo-marketing/  :8085   营销域：优惠券（领券中心 / 我的券包 / 锁·解·核销 / 补偿调度）
└── frontend/                  React 18 + TS strict + Vite 5（买家/商家/后台/评价/优惠券）
```

## 4. 交付动作清单（每次任务收尾照做）

- [ ] 编译通过（`package` 成功）
- [ ] 受影响的镜像重建并 healthy（`docker compose build <svc>-app && docker compose up -d <svc>-app`）
- [ ] 受影响冒烟脚本全绿（并在交付说明里写清**跑了几套、各多少项、结果**）
- [ ] 前端：`tsc --noEmit` 0 错误、`vite build` 通过（有 UI 改动再加对应 `*-ui-check.mjs`）
- [ ] `docs/PLAN.md`：里程碑/任务状态 + 本轮变更记录（含自测暴露的缺陷与修法）
- [ ] `README.md`：接口清单/模块/数字同步（**与代码同一次提交**）
- [ ] 云端台账同步（`.workbuddy/tools/sync_ledger.py`）
- [ ] 事项表（wb-issues）任务状态
- [ ] 若新增踩坑：写进 `docs/agent/08` 与项目记忆
