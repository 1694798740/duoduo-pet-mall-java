# 客服与私信 · 完整迭代计划（V1）

> 状态：**已确认（2026-10-21）**，按 A→B→C→D 落地；订单卡片一并实现；关闭后买家再发消息自动重开
> **进度：A + A6 + B + C（含 C4 订单卡）+ D 全部完成并验证（2026-10-21）**。落地记录见文末「十一」。
> 日期：2026-10-21
> 范围：商家工作台「客服信息」、商城客服页、私信（好友 / 闲置）、小程序对应页面、上传服务、个人中心
> 已拍板决策：① 未读显示「未读 N 条」，已读只在最后一条显示「已读」；② 商品卡片四类全做（含新建收藏夹与足迹）；
> ③ 视频 20MB / 60 秒；④ 商家端商品卡片点击 → 只读弹窗，不跳转

---

## 一、背景与目标

### 1.1 现状问题（来自本轮验收截图）

| # | 现象 | 性质 |
| --- | --- | --- |
| 1 | 会话列表只有「全部 / 进行中 / 已关闭」状态筛选，无法按未读筛 | 体验缺陷 |
| 2 | 会话里看不出对方是否已读 | 能力缺失 |
| 3 | 点开会话后，列表上的未读圆点不消失（要等回复/刷新） | 缺陷 |
| 4 | 商家端点击商品卡片会跳到商城商品详情（带着当前登录身份） | **越权面 / 严重** |
| 5 | 未读汇总显示成 `0000000000000000000` | 缺陷（数字被当字符串拼接） |
| 6 | 客服只能发文字 | 能力缺失 |
| 7 | 小程序不能拍照/选相册发图发视频 | 能力缺失 |
| 8 | Web 不能发图片/视频 | 能力缺失 |
| 9 | 商品卡片只能"当前浏览"，无法从购物车/收藏/已购选 | 能力缺失 |
| 10 | 私信（好友/闲置）无媒体能力 | 能力缺失 |

### 1.2 目标（可验收）

1. 客服与私信的**核心链路稳定**：文字、图片、视频、商品卡片四类消息，双端可发可收可回放。
2. 未读/已读行为对齐主流 IM：点开会话即刻清零；对方未读显示条数，读完显示「已读」。
3. 商家端不再具备"点击卡片跳商城"的路径，改为工作台内只读弹窗。
4. 商品卡片来源覆盖：当前浏览、购物车、收藏夹、已购买（个人中心补齐收藏夹与足迹入口）。
5. 不引入新的越权面：所有会话读写在服务端按 `sellerId / userId` 归属校验。

### 1.3 非目标（本轮不做）

- 语音消息、文件传输、消息撤回、消息转发、群聊。
- 客服工单的认领/转交/审计（组织规则未定）。
- 用户自定义表情包上传（本轮只提供内置表情包）。
- 消息端到端加密、敏感词审核（沿用现有内容审核策略）。

---

## 二、需求拆解（10 项 → 4 个批次）

### 批次 A · 稳定性与交互修复（P0，先做）

#### A1. 会话列表筛选：状态 → 已读/未读
> **When** 商家打开「客服信息」，**the system shall** 提供「全部 / 未读」两个筛选项，并在会话行保留「已关闭」状态标签。
- 移除 `全部/进行中/已关闭` 三态筛选（会话状态不是运营每天筛的对象；关闭状态用行内标签表达）。
- 新增「未读」筛选：`unread_for_seller > 0`。
- 列表排序：未读优先？**不**。仍按最后消息时间倒序 —— 排序突变会让运营找不到刚才那条会话。未读用加粗/圆点区分。
- 保留关闭能力（行内「关闭会话 / 重新开启」按钮），不做筛选。

#### A2. 未读圆点：点开即清零
> **When** 商家/用户打开某个会话，**the system shall** 立即把该会话未读清零，并同步更新列表角标与顶部汇总，**regardless of** 是否回复。
- 现状根因：进入会话时调了 `markRead`，但**列表数据没刷新**，角标来自旧的 `sessions` 状态。
- 修复：`markRead` 成功后本地把该会话 `unread` 置 0（乐观更新）+ 后台刷新列表；顶部汇总用 `Number()` 归约。
- 同时修 `0000000000000000000`：接口把 long 序列化成字符串（项目既有口径），前端 `reduce` 时 `sum + s.unread` 变成字符串拼接。改为 `Number(s.unread || 0)`。

