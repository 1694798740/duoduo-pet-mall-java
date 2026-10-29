# 上线后 100 轮灰度观察清单

> 适用：AI 管家上线后第一波真实流量（前 100 轮对话）。
> 原则：**前 20 轮人工逐条看，20~100 轮看指标 + 抽样**。
> 任何一条「中止条件」触发，先降级 AI 入口，再定位，不边查边放量。

---

## 1. 值守安排（发布单必须填）

| 项 | 填写 |
| --- | --- |
| 灰度开始时间 | ____ |
| 发布负责人 | ____ |
| 值守人（首 2 小时） | ____ |
| 回滚执行人 | ____ |
| 用户反馈收集渠道 | ____（客服群 / 工单） |
| 回滚方式 | 重新部署上一版 `duoduo-ai` 镜像（无功能开关，需在发布单写明） |

---

## 2. 观察节奏

| 阶段 | 范围 | 动作 |
| --- | --- | --- |
| T+0 ~ T+30min | 前 20 轮 | 每轮人工看回复、卡片、红点、跳转；记录异常到第 6 节表格 |
| T+30min ~ T+2h | 20~100 轮 | 每 10 轮抽样 1 轮人工复核；其余看指标 |
| T+2h | 100 轮结束 | 汇总指标与问题，决定「继续放量 / 维持灰度 / 回滚」 |

---

## 3. 必盯指标（每 10 分钟读一次）

Actuator：`GET http://<ai-host>:8086/actuator/metrics/{name}`

| 指标 | 正常 | 关注阈值 | 触发后的第一动作 |
| --- | --- | --- | --- |
| `ai_tool_failure_rate` | < 0.05 | > 0.30 持续 5 分钟 | 查哪个工具失败（审计表），再看对应下游服务 |
| `ai_llm_timeout_count` | 偶发 | 每 10 分钟新增 > 3 | 查模型网关状态；看 `ai_llm_fallback_count` 是否同步上涨 |
| `ai_llm_fallback_count` | 0 或偶发 | 持续增长 | 主模型在带病工作：查配额/下线公告，决定是否临时调整模型顺序 |
| `ai_guest_quota_exceeded_count` | 有波动 | 短时陡增 | 可能被脚本刷，或小程序 Cookie 未透传导致误判 |
| `ai_empty_card_count` | 0 | 任意增长 | 立即查「工具成功但卡片空」的会话（SQL 见第 5 节） |
| `ai_llm_duration` p95 | < 15s | > 25s | 先看模型耗时，再看是否触发了回退 |

补充：`GET /actuator/health` 必须始终 UP；`/api/duoduo/capabilities` 必须 `chatAvailable=true`。

---

## 4. 日志抓取建议

```powershell
# 实时跟 AI 服务日志（只看关键行）
docker logs -f duoduo-app-ai 2>&1 | Select-String "模型|LLM 调用失败|卡片装配失败|AI 审计写入失败|AI 方案生成失败|DEAD_LETTER"

# 发布后 2 小时内汇总异常
docker logs duoduo-app-ai --since 2h 2>&1 |
  Select-String "ERROR|WARN" |
  Select-String "LLM|卡片|审计|工具|DEGRADED"
```

关键日志与含义：

| 日志片段 | 含义 | 处置 |
| --- | --- | --- |
| `模型 xxx tools 调用失败，尝试下一个` | 主模型失败已回退 | 结合 fallback 指标判断是否持续 |
| `流式中断且已输出部分内容，不再回退` | 用户可能看到半截回复 | 抽查该会话是否补发/重试 |
| `AI 审计写入失败` | 审计落库失败（不影响回复） | 查 MySQL 慢查询/表结构 |
| `卡片装配失败 tool=` | Data → 卡片结构不匹配 | 取该会话工具结果复现 |
| `AI 方案生成失败` | 模型输出不符合信封格式 | 看是否模型漂移，必要时调 Prompt |
| `AI 审计队列已满` | 审计写入被打满 | 查 MySQL 写入能力 |
| `status=403 ... AllocationQuota.FreeTierOnly` | 模型账号免费额度耗尽 | **立即停止放量**：回退无效（同账号共享额度），充值或关闭「仅免费额度」模式后再继续 |

---

## 5. 每 10 轮一次的 SQL 抽查

```sql
-- ① 最近 10 轮的工具失败与耗时
SELECT tool_name, status, latency_ms, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE created_at >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE)
ORDER BY created_at DESC LIMIT 30;

-- ② 红点是否与服务端权威值一致（挑最近有 markRead 的会话）
SELECT session_id, tool_name, badge_patch, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE tool_name = 'get_user_notifications' AND created_at >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE)
ORDER BY created_at DESC LIMIT 10;

-- ③ 审计完整性：消息与会话是否都有
SELECT COUNT(*) AS messages FROM duoduo_ai.t_ai_chat_message
WHERE created_at >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE);
SELECT COUNT(*) AS sessions FROM duoduo_ai.t_ai_chat_session
WHERE last_active_at >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE);

-- ④ 记忆写入是否只走确认流程
SELECT memory_key, COUNT(*) FROM duoduo_ai.t_ai_memory_event
WHERE created_at >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE)
GROUP BY memory_key;
```

---

## 6. 抽样复核记录表（每 10 轮填一行）

| 轮次 | 用户问题 | 是否调工具 | 回复是否符合事实 | 卡片是否正确 | 红点是否一致 | 跳转是否可达 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| #10 | | | | | | | |
| #20 | | | | | | | |
| #30 | | | | | | | |
| #40 | | | | | | | |
| #50 | | | | | | | |
| #60 | | | | | | | |
| #70 | | | | | | | |
| #80 | | | | | | | |
| #90 | | | | | | | |
| #100 | | | | | | | |

复核口径：
- 「符合事实」：金额/订单号/通知条数与页面或 DB 一致，没有编造；
- 「卡片正确」：类型、条数、金额与工具结果一致，没有空壳卡；
- 「红点一致」：AI 说消了几条，角标就减几条；
- 「跳转可达」：点卡片能到目标页且带参生效。

---

## 7. 中止与回滚条件（任一满足立即执行）

| # | 条件 | 动作 |
| --- | --- | --- |
| 1 | AI 页面 5xx 比例 > 1%（连续 5 分钟） | 摘掉 AI 入口 / 回滚上一版 |
| 2 | `ai_tool_failure_rate` > 0.5 持续 10 分钟 | 回滚或关闭深度工具（仅保留商品问答） |
| 3 | 出现跨用户数据泄露（订单/通知/记忆） | **立即回滚**，按安全事件流程上报 |
| 4 | 出现可点击外链或越权跳转 | 立即回滚，保留现场（会话 ID + 审计记录） |
| 5 | 审计连续 10 分钟无新数据 | 停止放量，先修审计写入 |
| 6 | 用户投诉「AI 编造订单/物流」≥ 2 例 | 暂停深度工具，排查工具结果与 Prompt |
| 7 | 出现 `403 AllocationQuota.FreeTierOnly` | 立即停止放量；充值/关闭仅免费额度后再恢复 |

---

## 8. 100 轮后的结论模板

```markdown
## 灰度结论（前 100 轮）

- 时间窗口：____ ~ ____
- 实际轮次：____（游客 __ / 登录 __）
- 指标：tool_failure=__%  llm_timeout=__  fallback=__  empty_card=__  quota_blocked=__
- 人工复核：__ 轮，问题 __ 个（Critical __ / High __ / Medium __）
- 典型问题：
  1. ____（会话 ID：____）
  2. ____
- 结论：继续放量 / 维持灰度 / 回滚
- 后续动作与负责人：____
```
