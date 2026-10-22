# 客服消息独立化改造方案（已实施 · 第七十八轮）

> 状态：**已实施（2026-10-21）**，代码与增量 37 均已落地并验证（冒烟 22/0，含反向验证 21/1）。
> 已拍板：① 方案 A（域内独立子包）② 一人一会话 ③ 商家工作台可回复（自营客服账号以 SELLER 登录接待）
> **职责重划（本轮修订）**：客服接待**只在商家工作台**；平台运营端**不提供**任何私信/客服会话入口，
> 只处理平台级事务（审核、仲裁、干预、审计）。原方案里的"平台后台工作台"已取消。
> 撰写：2026-10-21
> 背景：管理后台看不到用户客服消息 —— 根因不是数据/权限，而是 A4 那轮只建了接口（`/api/socials/support/**`）、
> 没有后台界面，且客服会话是以普通私信的形式存在（数据在 `t_social_message`，会混进私信列表）。
> 本方案把客服消息从私信模块中**彻底分离**：独立数据表、独立接口、独立入口、独立未读、独立工作台。

---

## 一、目标与不做什么

### 目标
1. 客服消息**不再出现在私信列表**，也不再受私信规则约束（特别是"非互关限一条"）。
2. 客服有独立的用户入口（Web / 小程序）、独立的会话与消息表、独立的未读数。
3. 平台运营能在管理后台看到并回复全部客服会话（含历史已迁入的消息）。
4. 客服会话与普通私信的边界写进代码与文档，后续不再靠注释维护口径。

### 不做（本轮明确排除）
- 不新建独立微服务（见 §二 方案选型；若你选 B 则另说）。
- 不做多人客服的认领 / 转交 / 审计工单（组织规则未定，属下一轮）。
- 不做客服会话的实时推送（沿用现有轮询；后台工作台用 5 秒轮询）。
- **商家端（merchant 后台）本轮不加消息页**：入驻商品的"店铺咨询"仍走私信，语义未变；
  商家侧同类工作台建议下一轮以同构组件、按 `sellerId` 取数单独实现。

---

## 二、架构选型

| | 方案 A（推荐） | 方案 B |
|---|---|---|
| 形态 | 留在 `duoduo-social` 模块内，新增 `support` 子包 + 独立表 | 新建 `duoduo-support` 微服务 |
| 数据 | 新表 `t_support_session` / `t_support_message`，与私信零共享 | 同左，独立库 |
| 接口 | 新前缀 `/api/support/**` | 同左，独立网关路由与容器 |
| 成本 | 一套迁移 + 一个子包 | 额外容器、网关路由、配置、CI、自测基线全部 +1 |
| 隔离度 | 代码路径与数据全分开，仅共用 ID 生成器与资料回源 | 物理隔离 |
| 结论 | **推荐**。当前单客服账号规模，物理隔离的收益不抵运维成本；未来要拆只搬子包 | 若你要求物理隔离再做 |

本方案后续内容按 **方案 A** 编写。

---

## 三、数据模型

### 3.1 `t_support_session`（一人一会话）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| user_id | BIGINT | 咨询用户，**唯一索引**（一人一客服会话） |
| status | VARCHAR(20) | `OPEN` / `CLOSED` |
| last_message_at | TIMESTAMP | 列表排序用 |
| last_message_preview | VARCHAR(120) | 列表摘要，避免每行子查询 |
| unread_for_user | INT | 用户未读（冗余计数，读时也回表校验） |
| unread_for_agent | INT | 客服未读 |
| created_at / updated_at | TIMESTAMP | — |

> **关键决策（已确认）**：客服采用**一人一会话**，商品咨询以"消息上的商品上下文"呈现，
> 而不是像私信那样每件商品开一个子会话。理由：客服是"用户对平台"的单一关系，
> 按商品分会话会让工作台堆出几十条碎片会话，与"一个人一件事"的接待直觉冲突。
> 代价：用户问过 3 件商品，都在同一条会话里（前端显示商品卡片区分）。