#### A3. 已读回执（双向）
> **When** 对方打开会话，**the system shall** 把我方已发出且被读到的消息标为「已读」；
> **While** 对方尚未打开，**the system shall** 显示「未读 N 条」。
- 服务端记录双方读到的**最大消息 id**（`user_last_read_id` / `seller_last_read_id`），不逐条查询。
- 渲染规则（按你的选择）：
  - 我方消息区：若 `peer_last_read_id < 我方最后一条 id` → 显示「未读 N 条」（N = 对方未读的我方消息数）；
  - 对方读完之后 → 仅在**最后一条**我方消息下显示「已读」。
- 私信侧同样处理（新增读游标表，见 §四）。

#### A4. 商品卡片越权修复
> **If** 商家在会话中点击商品卡片，**then the system shall** 在工作台内弹出只读商品信息弹窗（图片/标题/价格/库存），**shall not** 跳转商城或携带当前身份访问买家页面。
- **根因**：卡片被渲染成指向商城 `/product/{id}` 的外链，商城与商家端同浏览器时，链接会带着**当前浏览器里商城的登录态**打开页面；同时"默认携带卡片"让每一条首条消息都自动挂卡，放大了这个问题。
- 修复两条：
  1. **不再默认带卡**：去掉 `productId` 自动附加；商品卡改为「加号菜单 → 发送商品」主动选择。
  2. **商家端只读弹窗**：卡片不可外链，改为弹窗（数据取 `/api/products/{id}`，只展示，不提供加入购物车/购买等买家动作）。
- 商城用户侧仍可点卡片进商品详情（那是买家自己的正常路径）。

#### A6. 客服消息接入通知中心（第七十九轮插入，优先于 B 批）
> **When** 商家回复客服消息，**the system shall** 在用户的通知中心写入一条「商家」分类的通知；
> **the system shall** 该通知可点击直达对应客服会话；**the system shall** 客服未读只计一次。
- 写入：`NoticeService.pushSupportNotice`（同会话合并为一条，`category=MERCHANT`、`bizType=SUPPORT`、
  `bizId=sessionId`、`jumpPage=/pages/support/support?sellerId=…`）。
- 网页落地页：`NoticeWebPaths` 增 `SUPPORT → /support?sellerId=…`（服务端定义，前端不自建映射）。
- **不重复计数**：`SUPPORT` 与 `MESSAGE` 一样从「通知未读」中排除；未读仍以
  `t_support_session.unread_for_user` 为准，`total = notice + message + support`。
- 已读联动：买家打开客服会话 → 该会话的通知一并标已读；通知中心「全部已读」→ 客服会话未读一并清零。
- 入口角标：头像下拉「通知中心」= `notice + support`（原先只挂 `notice`，所以徽标有数字、入口却是 0）。

#### A5. 会话内消息渲染基线
> **The system shall** 统一渲染 TEXT / PRODUCT / IMAGE / VIDEO / EMOJI 五类消息，未知类型降级为文本提示。
- 图片：等比缩略，点击查看大图（Web 灯箱 / 小程序 previewImage）。
- 视频：内嵌播放（Web `<video controls>` / 小程序 `<video>`），展示时长角标。
- 表情包：以图片形式展示（固定尺寸，不拉伸）。

### 批次 B · 多媒体消息（P0，核心能力）

#### B1. 上传服务扩展（后端）
> **When** 已登录用户上传 mp4 视频（≤20MB / ≤60s），**the system shall** 落存储并返回可访问 URL；
> **If** 超出大小、时长或类型不符，**then the system shall** 返回明确错误码与可读提示。
- `UploadController` 白名单增加 `mp4`（可选 `webm`）；图片仍走现有 `imageMogr2` 处理，**视频不得附加图片处理参数**。
- 大小上限：图片沿用 5MB；视频 20MB（配置项 `duoduo.uploads.max-video-size-mb`）。
- 时长：服务端不引 ffmpeg（不增加运行依赖），由**端侧采集时限制 + 服务端仅校验大小/类型**；端侧超过 60 秒直接拒绝。
- 读取：`GET /uploads/{uuid}.mp4` 需支持 **Range**（否则视频不能拖动进度）；本地存储返回 206，COS 走 302/直链。
- 网关/nginx：请求体上限调到 ≥25MB（`client_max_body_size`），仅对 `/api/uploads` 生效。

