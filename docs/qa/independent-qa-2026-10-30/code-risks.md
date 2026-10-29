# 代码风险清单（未复现，不得当作已确认缺陷）

> 这些是代码审查/静态观察发现的风险；除 CR-01 的“慢”有实测支撑外，其余未构造出稳定复现，故不作为缺陷计数。
> 文件路径均相对 `duoduo-pet-mall-java/`。

## CR-01 · AI 单轮没有全局截止时间，最坏可超过网关/nginx 超时

- **级别**：P1（潜在；未复现最坏路径）
- **位置**：`duoduo-ai/src/main/java/com/duoduo/ai/agent/AgentChatService.java:221-225`；`duoduo-ai/src/main/java/com/duoduo/ai/llm/DashScopeToolClient.java:82-85`；`frontend/nginx.conf:80`、`:55`
- **现象**：Agent 最多 4 步，每步 `chatWithTools` 各自有 55s 总预算；整轮没有 deadline。理论上限 4×55s=220s。实测推荐场景已 97s；非流式 `/chat` 经 nginx 60s 会 504，SSE 180s 也可能被突破。
- **为什么危险**：用户看到长时间无响应/被网关切断；重试会叠加模型成本；游客配额只在成功后才扣，失败重试可能放大调用量。
- **复现思路**：构造一个需要 3~4 步工具调用的问题（先搜索、再详情、再库存、再推荐），在 nginx 8088 上走非流式 `/api/duoduo/chat`，观察 60s 切断；或在 SSE 上观察超过 180s。
- **回归**：整轮 deadline 必须 < nginx 最短超时；超时返回明确降级文案；记录 p95。

## CR-02 · AI 审计线程池满时静默丢审计

- **级别**：P2
- **位置**：`duoduo-ai/src/main/java/com/duoduo/ai/audit/AiAuditService.java:163-166`
- **现象**：审计任务被拒绝时只 `log.warn`，不重试、不落盘、不计数；队列容量 2000。
- **为什么危险**：审计是“谁在什么页面调了什么工具、改了什么”的唯一证据；高峰期丢审计会让事后排障/合规检查出现空洞。
- **复现思路**：压测/人为让 DB 写入变慢，填满队列后触发拒绝；观察 `t_ai_tool_call_log` 与实际工具调用数不一致。
- **回归**：审计拒绝次数指标 + 告警；关键资金/写操作审计改为同步或本地落盘补偿。

## CR-03 · `/api/duoduo/confirm` 用 HTTP 200 + 内层失败表达拒绝

- **级别**：P3
- **位置**：`duoduo-ai/src/main/java/com/duoduo/ai/controller/AiController.java:147-149`
- **现象**：伪造/过期确认令牌返回 `HTTP 200`，外层 `code=SUCCESS`，内层 `data.success=false, code=CONFIRM_INVALID`。
- **为什么危险**：只检查 HTTP 状态的客户端/第三方集成会把“拒绝”当成功；前端当前有检查内层字段，但契约不够显式。
- **复现思路**：已复现（T14），见 `evidence/api-followup.md`；风险在于调用方而非服务端。
- **回归**：要么返回 4xx，要么在 OpenAPI 明确“必须检查 data.success”。

## CR-04 · 通知 miniPath 直接来自 jump_page，未做小程序路径白名单

- **级别**：P3
- **位置**：`duoduo-ai/src/main/java/com/duoduo/ai/butler/service/NotificationQueryService.java:136-142`
- **现象**：`miniPath()` 只去掉前导 `/`，未校验是否 `pages/...`；若通知 `jump_page` 被写成 `http://...`，会原样进入卡片 `miniPath`。
- **为什么危险**：小程序 `wx.navigateTo` 打不开外链，用户点击无反应；Web 侧 `pageUrl` 走 `webPath` 白名单，暂未发现开放重定向。
- **复现思路**：本地 SQL 造一条 `jump_page='https://evil.example'` 的通知，登录买家问“我有什么通知”，检查 AI 卡片的 `miniPath`。
- **回归**：miniPath 只允许 `pages/` 前缀；非法值置 null 并记录。

