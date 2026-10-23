# 客服 / 私信 IM 后续能力设计（语音 · 引用 · 撤回）

- 文档性质：**设计稿，本期不实现**（2026-10-23 立项，纳入后续计划）
- 适用范围：客服会话（`t_support_message`）与私信（`t_social_message`）共用同一套交互与消息模型
- 参考对象：微信（语音条 / 引用 / 2 分钟撤回）、企业微信客服、淘宝千牛

---

## 0. 结论先行

| 能力 | 结论 | 核心难点 | 建议顺序 |
| --- | --- | --- | --- |
| 语音消息 | 可做，小程序优先 | Web 端录音格式（webm）在 Safari 不可播，需要转码或双格式策略 | ① |
| 引用回复 | 可做，三端一致 | 历史消息定位（现有分页接口只支持 page，需要 `around` 定位） | ② |
| 5 分钟撤回 | 可做，且建议先补齐 | 撤回后未读数收敛、媒体文件保留与清理、系统通知不可撤回 | ③（与语音无依赖，可与①并行） |

三条能力都**不新建表**：`t_support_message` / `t_social_message` 已经有 `biz_type`、`meta_json`、`media_url`、`duration_ms`，缺的只是 `recalled_at` 一个字段。

---

## 1. 现状与约束（改造必须尊重的既有事实）

1. **消息类型是白名单**：`TEXT / IMAGE / VIDEO / EMOJI / PRODUCT / ORDER`（客服）与 `TEXT / ASK / PRODUCT / LISTING / IMAGE / VIDEO / EMOJI`（私信）。新增类型必须同时改白名单、预览文案、通知摘要，漏一处就是「发送成功但列表显示空白」。
2. **媒体一律走上传接口**（`POST /api/uploads` → COS），消息里只存 URL。语音也必须走这条路，不新增二进制通道。
3. **无 WebSocket**：三端都是 HTTP 轮询（客服 5s / 私信 4s）。引用和撤回的实时性靠轮询天然满足，不需要引入推送。
4. **已读用游标**：`user_last_read_id` / `seller_last_read_id` + 每条消息 `read_at`。撤回属于「行还在、内容不可见」，游标逻辑不受影响 —— 这是选择软撤回的直接原因。
5. **清理策略已就位**：会话关闭 30 天后物理删除会话与消息，并按 URL 删 COS 对象。语音文件必须沿用同一套（否则就是新的存储泄漏点）。
6. **端侧差异**：小程序有 `RecorderManager`；Web 端 `MediaRecorder` 产出 webm（Chrome）或 mp4（Safari），跨端播放格式不一致。

---

## 2. 语音消息（VOICE）

### 2.1 交互（对齐微信）

- 输入栏「+」旁增加「按住说话」与键盘的切换按钮；按住录音、上滑取消、松手发送。
- 录音上限 **60 秒**（与视频同口径），剩余 10 秒开始倒计时提示；不足 1 秒松手提示「说话时间太短」。
- 气泡形态：宽度随时长在 `60–200px` 间线性变化，显示 `🔊 时长`；**未播放的消息**右上角显示红点（与微信一致）。
- 点击播放（同一条再次点击停止）；同一时刻只允许一条在播。
- 播放即视为已读：与服务端 `markRead` 同一入口，不额外定义「语音已读」。

### 2.2 数据

| 字段 | 取值 |
| --- | --- |
| `biz_type` | `VOICE` |
| `media_url` | 上传接口返回的 URL（mp3/aac/m4a/webm） |
| `duration_ms` | 必填，服务端二次校验 `> 0 && <= 60000` |
| `meta_json` | `{"format":"mp3","size":12345,"waveform":[0.1,0.6,…]}`（波形可选，第一版不做） |
| `content` | 服务端生成 `[语音]`，用于列表摘要与通知，前端不展示 |

### 2.3 端侧与格式策略（关键取舍）

| 端 | 录音能力 | 产出 | 播放 |
| --- | --- | --- | --- |
| 小程序 | `RecorderManager`（aac / mp3） | m4a/mp3 | 原生 `<audio>` 均支持 |
| Chrome/Edge | `MediaRecorder` | webm/opus | 支持 |
| Safari | `MediaRecorder` | mp4/aac | 支持 mp4，**不支持 webm** |

两个可选方案：

- **A（推荐）双格式上传**：端侧优先 mp4/aac；不支持时上传 webm 并**在同一个上传请求里标记格式**，服务端保留原文件；播放层按浏览器能力选择（`canPlayType`）。成本低，但 Safari 用户听不到 webm 的语音。
- **B 服务端转码**：引入 ffmpeg 或云函数转 mp3。体验统一，但新增运维面（转码队列、失败重试、临时文件清理），与当前「不引入重组件」的取向冲突。