#### B2. 消息模型扩展
> **The system shall** 为消息持久化媒体元数据，媒体 URL 由服务端生成的存储地址，不接受任意外链。
- `t_support_message` / `t_social_message` 增列：`media_url`、`thumb_url`、`width`、`height`、`duration_ms`、`meta_json`。
- `biz_type` 扩展：`TEXT / PRODUCT / IMAGE / VIDEO / EMOJI / ORDER`（ORDER 为 P2）。
- 兼容：老数据 `media_*` 全空，渲染层按 `biz_type=TEXT` 处理，零迁移。

#### B3. Web 端发送（商城客服 + 私信 + 商家工作台）
> **When** 用户点击输入框旁的「+」，**the system shall** 提供「图片 / 视频 / 表情 / 商品」入口。
- 图片：`<input type=file accept="image/*">`，选后本地预览 → 上传 → 发送 `IMAGE`。
- 视频：`accept="video/mp4"`，选择后校验大小/时长（`loadedmetadata` 读 `duration`）→ 上传 → 发送 `VIDEO`。
- 表情：内置表情面板（约 12 个静态表情包 + Unicode emoji 面板），发送 `EMOJI` 或把 emoji 拼进 `TEXT`。
- 上传中状态：气泡先插入"发送中"占位（本地 URL + 进度），成功后替换为服务端消息；失败给重试按钮。
- 失败不得静默：错误提示必须说清是"超出大小"还是"网络失败"。

#### B4. 小程序端发送
> **When** 用户在小程序点击「+」，**the system shall** 支持 `wx.chooseMedia` 从相册选择或拍摄，并上传图片/视频后发送。
- `wx.chooseMedia({ mediaType:['image','video'], sourceType:['album','camera'] })`
  - 注意：`chooseMedia` **不要**写进 `app.json.requiredPrivateInfos`（会导致模拟器起不来，本项目已踩过）。
- 上传走 `wx.uploadFile`（不能走 `wx.request` 传二进制），带 `Authorization`。
- 视频时长在前端用 `chooseMedia` 返回的 `duration` 校验，>60 秒直接提示重选。
- 图片支持 `wx.previewImage`；视频用 `<video>` 组件播放。

#### B5. 私信（好友 / 闲置）对齐
> **When** 用户在私信中发送图片/视频/表情，**the system shall** 与客服消息使用同一套上传、存储与渲染规则；
> **the system shall** 私信商品卡仅支持"当前咨询的商品/闲置"，不提供收藏夹、足迹、购物车、已购买。
- 私信沿用 `t_social_message`，扩展同一组媒体列。
- 私信发送入口保留「聊一聊 / 问一问」的上下文（`listingId` / `productId`），卡片必须来自该上下文，不能自选别的商品。

### 批次 C · 商品卡片四类来源（P1）

#### C1. 我的足迹（新建）
> **When** 用户打开商品详情，**the system shall** 异步记录一条足迹（同一商品只保留最近一次），**shall not** 因记录失败影响页面。
- 表 `t_product_view`：`(user_id, spu_id)` 唯一，`viewed_at` upsert；保留最近 100 条/人。
- 入口：个人中心 →「我的足迹」；列表支持清空单条/全部。
- 记录位置：`GET /api/products/{id}` 服务端旁路写入（**best-effort**，失败只记日志）。

#### C2. 我的收藏夹（新建）
> **When** 用户在商品详情点击收藏，**the system shall** 幂等加入收藏夹；**When** 再次点击，**the system shall** 取消收藏。
- 表 `t_product_favorite`：`(user_id, spu_id)` 唯一。
- 接口：`POST/DELETE /api/favorites/{spuId}`、`GET /api/favorites/products?page`、`GET /api/favorites/status?spuIds=`。
- 入口：商品详情收藏按钮 + 个人中心「我的收藏夹」。

