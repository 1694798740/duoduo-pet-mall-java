# 独立 QA 执行报告（2026-10-30）

> 环境：本机 Docker，18 容器 healthy；商城 `http://localhost:8088`，网关 `http://localhost:8080`。
> 原则：只记录实际执行过的结果；未能执行的一律 BLOCKED/NOT TESTED。
> 原始日志：`evidence/`；测试计划与矩阵：`test-plan.md`。

## 1. 基线检查

| 项 | 结果 |
| --- | --- |
| 容器 | 18/18 healthy（mysql/redis/nacos/es/mq + gateway + 8 业务 + 3 前端 + 旧 Node 原型） |
| 商城首页 | 200 |
| 网关 `/api/duoduo/capabilities` | 200，`mode=llm` |
| 商家端 `:8091` / 后台 `admin.localhost:8090` | 302（到登录页，符合预期） |
| 数据库 | user 4633 / order 2171 / spu 80 |
| Git | `87da3e1`，工作区干净（本轮只新增 `docs/qa/independent-qa-2026-10-30/`） |
| 需求基线 | README/PLAN/ARCHITECTURE/manuals/OpenAPI/DDL/scripts 已读；**PRD 原文件不在仓库**（G1） |

## 2. 自动化执行

### 2.1 Maven 全量单元测试

命令（PowerShell，显式 JDK21 + Maven classworlds）：

```
java -classpath "D:/devsoft/apache-maven-3.9.4/boot/plexus-classworlds-2.7.0.jar" \
  "-Dclassworlds.conf=.../bin/m2.conf" "-Dmaven.home=..." \
  "-Dmaven.multiModuleProjectDirectory=<repo>" \
  org.codehaus.plexus.classworlds.launcher.Launcher -ntp test
```

结果：**1158 tests，0 failures，0 errors，5 skipped，BUILD SUCCESS，总耗时 1:04 min**。
日志：`evidence/maven-test.log`；surefire XML 汇总：120 个测试类。

### 2.2 前端 TypeScript 与构建

命令：`npm run typecheck && npm run build`（frontend monorepo，mall/admin/merchant 三端）。

结果：`tsc --noEmit` 三端 0 错误；`vite build` 三端成功（退出码 0）。
日志：`evidence/frontend-typecheck.log`、`evidence/frontend-build.log`。

### 2.3 核心冒烟（两批 34 套）

| 脚本 | 断言 | 结果 | 耗时 |
| --- | --- | --- | --- |
| smoke.ps1 | 62 | PASS 62 / FAIL 0 | 13.9s |
| order-smoke.ps1 | 79 | PASS 79 / FAIL 0 | 21.8s |
| payment-smoke.ps1 | 72 | PASS 72 / FAIL 0 | 26.6s |
| coupon-smoke.ps1 | 145 | PASS 145 / FAIL 0 | 40.8s |
| coins-smoke.ps1 | 65 | PASS 65 / FAIL 0 | 17.7s |
| privilege-matrix-smoke.ps1 | 116 | PASS 116 / FAIL 0 | 43.0s |
| tenant-isolation-smoke.ps1 | 37 | PASS 37 / FAIL 0 | 21.1s |
| import-export-batch-smoke.ps1 | 183 | PASS 183 / FAIL 0 | 7.2s |
| ai-smoke.ps1 | 44 | **PASS 37 / FAIL 7** | 162.1s |
| aftersale-smoke.ps1 | 101 | PASS 101 / FAIL 0 | 17.4s |
| settlement-smoke.ps1 | 149 | PASS 149 / FAIL 0 | 33.9s |
| **第一批合计** | **1053** | **1046 PASS / 7 FAIL** | — |

第二批（23 套，覆盖地址/注销/2FA/折扣券/秒杀/物流/信用分/IP 白名单/评价/自动退款/搜索/商家/对账/审计/后台/交易/权限版本/租户健康/ID 精度/MQ/代运营/回填/导出 P3）：

