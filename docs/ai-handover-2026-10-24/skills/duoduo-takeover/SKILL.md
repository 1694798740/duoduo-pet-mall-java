---
name: duoduo-takeover
version: 1.0.0
display_name: 多多宠物商店 · AI 接管
display_name_en: Duoduo Pet Mall AI Takeover
description: 接手「多多宠物商店」项目时使用。提供开工自检、代码地图、硬规矩、构建/部署顺序、测试门禁与待办清单，保证新 AI 无需真人协助即可继续开发。
description_zh: 接手多多宠物商店项目时使用。加载后按「自检 → 读地图 → 领任务 → 实现 → 三层验证 → 四处文档 → 提交」执行；强制遵守前端先构建 dist、禁止原生弹窗、跨库禁止 JOIN、幂等靠唯一索引、金额整数分/时间 UTC 等纪律。
description_en: Use when taking over the Duoduo Pet Mall project. Provides self-check, code map, hard rules, build/deploy order, test gates and backlog so a new AI can continue without human help.
agent_created: true
---

# 多多宠物商店 · AI 接管技能

## 何时使用

- 会话开始、需要继续本项目开发时；
- 需要判断“这个改动该动哪些文件”“提交前要跑什么”“为什么页面没生效”时；
- 换账号/换 AI 接手时。

## 第一步：三分钟自检（全绿才动手）

```bash
cd /d/智能体项目/duoduo-pet-mall-java
docker compose ps

export JAVA_HOME="/c/Program Files/Java/latest/jdk-21/jdk21"
/d/devsoft/apache-maven-3.9.4/bin/mvn -o -q -pl duoduo-product -am -DskipTests package

cd frontend && npm run typecheck

cd .. && NO_PROXY="*" node scripts/product-manage-r93-smoke.mjs
```

任一失败：先修基线，不写新功能。

## 文档地图（工作区）

`C:\Users\16947\WorkBuddy\2026-09-18-17-36-12\AI交接文档集-2026-10-24\`

| 需要知道 | 读 |
|---|---|
| 全貌与读法 | `00-接手索引.md` |
| 业务/角色/资金 | `01-项目介绍与业务模型.md` |
| 服务与端口 | `02-架构与技术栈.md` |
| 改哪儿 | `03-目录结构与代码地图.md` |
| 跑起来/部署 | `04-环境搭建与启动运维.md` |
| 规矩 | `05-开发规范与工作流.md` |
| 已做了什么 | `06-功能清单与完成度.md` |
| 表/接口 | `07-数据模型与接口契约.md` |
| 怎么验 | `08-测试体系与验收方法.md` |
| 下一步 | `09-待办任务与后续计划.md` |
| 什么叫做完 | `10-最终预期效果与验收标准.md` |
| 报错先查 | `11-踩坑手册与铁律.md` |

仓库内的进度权威：`docs/PLAN.md` 顶部最新一轮；自测手册 `docs/SELFTEST.md`。

## 硬规矩（违反即不合格）

1. **前端先构建再建镜像**：`cd frontend && npm run build:<app>` → `docker compose build <web-svc>` → `up -d --force-recreate`；或直接用 `scripts/web-up.sh`。
2. **禁止原生弹窗**：不用 `window.confirm/prompt/alert`；web 用 `useConfirm`，小程序用 `wx.showModal`。
3. **跨库禁止 JOIN**：跨域走 Feign + `X-Internal-Token`。
4. **幂等靠唯一索引**，不搞“先查再插”。
5. **归属只认登录态**，请求体里的 `userId/sellerId` 一律忽略。
6. **金额整数分、时间 UTC、雪花 ID 字符串**（禁止 Number 比较/排序）。
7. **可空字段更新**必须显式 `set(col, null)`；`eq(col,null)` 要写 `.isNull(col)`。
8. **改公共模块先 install**：`mvn -pl duoduo-common,duoduo-common-web -am -DskipTests install`。
9. **测试不可放宽**：不许改历史断言让其变绿；不许“接口 200 即通过”。
10. **每轮文档四处同步**：`docs/PLAN.md`、`README.md`、`docs/SELFTEST.md`、工作区《项目简报.md》。

## 单任务循环

1. 在 `09-待办任务与后续计划.md` 取一项，明确验收标准；
2. 按 `03-目录结构与代码地图.md` 定位代码，读现有实现与调用方；
3. 最小改动 → 单测；
4. 冒烟（正向/越权/边界 + 回库断言）；
5. 涉及界面 → 重建前端 + UI e2e（打容器产物）；
6. 回归受影响套件；
7. 更新文档与待办状态；
8. 提交（问题→根因→改动→验证）并推送。

## 常见症状快速定位

| 症状 | 去读 |
|---|---|
| 页面没变化 | `11` 第 1 条（dist 未重建） |
| `*.localhost` 502 | `11` 第 2 条（NO_PROXY） |
| 更新成功但库没变 | `11` 第 5 条（NOT_NULL） |
| ID 排序错乱 | `11` 第 6 条（JS 精度） |
| 审核 e2e 不进队列 | `11` 第 10 条（需命中风险规则） |
| Maven 离线缺包 | `11` 第 12 条（install-file） |

## 当前下一步

P0-5 并发乐观锁 → P0-6 用例 → P1-1 审核历史 → P1-2 删除/回收站 → P1-3 类目属性 → P1-4 图片表 → P1-5 搜索同步 → P1-6 批量上下架。