#### C3. 加号菜单 → 发送商品（四类来源）
> **When** 用户在会话中点击「+ → 商品」，**the system shall** 提供「当前浏览 / 购物车 / 收藏夹 / 已购买」四个来源；
> **When** 选中商品，**the system shall** 以该商品的快照发送一条 `PRODUCT` 消息。
- 数据来源：
  - 当前浏览：会话上下文携带的 `productId`（从商品页进入时），最多 1 条；
  - 购物车：`GET /api/cart`（已存在）；
  - 收藏夹：C2；
  - 已购买：新增 `GET /api/orders/purchased-products?page`（按订单明细去重，只取已支付/已完成）。
- 卡片快照：发送时落 `meta_json`（标题/主图/价格），读取时若商品仍存在则以现价为准，已下架则显示"商品已下架"而非空白。
- 商家端与用户端的选择器复用同一组件，仅数据范围不同（商家端不提供"已购买"）。

#### C4. 订单卡片（本轮做）
> **When** 用户在订单详情点「咨询订单」，**the system shall** 发送一条 `ORDER` 卡片（订单号脱敏 + 商品缩略 + 状态）。
- 入口：订单详情页「咨询订单」按钮；卡片落 `meta_json`（订单号后四位、商品缩略、状态）。
- 不含订单状态实时联动（避免跨域实时查询），显示发送时点的状态快照。

### 批次 D · 收尾与回归（P0/P1 完成后）

- 会话自动关闭策略：商家关闭后用户再发消息 → **自动重开**（已定）。该行为在 A 批一并落地，不放到 D。
- 客服会话列表：昵称/最后消息的关键词搜索（可选）。
- 全量回归：`auth-isolation-smoke`、`support-split-smoke`、私信/闲置冒烟、三端构建、暗夜守卫。
- 小程序真机验收（拍摄/相册/视频播放只能在真机确认）。

---

## 三、优先级与交付顺序

| 顺序 | 批次 | 为什么这个顺序 |
| --- | --- | --- |
| 1 | A（未读/已读/越权/数字 bug） | 都是"用户每天都在碰"的缺陷，且改动面小、可快速验证 |
| 2 | B（多媒体） | 核心能力，依赖上传服务扩展；先打通图片再视频（视频风险最高） |
| 3 | C（四类商品卡片） | 依赖新建收藏/足迹，功能独立，不阻塞聊天 |
| 4 | D（收尾/回归） | 全量回归与真机验收 |

**「客服不能出现重大 Bug」的保障**：每批完成后立即跑冒烟 + 反向验证，A 批不通过不进入 B 批。

---

## 四、数据模型变更（增量 38）

```sql
-- 1) 客服消息：媒体列
ALTER TABLE duoduo_social.t_support_message
  ADD COLUMN media_url   VARCHAR(500) NULL,
  ADD COLUMN thumb_url   VARCHAR(500) NULL,
  ADD COLUMN width       INT NULL,
  ADD COLUMN height      INT NULL,
  ADD COLUMN duration_ms INT NULL,
  ADD COLUMN meta_json   VARCHAR(1000) NULL;

-- 2) 客服会话：双向读游标
ALTER TABLE duoduo_social.t_support_session
  ADD COLUMN user_last_read_id   BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN seller_last_read_id BIGINT NOT NULL DEFAULT 0;

-- 3) 私信：媒体列 + 读游标
ALTER TABLE duoduo_social.t_social_message
  ADD COLUMN media_url VARCHAR(500) NULL,
  ADD COLUMN thumb_url VARCHAR(500) NULL,
  ADD COLUMN width INT NULL,
  ADD COLUMN height INT NULL,
  ADD COLUMN duration_ms INT NULL,
  ADD COLUMN meta_json VARCHAR(1000) NULL;

CREATE TABLE IF NOT EXISTS duoduo_social.t_social_read_cursor (
  conversation_id VARCHAR(80) NOT NULL,
  user_id         BIGINT      NOT NULL,
  last_read_id    BIGINT      NOT NULL DEFAULT 0,
  updated_at      TIMESTAMP   NOT NULL,
  PRIMARY KEY (conversation_id, user_id)
);

-- 4) 收藏夹 / 足迹（商品域）
CREATE TABLE IF NOT EXISTS duoduo_product.t_product_favorite (
  id         BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  spu_id     BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fav (user_id, spu_id)
);

CREATE TABLE IF NOT EXISTS duoduo_product.t_product_view (
  id        BIGINT NOT NULL,
  user_id   BIGINT NOT NULL,
  spu_id    BIGINT NOT NULL,
  viewed_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_view (user_id, spu_id),
  KEY idx_view_time (user_id, viewed_at)
);
```

