# 多多 AI 全能管家 · 排障手册

> 适用范围：`duoduo-ai` 服务的对话、工具、卡片、红点、审计与游客配额。
> 原则：**先看指标定位环节，再看日志定位代码**。不要一上来就读模型回复——它只是结果。

## 1. 四个关键指标

指标通过 Actuator 暴露：`GET /actuator/metrics/{name}`（网关内网可达）。

| 指标 | 类型 | 含义 | 正常 | 需要关注 |
| --- | --- | --- | --- | --- |
| `ai_tool_failure_rate` | Gauge（0~1） | 工具失败率 = 失败/总调用 | < 0.05 | 持续 > 0.30 说明下游或参数校验出问题 |
| `ai_llm_timeout_count` | 累计计数 | 模型调用超时次数 | 偶发 | 持续增长：模型网关变慢/不可用 |
| `ai_guest_quota_exceeded_count` | 累计计数 | 游客 3 轮用尽被拦次数 | 有波动 | 陡增：被脚本刷、或 Cookie 未透传导致误判 |
| `ai_empty_card_count` | 累计计数 | 工具成功但卡片为空 | 偶发 | 持续增长：卡片装配解析失败或引用缺失 |

辅助指标：

| 指标 | 用途 |
| --- | --- |
| `ai_tool_calls_total` / `ai_tool_failures_total` | 失败率的分母/分子，按时间窗口算真实速率 |
| `ai_llm_fallback_count` | 主模型失败后切换到备选模型的次数；持续增长说明主模型在带病工作 |
| `ai_llm_duration` | 模型耗时（含 p50/p95）；先看它再看超时计数 |

## 2. 四条排障路径

### 2.1 AI 不回复 / 一直转圈

1. `GET /actuator/health` 看服务是否 UP；`GET /api/duoduo/capabilities` 看 `chatAvailable`。
2. 若 `chatAvailable=false`：模型 Key 未配置（`AI_NOT_CONFIGURED`），不是用户网络问题。
3. 日志关键字：`Agent 调度失败`（异常栈）、`AI 服务未配置`、`当前会话正在处理中`（会话锁）。
4. 若日志出现 `status=403 ... AllocationQuota.FreeTierOnly`：模型账号**免费额度耗尽**。
   此时回退无效（同一账号下所有模型共享额度），需充值或关闭「仅免费额度」模式。
5. SSE 场景额外确认：前端请求的是 `/api/duoduo/chat/stream`，且响应未被代理缓冲
   （仓库内 `frontend/nginx.conf` 对该精确路径设置了 `proxy_buffering off`）。

### 2.2 卡片为空

1. 看 `ai_empty_card_count` 是否在涨。
2. 日志关键字：`卡片装配失败 tool=`（Data → 卡片结构不匹配）、`AI 审计写入失败`（旁路，不影响回复）。
3. 审计表确认模型到底调了什么：

```sql
SELECT tool_name, status, latency_ms, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE session_id = '<sessionId>'
ORDER BY created_at DESC
LIMIT 20;
```

4. 若工具 `status=OK` 但卡片为空：检查审计消息里的 `card_refs`（模型可能给了空数组）：

```sql
SELECT role, card_refs, LEFT(content, 200) AS content, created_at
FROM duoduo_ai.t_ai_chat_message
WHERE session_id = '<sessionId>'
ORDER BY created_at DESC
LIMIT 10;
```

### 2.3 AI 看过通知，但角标没消

1. 先确认服务端权威值：`GET /api/socials/notices/unread-summary`（需登录）。
2. 查工具日志里通知工具的状态与返回：

```sql
SELECT status, badge_patch, args_json, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE tool_name = 'get_user_notifications'
ORDER BY created_at DESC
LIMIT 10;
```

3. 三种典型原因：
   - `badge_patch` 为 null：本次没有标记已读（模型传了 `markRead=false`，或没有未读）；
   - 前端没收到 `cards` 事件：检查 SSE 事件顺序 `meta → tool* → recommendations → token* → cards → done`；
   - 前端 store 未应用补丁：确认 `applyBadgePatch` 被调用（浏览器 Network/Console 无报错）。
4. 对照已读集合：

```sql
SELECT COUNT(*) FROM duoduo_social.t_social_notice_read WHERE user_id = <userId>;
```

### 2.4 审计没有落库

1. 启动日志应出现 `AI 库表自检通过`；若启动失败并提示 `缺列`，先执行：

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\apply-73-ai-butler-ddl.ps1
```

2. 运行期写失败会在日志打印 `AI 审计写入失败 kind=turn|tool:xxx session=...`：
   - `Data too long`：检查消息长度（MEDIUMTEXT 足够，通常是 JSON 字段超限）；
   - `Unknown column`：DDL 未执行（回到第 1 步）；
   - 队列满：`AI 审计队列已满（容量 2000）`，说明数据库写入变慢，需查 MySQL 慢查询。
3. 确认是否有数据：

```sql
SELECT COUNT(*) FROM duoduo_ai.t_ai_chat_message WHERE created_at >= CURDATE();
SELECT COUNT(*) FROM duoduo_ai.t_ai_tool_call_log WHERE created_at >= CURDATE();
```

## 3. 参数与开关

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `duoduo.ai.schema-guard.enabled` | `true` | 启动时校验审计/记忆表结构；**生产不要关闭** |
| `duoduo.ai.guest.cookie-secure` | `false` | 站点上 HTTPS 后置 `true` |
| `duoduo.ai.recall.read-timeout-ms` | `1500` | 下游只读接口超时；调大前先确认下游已恢复 |

## 4. 快速判定表

| 现象 | 最可能的环节 | 第一眼看什么 |
| --- | --- | --- |
| 回复正常、卡片没有 | 装配 / card_refs | `ai_empty_card_count`、`card_refs` 审计 |
| 回复正常、角标不动 | 补丁 / 前端 store | `badge_patch` 工具日志、SSE 事件顺序 |
| 回复变慢、偶发 503 | 模型 | `ai_llm_duration`、`ai_llm_timeout_count` |
| 回复正常但主模型已挂 | 模型回退 | `ai_llm_fallback_count` 持续增长 → 查主模型配额/下线公告 |
| 工具全失败 | 下游服务 | `ai_tool_failure_rate`、Feign 日志 |
| 游客很快被拦 | 配额 / Cookie | `ai_guest_quota_exceeded_count`、请求头 Cookie |
