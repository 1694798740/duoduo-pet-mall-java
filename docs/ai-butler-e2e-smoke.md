# 多多 AI 全能管家 · 端到端冒烟手册

> 目标：用一套可复现的步骤，验证「游客 3 轮 → 登录 → 记忆 → 卡片 → 跳转 → 高风险确认 → 落库」整条链路。
> 原则：**每一步都有预期结果与核对 SQL**。看不到证据的步骤，不算通过。

---

## 0. 前置检查

| 项 | 命令 / 位置 | 通过标准 |
| --- | --- | --- |
| 容器基线 | `docker compose ps` | 10 个容器 healthy（含 mysql / redis / ai） |
| 后端编译 | `mvn test -pl duoduo-ai -am` | BUILD SUCCESS，100 条测试全绿 |
| 前端构建 | `npm run build:mall` | `tsc --noEmit` + `vite build` 通过 |
| 增量 DDL | `scripts\apply-73-ai-butler-ddl.ps1` | 8/8 PASS，两遍指纹一致 |
| 模型 Key | `.env` 的 `DASHSCOPE_API_KEY` | `/api/duoduo/capabilities` 返回 `chatAvailable=true` |
| Redis | `docker exec duoduo-redis redis-cli ping` | `PONG`（高风险确认依赖它，Redis 挂了确认会失败） |

AI 服务启动日志里应能看到：

```
AI 库表自检通过：审计与记忆表结构齐全
```

看不到这行 → 回到 DDL 步骤；表缺列时服务会**拒绝启动**。

---

## 1. 容器重建

### 1.1 只重建 AI 应用（保留数据，日常迭代用）

```powershell
docker compose build duoduo-ai-app
docker compose up -d duoduo-ai-app
docker compose logs -f duoduo-ai-app | Select-String "AI 库表自检通过"
```

### 1.2 彻底重建 MySQL / Redis（清空全部业务数据）

> ⚠️ 会删除所有库数据。仅用于「从零复现」或数据已被污染的场景。

```powershell
docker compose stop duoduo-mysql duoduo-redis
docker compose rm -f duoduo-mysql duoduo-redis

# 确认卷名（默认是「目录名_卷名」）
docker volume ls | Select-String "mysql-data|redis-data"
docker volume rm duoduo-pet-mall-java_mysql-data duoduo-pet-mall-java_redis-data

docker compose up -d duoduo-mysql duoduo-redis
docker compose ps          # 等两个服务 healthy

# 容器初始化只跑 docs/sql 下的 00~08；73/74 是增量脚本，必须手动执行
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\apply-73-ai-butler-ddl.ps1
```

### 1.3 注入买家与通知夹具

**注册并登录一个买家**（脚本里的演示密码是 `duoduo12345`）：

```powershell
$gateway = 'http://127.0.0.1:8080'
$phone = '13900000099'
$pwd = 'duoduo12345'

Invoke-RestMethod -Method Post -Uri "$gateway/api/auth/register" `
  -ContentType 'application/json' `
  -Body (@{ phone = $phone; password = $pwd; nickname = 'AI冒烟买家' } | ConvertTo-Json)

$login = Invoke-RestMethod -Method Post -Uri "$gateway/api/auth/login/token" `
  -ContentType 'application/json' `
  -Body (@{ phone = $phone; password = $pwd } | ConvertTo-Json)

$token  = $login.data.accessToken
$userId = $login.data.userId
```

**注入一条「订单已发货」通知**（把 `<userId>` 换成上一步的值）：

```sql
INSERT INTO duoduo_social.t_social_notice
  (id, user_id, category, title, content, biz_type, biz_id, jump_page, created_at)
VALUES
  (900000000000099001, <userId>, 'ORDER', '订单已发货', '你的猫粮已发出', 'ORDER',
   '202610291154301234519',
   '/pages/order-detail/order-detail?orderNo=202610291154301234519',
   UTC_TIMESTAMP(3));
```

`biz_id` 必须是 21 位纯数字（订单号形状），否则网页端落地页会是 `/orders/xxx` 打不开。
订单卡片需要**真实订单**：在商城页面正常下一单（或复用既有下单冒烟脚本），记下订单号。

**清掉该用户的旧记忆**（保证从零开始）：