**建议先做 A，并把「Safari 端 webm 不可播」显式提示为「请在小程序或 Chrome 中收听」**，等语音量起来再评估 B。

### 2.4 服务端校验

- 白名单加 `VOICE`；`duration_ms` 必填且 ≤ 60000（超出直接 422，不做静默裁剪）。
- 地址必须来自本站上传接口（沿用 `isSafeMediaUrl`）。
- 频控沿用现有窗口（用户 20 条/分、客服 60 条/分）；语音不额外放宽。
- 摘要 `previewOf` 增加 `[语音]`；通知标题同口径。

### 2.5 验收要点（EARS）

- When 用户按住说话并松手，the system shall 在 3 秒内把语音气泡显示在会话末尾，并带上时长。
- If 录音时长超过 60 秒，then the system shall 自动停止并提示已截断，而不是发送失败。
- While 一条语音正在播放，the system shall 暂停其它语音，避免串音。
- When 接收方点击语音，the system shall 标记该消息已读并使会话未读减一。

---

## 3. 引用回复（QUOTE）

### 3.1 交互

- 长按气泡（移动端）/ 右键（Web）→ 菜单「引用 / 撤回（仅本人 5 分钟内）/ 复制」。
- 引用后输入框上方出现引用条：`对方昵称：被引用的摘要 ✕`，发送成功即消失。
- 气泡内的引用块：灰色背景 + 一行摘要（最多 1 行，超出省略）；**点击引用块跳转并高亮原消息**。
- 引用只允许**同会话内**的消息；跨会话引用在 UI 上不提供入口，服务端也拒绝。

### 3.2 数据（不新建表）

`meta_json` 存快照：

```json
{"quote":{"id":"3719...","senderId":"7100...","senderName":"多多官方客服",
          "type":"TEXT","summary":"这个猫粮有货吗"}}
```

- **必须是快照而不是引用 id 现查**：原消息可能被撤回、被清理（30 天），现查会让引用块变成空白。
- `summary` 由服务端生成（文本截断 30 字；媒体用 `[图片]/[视频]/[语音]/[商品]`），**不接受前端传入的文案** —— 否则引用块可以伪造任何内容。
- 引用 + 商品卡/订单卡可以共存：`meta_json` 里同时有 `quote` 与卡片字段。

### 3.3 服务端校验

1. `quote.id` 必须存在、属于同一 `session_id` / `conversation_id`，否则 422。
2. 摘要按**库里的事实**重新生成，覆盖请求里的 `summary`。
3. 原消息已撤回时，引用块一律显示「原消息已撤回」，不再回填内容。
4. 引用不计入消息条数限制，也不改变未读规则。

### 3.4 定位原消息（需要补一个接口参数）

现有 `GET /api/support/messages?page=N` 只能按页取，引用一条 200 年前的消息时前端无法一次跳到位。

- 新增 `around=<messageId>`：服务端返回「该消息前后各 N 条」的一页，并在返回体里给 `anchorId`。
- 前端收到后滚动到 `anchorId` 并做 1.2s 高亮；找不到（已被清理）则提示「原消息已不存在」。
- 该参数同样适用于「按未读数定位」的后续优化。

### 3.5 验收要点

- When 用户引用一条文本消息并发送，the system shall 在气泡内展示「被引用人 + 摘要」，点击可定位原文。
- If 被引用消息已被撤回或清理，then the system shall 显示「原消息已撤回 / 已不存在」，且不影响新消息发送。
- If 引用的是其它会话的消息，then the system shall 返回 422 并保持输入内容不丢失。

---

## 4. 撤回（RECALL，5 分钟内）

### 4.1 交互（对齐微信）

- 仅**发送者本人**可撤回；入口在长按/右键菜单，超过 5 分钟入口消失（以服务端时间为准，客户端只做显隐）。
- 撤回后气泡替换为系统提示：本人看到「你撤回了一条消息」，对方看到「对方撤回了一条消息」；**不保留原文**。
- 撤回不删除消息行，也不改变会话顺序；最后一条被撤回时，会话列表摘要更新为「[消息已撤回]」。
- 撤回的媒体文件**不立即删除**：保留到会话清理时统一删除（避免「撤回后又被举报、需要取证」时无据可查）。

### 4.2 数据

新增一列（两条消息表各一）：

```sql
ALTER TABLE t_support_message ADD COLUMN recalled_at timestamp NULL;
ALTER TABLE t_social_message  ADD COLUMN recalled_at timestamp NULL;
```