| 脚本 | 断言 | 结果 |
| --- | --- | --- |
| address-smoke | 82 | 82/0 |
| account-deletion-smoke | 12 | 12/0 |
| admin-2fa-smoke | 56 | 56/0 |
| discount-coupon-smoke | 64 | 64/0 |
| seckill-smoke | 89 | 89/0 |
| logistics-smoke | 79 | 79/0 |
| credit-smoke | 82 | 82/0 |
| merchant-ip-whitelist-smoke | 40 | 40/0 |
| review-smoke | 62 | 62/0 |
| payment-auto-refund-smoke | 40 | 40/0 |
| export-p3-smoke（复跑） | 8 | **8/0**（首次 4/4 因本地旧容器 404，重建后通过） |
| search-smoke | 40 | 40/0 |
| seller-smoke | 107 | 107/0 |
| recon-smoke | 53 | 53/0 |
| audit-log-smoke | 58 | **57/1**（DEF-011 真实审计缺口 + DEF-012 dry-run 假红） |
| admin-smoke | 75 | 75/0 |
| trade-smoke | 87 | 87/0 |
| perm-version-smoke | 34 | 34/0 |
| tenant-health-smoke | 90 | 90/0 |
| id-precision-smoke | 56 | 56/0 |
| mq-smoke | 48 | 48/0 |
| proxy-ops-smoke | 128 | 128/0 |
| backfill-smoke | 92 | 92/0 |
| **第二批合计** | **1482** | **1481 PASS / 1 FAIL** |

补跑 `pay-channel-smoke`：**62/0**（支付渠道、回调、库存复原全部通过）。
**冒烟合计**：**35 套 / 2597 断言 / 2589 PASS / 8 FAIL**。
本地 `docker-compose` 的 gateway/trade/fulfilment/social 容器比 jar 旧 35 小时；首次 `export-p3-smoke` 的 4 个 404 是**本地旧容器**导致，重建这 4 个容器后复跑 8/0，**不是产品缺陷**。其余套件在重建前已通过，重建后未改变结论。

AI 冒烟 7 个失败的定性（详见 `evidence/smoke-ai-smoke.ps1.log` 与 `evidence/ai-latency.txt`）：

- 6 个是**同一根因的测试设计问题**：脚本从第 2 节到第 6 节共用同一个游客 WebSession，累计超过 3 轮后，后续问题全部收到“游客体验最多 3 轮”，于是推荐/追问/预算/澄清断言失败。
- 1 个是**夹具未被检索到**：脚本现场用 SQL 建“犬粮”SPU，但未触发 ES/检索索引同步，AI 的 `search_products` 返回空，导致“幼犬狗粮推荐非空”失败。
- 独立复测（新游客 Cookie）：游客查订单正确返回 `loginUrl`，未编造订单；3 轮配额第 4 轮正确拦截；猫粮推荐能返回 3 条真实商品。**不能把这 7 个失败算作产品缺陷，但说明“旧冒烟全绿”不可信**（测试资产缺陷 D-03）。

### 2.4 真实浏览器 UI 检查（9 套有效）

| 脚本 | 断言 | 结果 | 结论 |
| --- | --- | --- | --- |
| home-ui-check | 9 | 8/1 | 首页裂图 1 项；独立取证首页 `homeBroken=[]`，**未复现**，按测试假红 |
| product-detail-ui-check | 19 | 18/1 | **商品详情主图裂图（已复现，D-01）** |
| pagination-search-ui-check | 51 | 48/3 | 1 项第 2 页内容不匹配（**未复现/待定**）；2 项是临时运营访问商家接口 403（测试前置问题） |
| checkout-payment-j03-ui-check | 12 | 12/0 | 真实浏览器下单→沙箱支付→回库状态全部一致 |
| coins-ui-check | 16 | 16/0 | 多多币页面/流水正常 |
| seller-ui-check | 40 | 40/0 | 商家工作台正常 |
| aftersale-ui-check | 44 | 43/1 | 1 项“订单列表能看到订单”选择器失败；紧接着“申请售后入口”PASS，属测试假红 |
| review-ui-check | 24 | 24/0 | 评价链路正常 |
| admin-entry-ui-check | 74 | 71/3 | 3 项集中在头像下拉 helper；人工浏览器核对运营下拉入口**存在且可跳转**，属测试假红 |
| **合计（有效 9 套）** | **289** | **280 PASS / 9 FAIL** | 仅 D-01 为已复现产品缺陷 |

> 误执行了一次不存在的 `merchant-ui-check.mjs`（MODULE_NOT_FOUND），不计入结果，已改用 `seller-ui-check.mjs`。

## 3. 独立 API 测试

脚本：`evidence/api-tests.py`（账号由环境变量注入，不落盘密码）。
原始结果：17 用例，14 PASS / 3 FAIL；复核后 3 个 FAIL 均非产品缺陷，详见 `evidence/api-followup.md`。

