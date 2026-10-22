# 多端消息未读与角标方案（Q9 · 2026-10-22）

> 范围：用户端（商城 Web / 小程序）、商家端（商家工作台）、运营后台三端的「消息未读计数 + 角标显示 + 已读清除」完整链路设计，以及本期已落地部分与后续建议。

## 1. 消息从产生到角标的完整链路

```
消息产生者                    存储与未读口径                    角标消费端
────────────                ─────────────────────            ─────────────────
交易域(发货/退款等) ──┐
履约域(售后/物流)   ──┼─→ duoduo-social 内部推送端点 ─→ t_social_notice (+t_social_notice_read)
分享圈(评论/关注)   ──┘         pushBatch/announce          未读 = 无已读记录的行（分5类）
私信: MessageService ────────→ t_social_message            未读 = read_at IS NULL
客服: SupportService ────────→ t_support_session          未读 = unread_for_user（会话级计数列）
全站公告: 运营后台 ──────────→ t_social_notice(user_id=0)   未读 = 该用户不在已读集合
```

**三条未读口径各自独立、互不重复计数**（第五十五轮确立，必须守住）：
- 通知未读：`t_social_notice` 中「我没有已读记录」的行，**排除** bizType=MESSAGE/SUPPORT；
- 私信未读：`t_social_message.read_at IS NULL`；
- 客服未读：`t_support_session.unread_for_user`（打开会话即清零）。

合并口径唯一入口：`GET /api/socials/notices/unread-summary` → `{ notice, message, support, total }`。

## 2. 端侧方案（推荐实现 = 本期落地）

### 2.1 用户端（商城 Web + 小程序）
| 需求 | 实现 | 状态 |
|---|---|---|
| 顶栏/底部导航总角标 | 轮询 `unread-summary`（Web：Layout 全站轮询 S7；小程序：`utils/unread.js` 全局刷新 + notice-toast 滑入） | ✅ 既有 |
| 通知中心分类角标 | **本期新增** `GET /api/socials/notices/unread-by-category`，按 ORDER/FRIEND/MERCHANT/SYSTEM/ANNOUNCEMENT 分桶（与 unread-count 同口径：定向+公告−已读集合，排除私信/客服） | ✅ 本期 |
| 已读/未读区分 | 列表项「未读」标签 + 左侧品牌色边条；点开即 `markRead` 并**立即**刷新顶栏角标 | ✅ 既有 |
| 数字封顶 | `>99` 显示 `99+` | ✅ 本期 |

推荐保持不变的两个纪律：
1. **角标数字必须来自服务端分桶**，前端绝不拿「当前页 !read 条数」当角标（分页外的未读会漏）；
2. 私信/客服通知在通知中心「列表可见但不计数」，否则同一条消息被数两遍。

### 2.2 商家端（商家工作台）
**本期已落地**（MerchantRoot 导航角标，30s 轮询 + 路由切换即刷新）：
- 客服信息 ← `sellerSupportApi.unreadCount()`（买家咨询未读总数）；
- 订单管理 ← `sellerApi.summary().pendingShipCount`（待发货）；
- 售后处理 ← `afterSaleApi.seller(PENDING).total + seller(RETURNING).total`（待处理 + 待签收）。

清除逻辑：**不做前端「点进即清零」**——打开会话/处理工单后由服务端事实收敛，下一次轮询同步角标。请求失败按 0 处理（角标是提醒，不能挡导航）。

后续建议（不阻塞验收）：商品管理角标可接「商品被驳回待重提」数；结算对账可接「新账单生成」提醒。

### 2.3 运营后台
运营后台**不介入**买卖双方私信/客服会话（SellerSupportController 注释确立的平台纪律），角标只覆盖**平台事务**：
- 待办类：入驻审核待审（`adminMerchantApi.page({status:'SUBMITTED'})`）、商品上架审核待审（E28 审核台）、售后仲裁待裁（`afterSaleApi` ARBITRATING）、抽查队列（E28）；
- 实现方式与商家端一致：AdminRoot 导航挂 `NavBadge`，30s 轮询汇总端点。
- 现状：后台暂无角标（本期范围外）。**建议**下期把「入驻审核 + 商品审核」两个数字先挂上（数据端点已存在，纯前端工作量）。

## 3. 已知边界
- 角标轮询间隔：用户端沿用既有节奏（10s 兜底），商家端 30s；不引 WebSocket（当前无推送基建，轮询在现有量级下足够）；
- 小程序分类角标：`pages/notification` 分类 chip 与 Web 同源同接口，如需同样式角标直接复用 `unread-by-category`（接口已就绪，端侧渲染待做，不阻塞本次验收）。