- 展示层：`recalled_at != null` → `content` / `media_url` / `meta_json` 一律不下发，仅下发 `recalled: true`。
- 为什么不物理删行：① 引用块要能回答「原消息已撤回」而不是「不存在」；② 已读游标是消息 id 水位，删行会出现「水位指向不存在的 id」；③ 清理任务按会话整体删除，不需要逐条删。

### 4.3 服务端规则

1. `recalled_at` 为空且 `sender_id == 当前用户` 才允许；重复撤回幂等返回成功。
2. 时间窗：`now - created_at <= 5 分钟`，超时 422「超过 5 分钟，无法撤回」。
3. **未读收敛**：若接收方尚未读（`read_at` 为空），撤回时把会话对应未读数减 1（不能减到负数）。这是最容易漏的一条 —— 不处理的话，用户会看到「未读 1」却点进去什么都没有。
4. 通知中心：撤回**不回收**已经发出的通知（通知是既成事实），但在通知列表里点击进入会话后看不到内容，属可接受行为；如要更严谨，可给通知加 `revoked` 标记（第二版）。
5. 客服侧额外约束：会话关闭后客服本就不能发送，撤回不影响该规则。

### 4.4 验收要点

- When 发送者在 5 分钟内撤回，the system shall 让双方都看到「撤回了一条消息」，且原文不再出现在任何接口响应中。
- If 超过 5 分钟，then the system shall 拒绝并提示，消息保持原样。
- If 接收方未读时消息被撤回，then the system shall 把该会话未读数减 1（不为负）。
- If 被撤回的消息正被引用，then the system shall 在引用块显示「原消息已撤回」。

---

## 5. 三端一致性矩阵（实现时按此逐格验收）

| 能力 | 小程序（客服/私信） | Web 商城（客服/私信） | 商家工作台 |
| --- | --- | --- | --- |
| 语音录制 | RecorderManager | MediaRecorder（webm） | MediaRecorder |
| 语音播放 | audio | audio（按格式兜底提示） | audio |
| 引用入口 | 长按菜单 | 右键 / 长按 | 右键 |
| 引用定位 | `around` + scroll-into-view | `around` + scrollIntoView | 同左 |
| 撤回入口 | 长按菜单（仅本人 5 分钟内） | 右键菜单 | 右键菜单 |
| 撤回展示 | 「你/对方撤回了一条消息」 | 同左 | 同左 |
| 未读收敛 | 轮询刷新 | 轮询刷新 | 轮询刷新 |

---

## 6. 变更清单（实现时照此落）

**数据库**

- `docs/sql/incremental/44-message-voice-quote-recall.sql`
  - `t_support_message` / `t_social_message` 各加 `recalled_at`
  - 语音不需要新列（复用 `duration_ms` + `meta_json`）

**后端（social）**

- `SupportService.normalizeType` / `MessageService.normalizeBizType` 加 `VOICE`
- `previewOf` / 通知摘要加 `[语音]`、`[消息已撤回]`
- 新增 `POST /api/support/messages/{id}/recall`、`POST /api/socials/messages/{id}/recall`
- `GET .../messages` 增 `around` 参数
- `toMessageView` / `toView`：撤回态屏蔽内容
- 清理任务：不变（按会话整体删 + 删 COS 对象）

**前端**

- `packages/shared/src/components/chat-media.tsx`：`MediaBubble` 增 `VOICE` 分支、`VoiceRecorder` 组件
- 三个聊天页（`Support.tsx` / `Chat.tsx` / `SupportInbox.tsx`）：长按/右键菜单、引用条、撤回态渲染
- 小程序 `pages/support` / `pages/chat`：同上 + `RecorderManager`

---

## 7. 风险与待定

| 项 | 说明 | 处理建议 |
| --- | --- | --- |
| Safari 播放 webm 语音 | 格式不兼容 | 先做「格式提示」，视使用量再上转码 |
| 撤回后通知残留 | 通知已发出无法回收 | 第二版给通知加 `revoked` 标记 |
| 语音内容审核 | 语音无法像文本那样关键词过滤 | 先做时长/频率风控；如需合规，接入 ASR + 文本审核（成本较高） |
| 引用定位的翻页 | `around` 需要新 SQL（按 id 上下各取 N 条） | 与 `page` 并存，不改既有契约 |

---

## 8. 实施顺序（按依赖，不按时间）

1. **撤回**：改动最小（一列 + 两个接口 + 展示层），且能顺带补齐「未读收敛」这类容易出错的口径；
2. **引用**：依赖撤回的展示口径 + `around` 定位；
3. **语音**：端侧工作量大、跨端格式风险最高，放在最后，且先只做小程序 + Chrome。