| 编号 | 用例 | 实际结果 | 结论 |
| --- | --- | --- | --- |
| T01 | 未登录访问订单 | 401 | PASS |
| T02 | 买家访问运营订单 | 403 | PASS |
| T03 | 买家访问商家订单 | 403 | PASS |
| T04 | 第三方店主读自己商品 | 200 | PASS |
| T05 | 第三方店主读自营商品 | 404 | PASS |
| T06 | 买家 A 读买家 B 订单 | 404 | PASS |
| T07 | 买家 A 读自己订单 | 200 | PASS |
| T08/T09 | 加购 0 / 201 | 400 / 400 | PASS |
| T10 | 搜索 SQL 注入 | 200，total=0 | PASS |
| T11 | pageSize=100000 | 200，返回 60 | PASS |
| T12 | 重复领券 | 两次 200，同一张券（幂等） | PASS（测试假设错误） |
| T13 | 重复签到 | 第一次 200，第二次 422 | PASS |
| T14 | 伪造 AI 确认令牌 | HTTP 200，内层 `success=false/CONFIRM_INVALID` | PASS（契约风险 CR-03） |
| T15 | 异步导出跨人下载 | 创建人 200；临时提权运营访问 404 | PASS（补实验） |
| T16 | 记忆接口未登录/登录 | 401 / 200 | PASS |
| T17 | 买家订单导出 | 200 text/csv，脱敏 | PASS |

补充实测：已关闭订单取消 → 422；第三方店主为平台订单发货 → 403；买家导出 CSV 手机号 `136****1111`、地址 `北京市海淀区****`。

## 4. 真人浏览器场景

| 场景 | 执行方式 | 结果 |
| --- | --- | --- |
| 买家登录 → 商品详情 → 购物车 → 结算 → 沙箱支付 → 订单详情 | J-03 UI 检查（真实 Chromium） | PASS：订单 PAID、支付单 SUCCESS、库存 DEDUCTED 回库一致 |
| AI 导购对话 + 通知卡片 | 浏览器代理（买家小A） | PASS：回复出现，通知卡 5 条，9 条未读 |
| AI 会话跨页面保持 | 浏览器代理：/ai → /notifications → 返回 /ai → 追问暗号 | PASS：气泡保留，AI 回忆“蓝鲸” |
| AI 记忆库页 | 浏览器代理 + 本目录截图 | PASS：称呼/物种/预算/过敏原/健康备注可见，无 console error、无破图 |
| 运营后台入口 | 浏览器代理 + 人工核对 | PASS：顶栏“管理后台”和头像下拉“进入管理后台”均存在并可跳转 |
| 商家工作台/评价/售后 | seller/review/aftersale UI 检查 | 商家 40/0、评价 24/0；售后 43/1（1 项测试假红） |

## 5. 未执行 / BLOCKED

| 范围 | 原因 |
| --- | --- |
| 微信登录 / 微信支付 / 订阅消息 | 未配置真实商户，项目已拍板不做微信支付 |
| 支付宝真实资金 | 只用沙箱；不产生真实扣款 |
| COS 真实写入 | 本地 `STORAGE_TYPE=cos`，写入会污染生产桶，主动不执行 |
| ES/Redis/MQ 故障注入 | 会打断同一环境其它测试，未做 |
| 并发压力（秒杀/发币/登录） | 未做压测；仅代码审查 + 现有冒烟 |
| 剩余约 6 套冒烟 / 约 50 套 UI | 已跑 35 套冒烟 + 9 套 UI；`upload`（COS 写入）主动不做，`wechat-auth/bind` 外部依赖，`smoke-linux` 等未跑 |
| 运营后台完整业务页（审核/仲裁/商家管理/导出任务页） | 仅入口与权限、售后仲裁、后台导出接口覆盖；页面级 NOT TESTED |
| 商家入驻 / 店员权限 / 2FA 完整 UI | 后端 2FA/权限冒烟已跑；入驻与店员 UI 未跑 |
| 客服会话/私信/未读全链路 | 只有通知与 AI 卡片覆盖；客服认领/转交/未读未跑 |
| 小程序真机 / 移动端适配 | 只做了 Web 桌面视口截图；小屏/真机 BLOCKED |
| HTTPS / Cookie Secure / ICP | 本地 HTTP，未验证 HTTPS 行为 |

## 6. 测试数据与清理

- 新增测试券：已删除，模板 `claimed_count` 由 98 还原为 97。
- 异步导出任务：任务行与 `/data/exports/*.csv` 已删除。
- 临时提权买家 B：已还原 `role=USER`。
- 签到产生的币/流水：保留（强行回滚会破坏“余额=流水”一致性），标注为本机夹具数据。
- 浏览器测试使用测试账号；未写生产或公网试用环境。