```sql
DELETE FROM duoduo_ai.t_ai_user_memory WHERE user_id = <userId>;
DELETE FROM duoduo_ai.t_ai_memory_event WHERE user_id = <userId>;
```

---

## 2. 全链路步骤与预期

### 步骤 1 · 游客 3 轮

1. 打开无痕窗口（不登录），进入 `/ai`。
2. 连续发送 4 条任意问题（如「你好」）。
3. 第 4 条预期：回复出现「游客体验最多 3 轮…」并且页面出现「去登录」按钮，**不再调用模型**。

核对：

```sql
-- 游客也会写会话与消息（user_id 为 NULL）
SELECT session_id, user_id, turn_count, status, started_at
FROM duoduo_ai.t_ai_chat_session
ORDER BY started_at DESC LIMIT 5;
```

指标：`GET /actuator/metrics/ai_guest_quota_exceeded_count` 应增加。

### 步骤 2 · 强制登录

1. 点「去登录」，用 `$phone / $pwd` 登录，回到 `/ai`。
2. 预期：登录后不再受 3 轮限制（`userId` 存在时配额分支直接跳过）。

### 步骤 3 · 记忆写入与「记住称呼」

> 说明：称呼**不从对话自动抽取**。模型很容易把「我家狗叫小七」记成「用户叫小七」，
> 名字只允许用户在记忆页显式保存 —— 这是隐私边界，不是功能缺失。

1. 个人中心 →「AI 记忆库」→ `/ai/memory`。
2. 填「希望怎么称呼你 = 小明」、物种 = 猫、预算 100~300、偏好类目「主粮」，点「保存偏好」。
3. 预期：提示「偏好已保存」，刷新后回显一致。
4. 回 `/ai` 问「我叫什么？」→ 预期回答「小明」。

核对：

```sql
SELECT user_id, preferred_species, budget_min_cents, budget_max_cents,
       preferred_categories, memory_json, updated_at
FROM duoduo_ai.t_ai_user_memory
WHERE user_id = <userId>;

-- 期望 memory_json 形如：{"nickname":"小明","allergens":[],"healthNotes":null}
```

### 步骤 4 · 通知卡片与红点

1. 记录当前未读：`GET /api/socials/notices/unread-summary`（带 `Authorization: Bearer $token`）。
2. 在 AI 里问「我有什么未读通知？」
3. 预期：
   - 气泡下方出现 `notification_list` 卡片，含「订单已发货」；
   - 卡片底部有「打开通知中心」按钮；
   - 再查 `unread-summary`，`notice` 比第 1 步**减少了本次展示的条数**。

核对：

```sql
SELECT tool_name, status, latency_ms, badge_patch, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE tool_name = 'get_user_notifications'
ORDER BY created_at DESC LIMIT 3;

SELECT notice_id, user_id, read_at
FROM duoduo_social.t_social_notice_read
WHERE user_id = <userId>
ORDER BY read_at DESC LIMIT 5;
```

### 步骤 5 · 点卡片跳转（带参数路由）

1. 点通知卡上的「打开通知中心」→ 预期打开 `/notifications?category=ORDER`，且分类 chip 已选中「订单」。
2. 点订单卡（有真实订单时）→ 预期打开 `/orders/<21位订单号>`，不是列表页。
3. 未登录时点卡片 → 预期弹登录，`next` 指回目标页（`/login?next=%2Forders`）。

### 步骤 6 · 高风险记忆（必须 Redis 正常）

1. `/ai/memory` 健康信息区填：过敏原「鸡肉、牛肉」、备注「肠胃敏感，换粮需 7 天过渡」。
2. 点「保存健康信息」→ 预期弹出确认弹窗，明确列出「过敏原：鸡肉、牛肉 / 健康备注：…」。
3. 点「确认保存」→ 预期提示「高风险记忆已确认保存」。
4. 负向验证：把浏览器里的确认令牌改成随机值再提交 → 预期 422/400「确认已过期或内容已变更」。

核对：

```sql
SELECT memory_json FROM duoduo_ai.t_ai_user_memory WHERE user_id = <userId>;
-- 期望：{"nickname":"小明","allergens":["鸡肉","牛肉"],"healthNotes":"肠胃敏感，换粮需 7 天过渡"}

SELECT memory_type, memory_key, old_value, new_value, source, actor, created_at
FROM duoduo_ai.t_ai_memory_event
WHERE user_id = <userId>
ORDER BY created_at DESC LIMIT 10;
-- 期望：nickname / allergens+healthNotes 的变更流水各至少一条
```

