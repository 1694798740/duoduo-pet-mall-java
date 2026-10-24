# 即时通讯大型迭代方案（实时消息 + 语音 + 撤回 + 引用）

> 版本：v1.1 · 2026-10-24（v1.0 · 2026-10-23）
> 范围：客服聊天（support）+ 私信聊天（direct message）
> 状态：**四项全部落地**——实时消息（90 轮）+ 撤回/引用/语音（91 轮，2026-10-24，冒烟 25/0 + 回归 7 套全绿）。

---

## 一、总体结论

本次迭代把聊天体系从「HTTP 轮询刷新」升级为「WebSocket 实时推送」，四项硬性需求全部落地并验证：

| 需求 | 结果 |
| --- | --- |
| 500+ 并发在线会话，接口响应 < 1 秒 | ✅ 520 条并发连接 2.3 秒内建齐，推送 32ms 到达 |
| 「对方正在输入」双向提示 | ✅ TYPING 信号 + 700ms 服务端节流 + 3s 前端自动消失 |
| 已读回执双向信号 | ✅ READ 信号 + `maxReadId` 游标，<1s 送达 |
| 消息推送零感知 | ✅ 新消息/已读状态即时推送到对端，无需手动刷新 |

## 二、技术选型与理由

**选型：原生 WebSocket（Spring `WebSocketHandler`）+ Redis Pub/Sub 扇出 + 一次性短票据鉴权 + 「纯信号」推送。**

对比过的备选方案：

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 继续轮询 | 实现最简 | 延迟秒级、空请求多，无法满足 <1s | 否决 |
| SSE | 服务端单向简单 | 半双工（只能推不能收），断线重连语义弱 | 否决 |
| STOMP/SockJS | 子协议成熟 | 引入额外协议栈，本项目消息模型简单，收益不抵复杂度 | 否决 |
| 第三方 IM（腾讯 IM 等） | 免运维 | 成本、数据出域、与现有账号/权限体系割裂 | 否决 |
| **原生 WS + Redis 扇出** | 零新依赖心智、可控性强、天然支持多实例 | 需自管心跳/重连/鉴权 | **采用** |

关键设计决策：

1. **鉴权：一次性短票据（60s，Redis `GETDEL` 消费）**，而非 JWT 放 URL。票据泄露窗口极短且只能用一次。
2. **网关预检（`WsTicketGlobalFilter`）**：WS 握手一旦完成，客户端看到 101 成功，后端再 401 只会让前端报「异常断开（1006）」。因此网关在升级前用 Redis `hasKey` 预检票据，无效直接 401，杜绝假成功。
3. **纯信号推送**：WS 只传「路由/状态信号」（MESSAGE/READ/TYPING/READY/PONG），**不带消息正文**。前端收到信号后走既有 HTTP 接口拉取（`mine` 标记、分页窗口等渲染逻辑只有一条路径），避免「WS 正文 + HTTP 拉取」双渲染源不一致的整类 bug。
4. **多实例横向扩展**：跨实例推送经 Redis Pub/Sub（channel `duoduo:realtime`），连接注册表按实例本地维护；单实例降级时自动走本地直发，无 Redis 也能跑（开发环境容错）。
5. **越权防护**：接收方由 `RealtimePeerResolver` 从库回源（客服会话查 sellerId/userId，私信按会话 id 规则解析），非会话参与方的事件不转发——冒烟测试第 5 节专项验证。

## 三、已实现架构（实时消息）

```
客户端(web/小程序)                     服务端
┌──────────────┐  ① POST /api/socials/realtime/ticket   ┌──────────────┐
│  RealtimeClient│ ───────────────────────────────────▶ │  social 服务   │
│ (重连退避/心跳) │  ② WS /ws/chat?ticket=xxx            │ WsHandshake   │
└──────┬───────┘ ◀────── 信号推送(MESSAGE/READ/TYPING) ─ │ ConnectionReg │
       │ ③ 收到信号 → 走既有 HTTP 拉取                    │ ChatSocketHd  │
└──────┴───────┘                                       └──────┬───────┘
  发消息/已读仍走 HTTP → 服务端写库后 publish → Redis Pub/Sub ──▶ 各实例 Subscriber → 本地连接
```

组件清单（`duoduo-social/.../realtime/`）：

- `RealtimeTicketKeys`（common）：Redis key 前缀
- `WsTicketService`：票据签发/消费（Redis SETEX 60s + GETDEL；无 Redis 时本地 Map 兜底）
- `WsHandshakeInterceptor`：握手期校验票据 → userId 放入 attributes
- `ChatSocketHandler`：消息处理（PING→PONG、TYPING 700ms 节流转发）
- `ConnectionRegistry`：userId → 连接集合；30s 心跳清理死链；按会话定向发送
- `RealtimePublisher` / `RealtimeSubscriber`：Redis Pub/Sub 扇出，本地兜底
- `RealtimePeerResolver`：从库回源会话参与方，防越权转发
- `RealtimeController`：`POST /api/socials/realtime/ticket`、`GET /api/socials/realtime/online`

接入点：

- `SupportService`：买家/商家发消息后 `pushRealtime`，双方已读接口 `pushRealtimeRead`
- `MessageService`（私信）：发消息 `pushRealtime`；`markRead` 发布 READ + `maxReadId` 游标
- 网关：`/ws/**` 早放行 + 票据预检（order -80）；路由 `duoduo-social-ws → lb:ws://duoduo-social`
- Nginx：三套配置均加 `/ws/` 反代（Upgrade 头、`proxy_read_timeout 3600s`）
- Vite：mall/merchant dev 代理 `ws: true`

前端：