## CR-05 · 订单分页第 2 页内容与“最早订单”不一致（未复现）

- **级别**：P2（潜在）
- **位置**：`duoduo-trade/src/main/java/com/duoduo/trade/service/OrderService.java:1086`（`created_at DESC, id DESC`）；前端订单列表分页组件
- **现象**：`pagination-search-ui-check` 48 PASS / 3 FAIL，其中“第 2 页里能看到最早那笔订单”失败；同一页面“第 2 页只有 1 张卡片”“页码 2/2”通过。后端排序本身有 id 兜底，未发现明显 SQL 问题。
- **为什么危险**：分页内容错位会让用户找不到订单；如果只是测试时序，则属于假红。
- **复现思路**：用固定买家连续创建 11 笔订单（记录创建顺序），直接调 `/api/orders?page=1/2&pageSize=10` 与 UI 对比；避免并发/同秒时间戳干扰。
- **回归**：API 层断言第 2 页 = 最早 1 笔；UI 层断言同一订单号；连续跑 3 次稳定。

## CR-06 · SSE 客户端断开后模型调用不会取消

- **级别**：P2
- **位置**：`duoduo-ai/src/main/java/com/duoduo/ai/controller/AiController.java:114-126`
- **现象**：`streamExecutor.execute` 里直接跑 `agentChatService.chat`，只依赖 `emitter.complete()`；没有注册 `onCompletion/onTimeout/onError` 去中断 Agent，也没有检查客户端是否已断开。
- **为什么危险**：用户关页/断网后，服务端仍可能跑完 4 步模型调用，浪费额度与线程；高并发下放大排队。
- **复现思路**：发起 SSE 后 1 秒断开连接，观察 AI 日志/模型调用是否继续到 `done`，以及线程池占用。
- **回归**：断开即取消（至少在下一次工具调用前检查取消标记）；统计“客户端断开仍完成”的次数。

## CR-07 · 秒杀 Redis 预扣成功但 DB 提交失败会少卖

- **级别**：P3（设计内边界，已在代码注释记录）
- **位置**：`duoduo-trade/src/main/java/com/duoduo/trade/service/SeckillService.java:51-78`
- **现象**：Redis Lua 预扣成功、DB 事务失败时，Redis 计数偏低、DB 配额未扣 → 少卖不超卖。
- **为什么危险**：活动余量展示可能偏少；不会超卖，属于可接受取舍，但运营会看到“还剩但抢不到”。
- **复现思路**：在 DB 扣减处注入异常/锁超时，观察 Redis 余量与 DB 余量差异。
- **回归**：失败时回补 Redis 计数或记录补偿任务；监控余量偏差。

## CR-08 · 导出文件路径来自数据库，下载时直接 `Path.of`

- **级别**：P3
- **位置**：`duoduo-trade/src/main/java/com/duoduo/trade/service/ExportJobService.java:193-201`
- **现象**：`job.getFilePath()` 直接 `Path.of` 并 `Files.isReadable`，没有校验是否在导出目录内。
- **为什么危险**：只有运营能创建任务、路径由服务端写入，正常不可控；但若 DB 被篡改/迁移脏数据，可能读取任意可读文件。
- **复现思路**：本地把某任务 `file_path` 改成 `/etc/passwd` 后下载（需要 DB 权限，不对外部开放）。
- **回归**：`normalize` 后必须以 `exportDir` 开头，否则 404。

## 未发现明显问题的链路（审查结论）

- 支付回调：验签后做幂等、金额核对（`payload.amount() != payment.amount()` 拒绝）、重复收款单独处理（`OrderService.java:1540-1608`）。
- 优惠券：领券唯一索引 + 状态 CAS + 幂等快路径（`CouponService.java:108-131`）。
- 多多币任务：事件唯一键、进度条件自增、发奖 `rewarded 0→1`、流水唯一键四道闸（`CoinTaskService.java:37-48`）。
- 导出任务归属：`Objects.equals(createdBy,userId)` + 跨人 404，实测通过（`ExportJobService.java:324-330`）。
- 库存：冻结/释放/扣减原子条件更新（`StockService.java:24-52`）。