**回填**：老会话的读游标按"历史已读最大值"初始化一次（`MAX(id) WHERE read_at IS NOT NULL`），
否则上线瞬间所有历史消息都会被算成"未读 N 条"。

---

## 五、接口清单

| 方法 | 路径 | 说明 | 批次 |
| --- | --- | --- | --- |
| POST | `/api/uploads` | 扩展视频（mp4，≤20MB） | B1 |
| GET | `/uploads/{file}` | 支持 Range（视频拖动） | B1 |
| POST | `/api/support/messages` | 扩展 `type/mediaUrl/thumbUrl/width/height/durationMs` | B2 |
| GET | `/api/support/messages` | 返回 `peerLastReadId`、`myUnreadCount`（未读条数） | A3/B2 |
| POST | `/api/support/read` | 返回被标记条数 + 新游标 | A2 |
| GET | `/api/seller/support/sessions` | 支持 `onlyUnread=true` | A1 |
| POST | `/api/socials/messages` | 私信扩展媒体字段 | B5 |
| GET | `/api/socials/messages/conversations/{peerId}` | 返回对端读游标 + 未读条数 | A3/B5 |
| POST | `/api/favorites/{spuId}` / `DELETE` | 收藏/取消 | C2 |
| GET | `/api/favorites/products` | 收藏列表 | C2 |
| GET | `/api/history/products` | 足迹列表 | C1 |
| GET | `/api/orders/purchased-products` | 已购买商品（去重） | C3 |
| GET | `/api/products/{id}` | 旁路记录足迹（已登录时） | C1 |

---

## 六、前端改动

### 商家工作台（`apps/merchant`）
- 客服列表：筛选「全部/未读」、行内「已关闭」标签、点开即清零（乐观更新 + 刷新汇总）。
- 会话区：媒体气泡、已读回执、加号菜单（图片/视频/表情/商品）、商品只读弹窗。
- 顶部汇总：`Number()` 归约，杜绝字符串拼接。

### 商城（`apps/mall`）
- 客服页：同上（无商家侧只读弹窗，卡片可正常跳商品详情）。
- 私信页：媒体/表情/当前商品卡；已读回执。
- 商品详情：收藏按钮、足迹旁路（服务端）、「问客服」仍带当前商品上下文但**不自动发卡**。
- 个人中心：新增「我的收藏夹」「我的足迹」入口与页面。

### 小程序
- 客服页 / 私信页：`chooseMedia` 拍摄与相册、`uploadFile`、图片预览、视频播放、表情面板、加号菜单。
- 个人中心：收藏夹/足迹入口。
- `app.json` 不新增 `requiredPrivateInfos`（避免模拟器无法启动）。

---

## 七、验收标准（EARS 摘要）

1. **When** 商家点开会话，**the system shall** 在 1 秒内把该会话未读清零，列表与顶部汇总同步更新。
2. **While** 对方未读，**the system shall** 显示「未读 N 条」；对方读完后，**shall** 在最后一条我方消息显示「已读」。
3. **If** 商家点击商品卡片，**then the system shall** 只弹出只读信息弹窗，不发起任何跨站跳转。
4. **When** 用户发送图片/视频（≤20MB/≤60s），**the system shall** 在双端正确渲染，刷新后仍可见。
5. **When** 用户在小程序拍摄并发送图片，**the system shall** 上传成功并在 Web 端可查看。
6. **When** 用户从购物车/收藏夹/已购买/当前浏览选择商品，**the system shall** 发送带商品信息的卡片；商品下架后**shall** 显示「商品已下架」。
7. **If** 上传超过大小或时长限制，**then the system shall** 拒绝并给出具体原因，不产生半条消息。
8. **The system shall** 保证私信会话中不出现收藏夹/足迹/购物车/已购买来源的商品卡。