### 3.2 `t_support_message`
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| session_id | BIGINT | 索引 `(session_id, created_at)` |
| sender_id | BIGINT | 用户或客服账号 |
| sender_role | VARCHAR(20) | `USER` / `AGENT` / `SYSTEM` |
| content | VARCHAR(1000) | 正文 |
| biz_type | VARCHAR(20) | `TEXT` / `PRODUCT`（商品卡上下文） |
| biz_id | BIGINT NULL | 商品 spuId |
| read_at | TIMESTAMP NULL | 接收方已读时间 |
| created_at | TIMESTAMP | — |

### 3.3 会话标识
- 不再使用 `lo:hi` 字符串键。客服会话以 `session_id` 为主键，用户维度用 `user_id` 唯一索引定位。
- 这样"私信列表里混进客服会话"在数据层就不可能发生 —— 不是靠查询里加过滤条件排除。

---

## 四、接口设计

### 4.1 用户侧 `/api/support/**`（需登录，网关不进白名单）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/support/session` | 取或建我的客服会话（幂等）；返回 `sessionId`、未读数、最近一条、客服在线说明 |
| GET | `/api/support/session/messages?page&pageSize` | 历史消息，时间正序，分页取最新一页 |
| POST | `/api/support/session/messages` | 发送；`content`、可选 `productId`（附商品卡） |
| POST | `/api/support/session/read` | 标记已读（幂等） |
| GET | `/api/support/unread-count` | 我的客服未读数 |

`GET /api/support/session` 同时承担原 `support/agent` 的职责：**建会话前回源校验客服账号存在**，
不存在返回 503 `SUPPORT_AGENT_NOT_READY`（保留现有静默失败防护，不退回"200 + 假会话"）。

### 4.2 商家侧 `/api/seller/support/**`（`LoginUser.isSeller()` 准入）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/seller/support/sessions?status&page&pageSize` | 本店会话：按最后消息倒序，带未读数、用户昵称头像、最后一条摘要 |
| GET | `/api/seller/support/sessions/{id}/messages?page` | 会话详情（校验会话归属本店，不属于 → 403） |
| POST | `/api/seller/support/sessions/{id}/messages` | 商家回复；sender = 当前登录店铺主体 |
| POST | `/api/seller/support/sessions/{id}/read` | 商家侧已读 |
| POST | `/api/seller/support/sessions/{id}/status?value=OPEN\|CLOSED` | 关闭 / 重新开启 |
| GET | `/api/seller/support/unread-count` | 工作台角标 |

> 自营客服账号以 SELLER 身份登录时，走的就是这同一套接口，**不需要**"平台运营代发"：
> 回复人天然是"多多官方客服"，没有"回复人变成运营本人"的坑。

---

## 五、关注限制的处置（逐场景梳理）

| 场景 | 现行规则 | 改后 |
|---|---|---|
| **客服会话** | 主会话走"非互关每人限发一条" → 用户发完第一条就被挡住（就是你截图里那句提示） | **完全移除**。新路径不经过 `MessageService` 的限发分支，也不存在"互关"概念 |
| 普通私聊（用户↔用户） | 非互关限一条，互关后不限 | **保留**。这是防骚扰规则，与客服无关 |
| 商品咨询（入驻卖家） | 咨询会话豁免 | 保留豁免 |
| 闲置咨询 | 咨询会话豁免 | 保留豁免 |
| 客服频控 | 60 秒 / 10 条（与私信共用） | 独立：60 秒 / **20 条**（客服场景用户常连发补充说明），超限 429 |
| 正文长度 | 1000 字 | 不变 |

**结论**：关注限制只保留在"纯社交私聊"上；凡是"用户对平台/对商家"的咨询语义，都不适用。

---

## 六、入口与展示位置

### 6.1 用户侧
| 端 | 现状 | 改后 |
|---|---|---|
| Web | `SupportEntry` → `/messages/:peerId`（复用私信会话页） | 新建 `/support` 独立页；`SupportEntry` 直接跳 `/support` |
| Web 商品详情（自营） | 「问一问」→ 私信会话（客服收件人） | → `/support?productId=xxx`，会话内显示商品卡 |
| Web 商品详情（入驻） | 「问一问」→ 卖家私信 | **不变** |
| Web 订单页 / 订单详情 | `SupportEntry` | 跳 `/support`，可带订单号作为首条上下文（可选） |
| 小程序 | `profile.goSupport()` → `pages/chat/chat?peerId=` | 新建 `pages/support/support`；入口改跳该页 |
| 小程序 商品页自营 | 同 Web | 跳 `pages/support/support?productId=` |