- `frontend/packages/shared/src/realtime.ts`：`RealtimeClient`（引用计数共享连接、25s 心跳、10s PONG 超时判死、指数退避重连 1s→30s、TYPING 2s 节流）+ `useRealtime(handler)` Hook（ref 订阅，免重渲染重连）
- `Support.tsx` / `SupportInbox.tsx` / `Chat.tsx`：MESSAGE→重新拉取会话；READ→重新拉取（未读徽标即时归零）；TYPING→对方输入提示（3s 自动消失）
- 小程序：`utils/realtime.js`（`wx.connectSocket` 同协议），`pages/support` / `pages/chat` onShow 建连 / onHide·onUnload 断开，`.chat-typing` 提示

## 四、验证结果（全绿）

**实时冒烟（`scripts/realtime-smoke.mjs`）：17 通过 / 0 失败**

1. 票据建连：双端 READY ✅；无效票据被网关 401 拒绝 ✅
2. 消息送达：HTTP 写入 → WS 推送 **32ms**（<1s）✅；事件带正确会话 id ✅
3. 已读回执：<1s 送达、游标为正 ✅
4. 正在输入：双向、标明输入方 ✅
5. 越权：非参与方 TYPING 不转发 ✅
6. 容量：520 并发连接 2294ms 建齐；500+ 连接下推送仍 <1s；服务端在线数与客户端一致 ✅

**回归（实时改造后全部重跑）：**

- `support-session-ui-check` 39/0、`support-ux-ui-check` 53/0、`seller-ui-check` 40/0、`support-read-smoke` 24/0、`notice-chat-follow-ui-check` 50/0

## 五、语音消息方案（已实施，2026-10-24）

**结论：用「HTTP 上传 + WS 信号通知」实现，与现有图片/视频消息同构。**

- 录制：web 端 `MediaRecorder`（audio/webm；Safari 兜底 audio/mp4）；小程序 `wx.getRecorderManager()`（aac，采样率 44.1k）。
- 形态：沿用现有 `MSG_TYPE` 体系新增 `VOICE`；字段 `mediaUrl` + `durationMs`（<=60s 限制，端侧校验）。**不做服务端转码**，端侧直接录出可播格式。
- 上传：复用现有 fulfilment COS 上传链路（`/api/uploads`），气泡内 `<audio>` / `wx.createInnerAudioContext` 播放 + 波形进度条（简化为长度比例条即可，不做逐帧波形以省工作量）。
- 未读红点/已读回执/推送：完全复用本次实时通道——服务端只发 MESSAGE 信号，语音细节由拉取接口返回，**实时层零改动**。
- 边界：录音中锁输入框；切后台/来电中断（小程序 `onStop` 回调补发）；HTTPS 强制（浏览器 getUserMedia 限制）。

## 六、消息撤回方案（已实施，2026-10-24）

**结论：软删 + 2 分钟窗口 + WS 信号 + 30 天审计留痕。**

- 表：`im_message` / support message 加 `recalled_at`、`recall_reason`（可空）。**不物理删除**，审计与对账（铁律：台账说删了 ≠ 删了，要指得出实物）需要留痕。
- 服务端 `POST /recall`：仅发送者本人；`now - created_at <= 120s`；已撤回幂等返回成功。撤回后 `content/media` 服务端置空脱敏（`SELECT` 层排除，而不是只靠前端隐藏）。
- WS：新增信号类型 `RECALL`（复用本次 RealtimeEvent record，加一个 type），对端收到后重拉对应消息。
- UI：气泡变「对方撤回了一条消息」灰字；撤回方 2 分钟内显示可撤回按钮。
- 边界：撤回后未读计数同步减；客服会话关闭状态下仍允许撤回（自己消息自己负责）；小程序同步同逻辑。

## 七、引用消息方案（已实施，2026-10-24）

**结论：消息携带 `reply_to_id`，渲染时拼 quoted 卡片；不做嵌套。**

- 发送：长按/悬停气泡 → 「引用」→ 输入框上方显示被引用摘要（前 40 字符 + 类型图标）；发送时带 `reply_to_id`。
- 存储：单列 `reply_to_id BIGINT`，无新表。服务端返回时**服务端拼装** `quoted: {id, senderName, type, excerpt}`（前 60 字符；媒体类型显示「[图片]/[视频]/[语音]」），不信任端侧传摘要。
- 渲染：气泡顶部灰色小条（一或两行截断）；点击滚动定位到原消息（在分页窗口内直接 scrollIntoView；不在窗口内则先按 id 单独拉取该条）。**不嵌套**：被引用的消息本身带引用时，摘要条不递归。
- 边界：引用已撤回消息 → 显示「原消息已撤回」；引用不存在的消息（越权/清理）→ 服务端置空 quoted，端侧降级为普通消息；产品卡片/语音同样可被引用。

## 八、实施顺序（下一批）

1. **撤回**（改动面最小：一列 + 一接口 + 一信号类型，先打样 RECALL 信号扩展点）
2. **引用**（依赖撤回的「已撤回显示」交互）
3. **语音**（涉及端侧录制权限/格式适配，体量最大放最后）
4. 每步完成后补对应冒烟脚本 + UI e2e，再合并回归

## 九、风险与备注

- Redis Pub/Sub 为 at-most-once：断线重连后由既有 HTTP 拉取兜底补齐（纯信号设计的根本好处——漏一条信号不丢数据）。
- 单实例 Redis 故障时票据/扇出有本地兜底，但多实例间推送会失效——生产部署必须保证 Redis 可用。
- 网关 `/ws/**` 早放行 + 预检是安全关键路径，改动网关时勿动该 filter 的 order。
- 小程序真机需在 mp 后台配置 wss 域名（生产为 HTTPS/WSS，本地开发为 ws://localhost）。