### 步骤 7 · 记忆在对话中生效

1. 回 `/ai` 问「我家猫肠胃敏感，推荐一款猫粮」。
2. 预期：回复提到「肠胃敏感 / 换粮过渡」，并且**不推荐含鸡肉的选项**（如工具候选里有）。
3. 预期：回复不会声称「诊断」，仍带「不能替代兽医」的边界提醒。

说明：过敏原与健康备注现在会进入 `SlotState`，由 `PromptBuilder` 以 `"""` 定界注入；
称呼同样注入为「用户希望被称呼为」。

### 步骤 8 · 清除记忆

1. `/ai/memory` 点「清除所有记忆」→ 二次确认后执行。
2. 预期：表单清空；再问「我叫什么」→ AI 不再知道称呼。

核对：

```sql
SELECT COUNT(*) FROM duoduo_ai.t_ai_user_memory WHERE user_id = <userId>;  -- 期望 0
```

---

## 3. 审计与观察（整轮结束后）

```sql
-- 会话权威行：每轮都会 upsert
SELECT session_id, user_id, turn_count, summary, status, started_at, last_active_at
FROM duoduo_ai.t_ai_chat_session
WHERE user_id = <userId>
ORDER BY last_active_at DESC;

-- 消息与卡片引用
SELECT role, LEFT(content, 120) AS content, card_refs, page_url, created_at
FROM duoduo_ai.t_ai_chat_message
WHERE session_id = '<上一步的 session_id>'
ORDER BY created_at;

-- 工具调用全量
SELECT tool_name, status, latency_ms, LEFT(args_json, 80) AS args, created_at
FROM duoduo_ai.t_ai_tool_call_log
WHERE session_id = '<上一步的 session_id>'
ORDER BY created_at;
```

指标看板（`/actuator/metrics/{name}`）：

| 指标 | 期望 |
| --- | --- |
| `ai_tool_failure_rate` | 正常对话后接近 0；若明显升高，先查下游 |
| `ai_llm_timeout_count` | 不随对话次数线性增长 |
| `ai_guest_quota_exceeded_count` | 步骤 1 后 +1 |
| `ai_empty_card_count` | 步骤 4 后不应增长（有通知却无卡片就是异常） |

---

## 4. 异常排查

| 现象 | 先查 | 常见原因 |
| --- | --- | --- |
| 记忆页 401 | 请求头 `Authorization` | 未登录 / token 过期 |
| 高风险确认 400/422 | 提示文案 | 超过 5 分钟、内容与申请时不一致、令牌已被消费 |
| 高风险申请失败（Redis 正常） | `docker logs duoduo-app-ai` | 确认服务写入失败（Redis 权限/网络） |
| 高风险申请失败（Redis 挂） | `redis-cli ping` | 设计如此：高风险写入 fail-closed，不降级 |
| 卡片为空 | `ai_empty_card_count`、工具日志 | 模型没调工具 / `card_refs=[]` / 卡片解析失败 |
| 角标不消 | 比较 `unread-summary.notice` 与 `badge_patch.notification` | 未标记已读、前端未应用补丁、SSE 未收到 `cards` |
| AI 不记得称呼/过敏原 | `memory_json` 内容 | 字段名必须是 `nickname` / `allergens` / `healthNotes` |
| 通知卡点不开 | `jump_page` | `biz_id` 不是 21 位订单号，网页落地页拼不出 |
| 对话 503 | `docker logs duoduo-app-ai` | 模型 Key 未配置 / 模型超时 / 下游不可用 |

---

## 5. 验收清单

- [ ] 游客第 4 轮被拦，且给出登录入口
- [ ] 登录后记忆页可读写，称呼在对话中生效
- [ ] 通知卡出现，红点按展示条数减少
- [ ] 卡片跳转带参数且页面筛选生效
- [ ] 高风险保存必须经过确认弹窗，令牌无效时拒绝
- [ ] `memory_json` 与 `t_ai_memory_event` 有对应记录
- [ ] 会话/消息/工具三类审计都有数据
- [ ] 四个监控指标可在 actuator 读取
- [ ] 清除记忆后 AI 不再使用旧记忆