`/support` 页与 `/messages/:peerId` 共用气泡视觉，但**去掉"互相关注 / 返回列表 / 只能发一条"整套私信语义**，
顶部显示"多多官方客服 · 工作时间说明"，底部常驻输入框。

### 6.2 商家工作台（本轮实际落地位置）
- 新增页签 **「客服信息」**（`MerchantRoot.tsx` 的 `TABS_FOR_OWNER`，位置在「订单管理」之后、「售后处理」之前），
  路由 `/seller/support`，页面 `frontend/apps/merchant/src/pages/seller/SupportInbox.tsx`。
- 布局：左侧会话列表（全部 / 进行中 / 已关闭）+ 右侧聊天区 + 回复框 + 关闭/重开会话。
- 准入：`SellerSupportController.requireSellerId()` 要求 `LoginUser.isSeller()`，
  `sellerId` **由登录态推导**，请求里不接受该参数 —— 换 id 看别家会话这条路从契约上不存在。
- **平台管理后台不加任何客服页签**：`/api/admin/**` 与 `/api/support/**` 分离，运营账号即使登录也读不到会话内容。

### 6.3 两类客服的区分（自营 / 第三方）
| 维度 | 多多自营客服 | 第三方商家客服 |
|---|---|---|
| 接待方 `seller_id` | 官方客服账号 `710000000000000001` | 商品归属卖家 `t_spu.seller_id` |
| 数据归属 | 自营店铺（该账号本身是 SELLER） | 各自店铺 |
| 入口 | 商品详情（自营）/ 订单页 / 我的 → 问客服 | 商品详情（入驻）「问一问」带 sellerId |
| 接待位置 | 商家工作台，用**官方客服账号**登录 | 商家工作台，用**商家账号**登录 |
| 代码路径 | **完全相同**（靠 sellerId 区分，不靠类型字段） | 同左 |
| 消息分流 | 落在 `t_support_message`，session 键 = (客服账号, 用户) | 同左，session 键 = (卖家, 用户) |

> 为什么不用 `type=SELF/THIRD` 字段：类型与 seller_id 是同一事实的两种写法，
> 多一个字段就多一次"两者不一致"的机会。判据统一为 `sellerId == officialCsUserId`。

### 6.4 闲置/跳蚤市场：不进客服
- 闲置只保留 **私信** 通道（详情页「聊一聊」/「问一问」→ `pages/chat`、`/messages/:peerId?listingId=`）。
- 理由：闲置是买卖双方的个人交易，平台不介入履约与售后，没有"客服"这个角色可当。
- 代码边界：`MessageService` 的 `LISTING` 分支保持原样；客服域（`SupportService`）**不接收** `listingId`。
- 商家工作台的「客服信息」里因此**不会**出现闲置会话。

---

## 七、未读与通知

| 项 | 现状 | 改后 |
|---|---|---|
| 私信未读 | `t_social_message.read_at IS NULL` 计数 | 不含客服（数据已分离） |
| 客服未读 | 混在私信未读里 | `t_support_session.unread_for_user` 独立计数 |
| 角标总数 | 通知 + 私信 | 通知 + 私信 + **客服**（`UnreadService.summary` 加第三项） |
| 到达通知 | 写 `t_social_notice`（FRIEND / MERCHANT） | **不再写**。客服消息不进"好友消息/商家消息"；如需提醒，用独立 `SUPPORT` 分类指着 `/support`（本轮建议暂不加，避免三重计数） |

★ 连带改动（最容易漏）：`SocialDtos.UnreadSummary` 加字段 → 小程序 `utils/unread.js`、
Web 顶栏角标、`GET /api/socials/notices/unread-summary` 的返回结构。三处必须同轮改完，
否则会出现"角标 3、点进去 1"的自相矛盾。

---

## 八、历史数据迁移（增量 37）

文件：`docs/sql/incremental/37-support-module-split.sql`