---

## 八、风险与降级方案

| 风险 | 影响 | 降级/备选 |
| --- | --- | --- |
| 视频 Range 在本地存储不支持 | 视频不能拖动、Safari 无法播放 | 读取改为 302 到 COS 直链；或本地存储实现 206 分段 |
| 网关/nginx 请求体上限 | 20MB 视频被 413 拒绝 | 仅对 `/api/uploads` 放宽；必要时前端分片（本轮不做） |
| 小程序真机能力（拍摄） | 开发者工具测不到 | 明确列为真机验收项，模拟器只验相册选择 |
| 收藏/足迹 新建表 | 增量迁移与回填 | 纯新增表，无历史数据；收藏为空不影响商品详情 |
| 已读回执老数据 | 上线瞬间"未读 N 条"虚高 | 迁移时按历史 `read_at` 初始化游标 |
| 媒体消息体积 | 会话列表变慢 | 列表只存首条消息摘要（已有），媒体懒加载 + `loading=lazy` |

---

## 九、已确认决策（2026-10-21）

1. 执行顺序：**A → B → C → D**，每批完成后跑冒烟与反向验证，再进下一批。
2. 订单卡片：**本轮一并实现**（C4）。
3. 会话关闭后买家再发消息：**自动重开**（A 批落地，不等 D）。
4. 已读展示：未读时显示「未读 N 条」，读完后只在最后一条我方消息显示「已读」。
5. 商品卡片：四类全做（当前浏览 / 购物车 / 收藏夹 / 已购买）。
6. 视频：单条 ≤20MB / ≤60 秒，mp4。
7. 商家端商品卡片：**只读弹窗**，不跳转。


---

## 十、A 批落地记录（2026-10-21，已验证）

| 项 | 实现 | 验证 |
| --- | --- | --- |
| A1 筛选 | 商家端改为「全部 / 未读」，`onlyUnread` 走服务端 `unread_for_seller > 0`；行内保留「已关闭」标签 | UI 断言筛选项只有两项 |
| A2 未读清零 | 点开调 `markRead` → 服务端置游标+未读归零；前端乐观置 0 | 角标 1 → 0（Playwright） |
| A2b 数字 bug | `Number(s.unread)` 归约（接口把 long 序列化成字符串） | 汇总不再出现长串 0 |
| A3 已读回执 | `t_support_session` 增 `user_last_read_id / seller_last_read_id`（增量 38，含历史回填）；thread 返回 `peerLastReadId + myUnreadCount` | 冒烟 16/0：未读 N 条 → 已读 |
| A4 越权修复 | 卡片改为工作台内只读弹窗（不再外链商城）；**且不再默认携带商品卡** | 弹窗出现且 URL 未变 |
| A5 自动重开 | 买家发消息时若会话 CLOSED → 服务端改回 OPEN；商家在关闭状态仍不能回复 | 冒烟覆盖 |
| A6 通知中心 | 客服回复写 `MERCHANT/SUPPORT` 通知（同会话合并）、可点击直达 `/support`；通知未读排除 SUPPORT；打开会话同步已读；菜单角标 = notice + support | 冒烟断言 4 项；UI 菜单角标 1、通知页「商家」分类可见、点击跳转正确 |

**A 批额外发现并修掉的隐患**：列表加载时自动选中第一条会话，会在用户还没看时把它标成已读
（未读角标莫名消失）。已改为「列表只展示，点开才算读」；从商品页带 sellerId 进入时仍直接定位（明确意图）。

新增回归脚本：`scripts/support-read-smoke.py`（**20/0**，含通知中心四项断言）。


---

## 十一、B/C/D 落地记录（2026-10-21）

### B 批：多媒体消息（已完成）