1. 建 `t_support_session` / `t_support_message`。
2. 识别待迁数据：`t_social_message` 中 `conversation_id` 任一端等于 `710000000000000001` 的行。
3. 按 `user_id` 归并成一人一个 session（同一用户的多条会话合并，`last_message_at` 取最大值）。
4. `sender_id = 客服` → `AGENT`；否则 `USER`；`biz_type` 保留（`ASK` / `PRODUCT` → `PRODUCT`，`biz_id` 作 `productId`）。
5. 迁移日志写入 `t_support_migration_log`（原 `t_social_message.id` → 新 `t_support_message.id`），便于回滚对账。
6. **删除** `t_social_message` 中被迁移的行（不删的话私信列表还会看到）。
7. 幂等：`INSERT ... WHERE NOT EXISTS`，可重复执行；执行前先 `--dry-run` 打印条数。

**执行前置**：先全量备份 `duoduo_social.t_social_message`（沿用现有备份脚本口径），
迁移后对账：`迁移前客服相关行数 == 迁移后 t_support_message 行数`，并抽样比对内容指纹。

---

## 九、改动点清单

### 后端（`duoduo-social`）
| # | 文件 | 动作 |
|---|---|---|
| B1 | `support/` 新子包（entity/mapper/service/controller） | 新建：`SupportSessionEntity`、`SupportMessageEntity`、`SupportSessionMapper`、`SupportMessageMapper`、`SupportMessageService`、`SupportSessionService`、`SupportController`（用户侧）、`SupportAdminController`（运营侧） |
| B2 | `controller/SupportController.java`（旧） | ✅ **已删除**（`/api/socials/support/**` 下线） |
| B3 | `service/MessageService.java` | ⚠️ **未清理**：`BIZ_ASK` 老通道仍在（见下方遗留 ①）；新入口已全部改道客服 |
| B4 | `service/UnreadService.java` | 合并口径加客服未读 |
| B5 | `dto/SocialDtos.java` | `UnreadSummary` 增加 `support` 字段 |
| B6 | `service/NoticeService.java` | 私信通知不再可能流向客服（随 B3 自然消失，加一条防御性判断即可） |
| B7 | `resources/application.yml` | 显式声明 `duoduo.social.official-cs-user-id`（当前只有 `@Value` 默认值，换号得改代码） |
| B8 | `docker-compose.yml` | 透传 `DUODUO_SOCIAL_OFFICIAL_CS_USER_ID`（本项目已因漏透传踩过两次） |
| B9 | `docs/sql/incremental/37-support-module-split.sql` | 新建表 + 迁移 |

### 前端 · 商城（`frontend/apps/mall`）
| # | 文件 | 动作 |
|---|---|---|
| F1 | `src/pages/Support.tsx` | 新建独立客服会话页（不复用 `Chat.tsx`，避免两套规则混在一处） |
| F2 | `src/components/SupportEntry.tsx` | 改为 `navigate('/support')`，不再请求 `agent()` |
| F3 | `src/pages/ProductDetail.tsx` | 自营商品「问一问」跳 `/support?productId=`；入驻保持私信 |
| F4 | `src/App.tsx`（路由） | 注册 `/support` |
| F5 | 顶栏 / 消息入口 | 私信列表不再出现客服；如需要，顶部加"客服"独立入口（图标 + 未读点） |
| F6 | `packages/api-sdk/src/endpoints.ts` | 新增 `supportApi`（新路径）；删 `agent()`；`messageApi` 保持 |

### 前端 · 商家工作台（`frontend/apps/merchant`）
| # | 文件 | 动作 |
|---|---|---|
| A1 | `src/pages/seller/SupportInbox.tsx` | 新建「客服信息」（左列表 + 右聊天 + 回复 + 关闭/重开 + 筛选） |
| A2 | `src/pages/merchant/MerchantRoot.tsx` | `TABS_FOR_OWNER` 增加「客服信息」+ 注册 `/seller/support` 路由 |
| A3 | `frontend/apps/admin` | **不改**：平台运营端不提供客服/私信入口 |

### 前端 · 平台管理后台
- 无改动。运营端继续只做平台级事务；这是职责划分，不是待办。

### 小程序（`miniprogram`）
| # | 文件 | 动作 |
|---|---|---|
| M1 | `pages/support/support.*` | 新建页面（js/wxml/wxss/json），轮询 4 秒，进入即 `read` |
| M2 | `pages/profile/profile.js` | `goSupport()` 改跳 `pages/support/support` |
| M3 | `pages/product/product.js` | 自营商品「问一问」分支跳客服页 |
| M4 | `utils/api.js` | 新增 `support.*` 四方法；删 `social.support` |
| M5 | `utils/unread.js` | 角标口径加客服未读 |
| M6 | `app.json` | 注册新页面 |

### 文档 / 守卫 / 测试
| # | 项 | 动作 |
|---|---|---|
| T1 | `scripts/check-support-split.mjs` | 新建守卫：`t_social_message` 不存在客服行、私信列表接口不返回客服 peer、`/api/socials/support` 已删除、后台路由与页签存在 |
| T2 | `scripts/e2e/support-message-check.mjs` | 端到端：用户连发 3 条（验证不再限一条）→ 运营后台可见 → 运营回复 → 用户侧收到 → 未读数正确 → 私信列表无客服 |
| T3 | 反向验证 | 临时去掉 `requireOperator()` → 必须恰好一条红；临时放开限发 → 恰好一条红 |
| T4 | `README.md` / `docs/PLAN.md` / `项目简报.md` / `待做清单` | 同步更新 |

---

## 十、验证清单（实施后照此验收）

1. 用户端：`/support` 能打开；连续发 3 条不被拦；顶部不出现"互相关注/只能发一条"。
2. 私信列表：`/messages` 中**没有**"多多官方客服"这一行；未读数不含客服。
3. 后台：`/admin/support` 可见历史"111"那条；未读角标为 1；回复后用户端 4 秒内出现。
4. 权限：用普通 USER 的 token 调 `/api/support/admin/sessions` → 403。
5. 迁移对账：迁移前客服相关行数 == 迁移后 `t_support_message` 行数；抽样内容一致。
6. 既有不回归：`product-detail-ui-check`、`me-page-ui-check`、`check-web-theme`、`check-miniprogram-dark` 全绿。

---

## 十一、实施结果（2026-10-21）

| 项 | 状态 |
|---|---|
| 增量 37 建表 + 历史迁移 | ✅ 1 会话 / 2 消息迁出，`t_social_message` 残留客服行 = 0 |
| 用户侧 `/api/support/**` | ✅ 取建会话 / 会话列表 / 消息 / 发送 / 已读 / 未读 |
| 商家侧 `/api/seller/support/**` | ✅ 列表 / 详情 / 回复 / 已读 / 关闭 / 未读 |
| 商城 Web `/support` 页 | ✅ 左侧会话列表 + 右侧气泡流 + 商品卡 + 5s 轮询 |
| 商家工作台「客服信息」 | ✅ 同上 + 关闭/重开 + 筛选 |
| 小程序 `pages/support` | ✅ 独立页面，4s 轮询；`profile` 与商品「问一问」已改道 |
| 旧 `SupportController`（`/api/socials/support/**`） | ✅ 已删除 |
| 未读口径三处同步 | ✅ `UnreadSummary.support` + 单测；小程序角标走同一合并口径 |
| 平台运营端 | ✅ 未新增客服页签（按职责划分刻意不做） |
| 验证 | `scripts/support-split-smoke.py` **22/0**；反向验证（去掉角色校验）**21/1**（恰好那条红） |

**未做（下一轮）**：店员（STAFF）接入客服工作台 —— 会话归属需要成员域回源解析
（`t_merchant_member.owner_user_id`），本轮只放行店铺主体，店员调接口 403。

**遗留风险（需排期清理）**
1. `MessageService` 的 `BIZ_ASK` 分支仍会把"商品咨询"写成私信（老客户端 / 冒烟脚本在用）。
   新入口（商城 Web、小程序商品页）已全部改道 `/api/support/**`，所以正常用户不会走进去；
   但一旦有旧客户端调用，仍会在私信里生成一条商品咨询会话 —— 与"商品咨询=客服"口径冲突。
   清理方式：`ASK` 直接返回 400 并提示改道，或服务端内部转发到客服域，二选一。
2. 商家端会话列表暂无关键词搜索（只按状态筛选）：昵称在用户域，跨域模糊搜索需要回源分页，
   当前数据量不值得，等会话数上来再做。