| 项 | 实现 |
| --- | --- |
| 上传 | `UploadController` 白名单加 `mp4`；图片 5MB / 视频 20MB（`duoduo.uploads.max-video-size-mb`）；扩展名与 MIME 必须一致 |
| 播放 | `GET /uploads/{file}` 支持 `Range` → 206 + `Content-Range`（本地存储切片）；视频不追加 imageMogr2 参数 |
| 网关/nginx | nginx `client_max_body_size` 16m → 25m（仅三站点静态入口） |
| 数据 | 增量 39：`t_support_message` / `t_social_message` 各加 6 列（media_url/thumb_url/width/height/duration_ms/meta_json） |
| 接口 | 客服用户侧 + 商家侧 + 私信均支持 `IMAGE/VIDEO/EMOJI`；媒体地址只收 http(s) 或 `/uploads/` |
| Web | 共享 `ChatMediaMenu` / `MediaBubble`：图片（点击新窗）、视频（内嵌播放）、表情面板；三页复用（客服/商家/私信） |
| 小程序 | 客服页与私信页 `wx.chooseMedia`（相册+拍摄）、`api.upload`、图片预览、`<video>` 播放、＋按钮 |
| 表情 | 内置 emoji 面板；纯 emoji 文本在气泡中放大显示（不引入贴纸资源管线） |

### C 批：四类商品来源（除订单卡已完成）

| 项 | 实现 |
| --- | --- |
| 数据 | 增量 40：`t_product_favorite` / `t_product_view`（均 (user, spu) 唯一） |
| 收藏 | `POST/DELETE /api/favorites/{spuId}`、`GET /api/favorites/status`、`GET /api/favorites/products` |
| 足迹 | 商品详情旁路记录（best-effort）；`GET /api/history/products`、`DELETE /api/history` |
| 已购 | 交易域新增 `GET /api/orders/purchased-products`（按 spu 去重，仅 PAID/SHIPPED/COMPLETED） |
| 选择器 | `ProductPicker`：当前浏览 / 购物车 / 收藏夹 / 已购买；客服页「🛍」入口 |
| 私信 | 仅提供「当前咨询商品」卡（🛍 直接发送），不接收藏/购物车/已购 |
| 个人中心 | 新增「我的收藏夹」「我的足迹」入口与页面；商品详情新增收藏按钮 |
| C4 订单卡 | 订单详情「咨询订单」→ 服务端回交易域取订单事实并校验归属 → 落 `meta_json` 快照；买家端卡片可跳订单详情，商家端只读 |
| 贴纸 | 内置 6 张 SVG 贴纸（站内 `/stickers/*`），以 `EMOJI` 消息发送；纯 emoji 文本仍走放大显示 |

### D 批：回归（已完成）

| 检查 | 结果 |
| --- | --- |
| `scripts/media-favorite-smoke.py` | 12/0（图片、mp4、Range 206、图片消息、收藏、足迹、已购） |
| `scripts/support-read-smoke.py` | 20/0 |
| `scripts/support-split-smoke.py` | 22/0 |
| `scripts/auth-isolation-smoke.py` | 11/0 |
| `check-web-theme.mjs` | 55/0 |
| `check-miniprogram-dark.mjs` | 25/0 |
| 三端 `npm run build` | 通过 |
| UI 实测 | 图片气泡、商品选择器（3 页签）、发送商品卡、收藏页 2 件、足迹页 1 件 |

### 环境限制（非代码遗留）

1. **真实视频播放：已用真实文件验证（2026-10-22）** —— 用户提供的 81MB 录屏先转码到 720p/60s（5.5MB），
   上传到 COS 后实测：`206 + Content-Range: bytes 0-1023/5790626`、播放器 `duration=60`、
   `readyState=4`、`play()` 后 `currentTime=1.13s` 正常走带；商家端同样能播放。
   **注意**：原始 81MB 文件会触发 20MB 上限拒绝（这是设计行为）。真实用户若发送大文件，
   需要在客户端压缩或提高上限（改 `duoduo.uploads.max-video-size-mb` 与 nginx `client_max_body_size`）。
2. **贴纸**：内置 6 张 SVG（ok/thanks/wait/cry/love/angry），未接入用户自定义贴纸。
