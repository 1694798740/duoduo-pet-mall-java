# S6 · 双端功能对照表（小程序 vs 网页商城）

> **这份文档回答一个问题**：同一个「多多宠物商店」，微信小程序端和网页商城端各自能做到哪些事，
> 哪些功能**只有一端有**，以及**为什么**是这么分的。
>
> 本文是**对照表**，不是功能清单。所有条目都以源码为准（文件路径见每行「出处」列），
> 由 `scripts/lib/check-dual-client.ps1` 静态守卫守着，防止文档与代码漂移。

- 小程序端：`miniprogram/`（**30 个页面**，tabBar 5 项）
- 网页端：`frontend/apps/mall/`（**31 个页面组件**，`App.tsx` 内 **34 条 `<Route>`**，顶部导航 6 项 + 头像菜单 13 项）
- 两者共用同一套后端：网关 `:8080` → 九个微服务；**契约完全同源**，差异只在**表现层与入口**。

---

## 0. 一句话结论

**两端在「交易主链路」上是等价的**（浏览 → 加购 → 下单 → 支付 → 售后 → 评价），
差异集中在三类：

| 差异类型 | 说明 | 举例 |
| --- | --- | --- |
| **A. 平台能力差异** | 某个端天然做不到，只能一端有 | 微信支付/微信登录/实名调起 → 只有小程序；大屏表格/快捷键 → 只有网页 |
| **B. 页面形态差异** | 两端都能做，但拆页方式不同（合页 vs 分页） | 小程序 `chat-list` + `chat` 两页 ↔ 网页 `/messages` + `/messages/:peerId` |
| **C. 入口位置差异** | 功能都在，但从不同地方进 | 「分类」小程序是独立页，网页是首页区块 + `/search?categoryId=` |
| **D. 真实缺口** | 一端有、另一端确实没有，且不是设计取舍 | 见 §3，逐条给出补法 |

**截至本文：D 类共 4 条**，其中 2 条是**有意不做**（已在 §3 标注），2 条是**待补**。

---

## 1. 页面级对照（逐行对齐）

### 1.1 交易主链路

| 功能 | 小程序 | 网页端 | 是否等价 | 备注 |
| --- | --- | --- | --- | --- |
| 首页 / 逛商品 | `pages/index/index`（tab） | `/`（`Home.tsx`，顶部导航「首页」） | ✅ | 两端都有搜索框 + 金刚区 + 分类区块 + 商品瀑布流 |
| 搜索 | 首页内联搜索（`index.js onSearch` → 刷新列表） | `/search`（独立页，支持 `keyword` / `categoryId`） | ⚠️ **形态不同** | 小程序**没有独立搜索页**，搜索在首页原地过滤；网页有结果页 + 筛选 |
| 分类 | `pages/category/category`（独立页，首页金刚区入口） | 首页「🗂️ 按分类逛」区块 → `/search?categoryId=` | ⚠️ **形态不同** | 网页**没有独立分类页**；两侧数据源同一接口 |
| 商品详情 | `pages/product/product` | `/product/:id`（`ProductDetail.tsx`） | ✅ | |
| 购物车 | `pages/cart/cart`（tab） | `/cart`（`Cart.tsx`，导航右上角图标） | ✅ | |
| 确认订单 | `pages/checkout/checkout` | `/checkout`（`Checkout.tsx`） | ✅ | |
| 支付 | `pages/pay/pay`（**独立收银台页**） | `/checkout` 内联完成（**无独立页**） | ⚠️ **形态不同** | 见 §2.1：小程序需 `wx.requestPayment` 交互，网页走沙箱直付 |
| 订单列表 | `pages/orders/orders` | `/orders`（`Orders.tsx`） | ✅ | |
| 订单详情 | `pages/order-detail/order-detail` | `/orders/:orderNo`（`OrderDetail.tsx`） | ✅ | |
| 售后列表 | `pages/after-sales/after-sales` | `/after-sales`（`AfterSales.tsx`） | ✅ | |
| 申请售后 | `pages/after-sale/after-sale` | `/after-sales/new`（`AfterSaleNew.tsx`） | ✅ | |
| 售后详情 | —（并入列表展开） | `/after-sales/:id`（`AfterSaleDetail.tsx`） | ⚠️ **形态不同** | 小程序列表内展开，无独立详情页 |
| 评价 | 订单详情内联 | `/reviews/new`、`/reviews/mine` | ⚠️ **形态不同** | 网页有独立「写评价」「我的评价」页；小程序在订单详情里发起 |
| 优惠券 | `pages/coupons/coupons` | `/coupons`（`Coupons.tsx`） | ✅ | 两端都支持 `?tab=mine` 口径 |
| 秒杀 | `pages/seckill/seckill` | `/seckill`（`Seckill.tsx`） | ✅ | 两端都不套登录（导流页） |
| AI 导购「多多」 | `pages/ai/ai` | `/ai`（`AiGuru.tsx`） | ✅ | 两端都不套登录（网关白名单） |
| 收货地址 | `pages/address/address` | `/addresses`（`Addresses.tsx`） | ✅ | |

### 1.2 社交 / C2C / 用户中心

| 功能 | 小程序 | 网页端 | 是否等价 | 备注 |
| --- | --- | --- | --- | --- |
| 分享圈 | `pages/share-circle/share-circle`（tab） | `/share`（`ShareCircle.tsx`，头像菜单） | ✅ | 两端都支持话题页（小程序 `share-topic` ↔ 网页 `/share/topic/:name` 同一组件） |
| 分享详情 | `pages/share-detail/share-detail` | `/share/:id`（`ShareDetail.tsx`） | ✅ | |
| 我的分享 | `pages/share-mine/share-mine` | `/share/mine`（`ShareMine.tsx`） | ✅ | |
| 跳蚤市场（C2C 闲置） | `pages/flea-market/flea-market` | `/listings`（`Listings.tsx`） | ✅ | |
| 闲置详情 | `pages/flea-detail/flea-detail` | `/listing/:id`（`ListingDetail.tsx`） | ✅ | |
| 发布闲置 | `pages/flea-publish/flea-publish` | `/listings/new`（`ListingForm.tsx`） | ✅ | 两端都只要求**登录 + 实名**，**不要求 SELLER 角色** |
| 编辑闲置 | —（发布页复用） | `/listings/:id/edit`（同一 `ListingForm`） | ⚠️ **形态不同** | 小程序发布页承担新建；编辑在「我的闲置」内发起 |
| 我的闲置 | `pages/flea-mine/flea-mine` | `/listings/mine`（`MyListings.tsx`） | ✅ | |
| 通知 | `pages/notification/notification`（**tab**） | `/notifications`（`Notifications.tsx`，头像菜单） | ⚠️ **入口不同** | 小程序是**一级 tab**（第 50 轮从二级升上来）；网页在头像菜单里 |
| 私信列表 | `pages/chat-list/chat-list` | `/messages`（`Messages.tsx`） | ✅ | |
| 聊天 | `pages/chat/chat` | `/messages/:peerId`（`Chat.tsx`） | ✅ | |
| 关注与粉丝 | `pages/follow-list/follow-list` | `/follow`（`Follow.tsx`） | ✅ | |
| 个人主页（看别人） | `pages/user-profile/user-profile?id=` | **❌ 无** | ❌ **缺口** | 见 §3-D1 |
| 我的 / 个人中心 | `pages/profile/profile`（tab） | `/me`（`Me.tsx`，头像菜单） | ✅ | |
| 实名认证 | `pages/realname/realname`（独立页） | `/me` 内联表单（`Me.tsx submitRealName`） | ⚠️ **形态不同** | 网页无独立页；两端都调 `POST /api/users/me/realname` |
| 登录 | `pages/login/login` | `/login`（`Login.tsx`） | ⚠️ **能力不同** | 见 §2.1 |
| 宠物档案 | **❌ 无** | `/pets`（`Pets.tsx`，头像菜单） | ❌ **缺口**（有意） | 见 §3-D2 |
| 404 | —（微信框架自带） | `/404`（`NotFound.tsx`） | ⚠️ | 平台机制差异，非功能缺口 |

---

## 2. 只有一端有的功能（平台能力差异）

这一类**不是缺口**，是两端宿主能力决定的，列出来是为了避免被误判成「漏做」。

### 2.1 只有小程序有

| 能力 | 出处 | 为什么网页做不到 |
| --- | --- | --- |
| **微信登录** | `miniprogram/utils/api.js` → `wx.login` → `POST /api/auth/wechat-login` | 需要微信客户端内的 `code`，浏览器里拿不到 |
| **微信绑定 / 解绑** | `pages/profile/profile.js toggleWechat()` | 解绑必须**重新取 code** 证明「操作者持有这个微信」，code 一次性、不能复用登录那一个 —— 前提是有微信环境 |
| **微信支付（`wx.requestPayment`）** | `pages/pay/pay` | 网页端走**沙箱直付**（`/checkout` 内联完成，无独立收银台页） |
| **拉起系统实名认证** | `pages/realname/realname` | 小程序可调起微信实名；网页只能手工填表（`Me.tsx` 内联表单） |
| **分享到微信会话 / 朋友圈** | `pages/share-detail`、`share-circle` 的 `onShareAppMessage` | 浏览器无此通道 |
| **订阅消息推送** | `pages/notification` + `utils/unread.js` | 网页只能站内轮询未读 |
| **扫码 / 拍照（闲置发布）** | `pages/flea-publish` | 浏览器受限（需 `getUserMedia` 且非 HTTPS 不可用） |

### 2.2 只有网页端有

| 能力 | 出处 | 为什么小程序不合适 |
| --- | --- | --- |
| **宠物档案 `/pets`** | `frontend/apps/mall/src/pages/Pets.tsx` | **有意不做**（见 §3-D2）：小程序侧信息架构已满，塞进 tabBar 需挤掉一项，收益不足 |
| **独立搜索页 + 结果筛选** | `/search`（`Search.tsx`） | 小程序 tabBar 5 项已满（首页/分享圈/通知/购物车/我的），再挂一级页会挤掉现有 tab；当前用「首页原地过滤」替代 |
| **独立分类页** | 网页用首页区块 + `/search?categoryId=` | 同上；小程序有独立 `category` 页（形态相反，见 §1.1） |
| **独立评价页（写 / 我的）** | `/reviews/new`、`/reviews/mine` | 小程序把这两件事收进订单详情内联，省两次跳转 |
| **独立售后详情页** | `/after-sales/:id` | 小程序在列表内展开，少一次页面栈 |
| **商家端 / 管理后台跨站入口** | `Layout.tsx` `MERCHANT_URL` / `ADMIN_URL` | 运营后台是**桌面优先**的表格密集界面，不适合手机；小程序侧不提供入口 |
| **大屏多列布局 / 快捷键** | `.nav-links` 响应式（1919px 断点注释） | 平台差异，非功能项 |

---

## 3. 真实缺口清单（D 类 · 只有一端有且未做）

> 判据：**同一件事，一端能做完整，另一端连入口都没有** —— 排除上面 §2 的平台能力差异。

### D1 · 个人主页（看别人）· **网页端已补齐（2026-10-13）** ✅

- **小程序**：`pages/user-profile/user-profile?id=<userId>`，三处入口 ——
  `flea-detail.js:153`（闲置详情点卖家）、`follow-list.js:92`（关注/粉丝列表点人）、
  `share-circle.js:228`（分享圈点头像）。
- **网页端**（补齐前）：`grep -rn "UserProfile" frontend/apps/mall/src/` **零命中**；`/follow` 列表点了人也不跳。
- **影响**：网页端 C2C 场景**断链** —— 看到别人的闲置/分享，点不进去看他是谁、还卖什么。
  这是社交链路「看到人 → 进主页 → 看他其他内容」的最后一跳缺失。
- **补齐做法**（**后端零改动**，接口小程序早就在用）：
  - `api-sdk` 新增 `followApi.profile(userId)`（`GET /api/socials/users/{id}/profile`）与 `UserProfileView` 类型；
  - 新增 `pages/UserProfile.tsx` + 路由 `/user/:id`；
  - 三处入口补齐：`Follow.tsx`（昵称可点）、`ListingDetail.tsx`（「查看卖家主页」按钮）、
    分享卡组件 `packages/shared/src/components/share.tsx`（作者名可点）。
- **两个刻意的取舍**：
  1. 入口做成 `<Link>` / `<button class="link-btn">` 而不是 `<span onClick>` ——
     键盘可达、屏幕阅读器念得出"这是按钮/链接"；
  2. 非本人主页时，关注数/粉丝数**只展示不可点**（后端 following/followers 只有"查自己"的语义），
     并给一句说明，而不是发起一个注定 403 的请求。
- **守卫同步**：`check-dual-client.ps1` 的 `$expectedMissingWeb` 已清空。

### D2b · 小程序「宠物档案在网页端」引导文案 · **已补齐（2026-10-13）** ✅

### D2 · 宠物档案 `/pets` · **小程序缺失** · **有意不做**（非缺口）

- **网页**：`/pets`，头像菜单入口。
- **小程序**：`grep -rni "pets" miniprogram/app.json miniprogram/pages/` **零命中**。
- **判决**：**有意不做**。小程序 tabBar 上限 5 项已占满，把宠物档案提为一级页须挤掉现有 tab；
  而宠物档案是「低频、录入型」功能，手机端录入体验差于桌面端。**结论：保持网页端独有**，
  但小程序「我的」页面应加一条**跳转说明**（「宠物档案请在网页端管理」），否则用户会以为没这功能。
  → 拆成 **D2a（不做，已决）** + **D2b（待补：小程序加一句引导文案，1 处 WXML 改动）**。

**D2b 已补齐（2026-10-13）**：`miniprogram/pages/profile/profile.wxml` 加了一条静态提示
（「宠物档案请在网页端管理」+ 一句解释），样式用压暗一档的 `.menu-note`，
刻意**不占 menu-item 的跳转位** —— 点了跳不过去，反而像坏了。

### D3 · 搜索结果页 · **小程序已补齐（2026-10-13）** ✅

- **网页**：`/search`，独立结果页，支持 `keyword` 与 `categoryId` 两种入参，带筛选。
- **小程序**（补齐前）：搜索框在首页内联（`index.js onSearch` → `fetchProducts(true)`），
  **原地刷新首页列表**，没有独立结果页；从金刚区「全部分类」进 `category` 页后，**分类内也无法二次搜索**。
- **影响**：① 首页滚动位置与搜索结果混在一起，用户点返回会丢搜索态；② `category` 页内搜不了。
- **补齐做法**：
  - 新增 `pages/search/search`（4 个文件），列表逻辑与 `category` 页**同构** ——
    同一套「请求序号 + 触底分页」，避免两处分页行为不一致；
  - 首页 `onSearch` 改为 `navigateTo` 到本页并带 `keyword`（**必须 `encodeURIComponent`**，
    否则 `wx.navigateTo` 的查询串会把中文截断）；
  - `category` 页右栏顶部加了一个「在『当前分类』中搜索」的入口，跳转时带 `categoryId` + `name`；
  - **不占 tabBar**（tabBar 已满 5 项，`navigateTo` 打开）。
- **两个刻意的取舍**：
  1. 分类页的搜索入口放在**右栏顶部、随列表滚动**，没有做成通栏固定栏 ——
     `cat-wrap` 是两列 flex、左右两栏都 `height:100%`，加通栏要改这层结构，
     而 A14 刚为「卡片被顶宽 / 左栏被压缩」修过一轮，为一个入口去动那套布局收益不抵风险。
  2. 分类页那个入口做成「看起来像输入框、点了整块跳转」的**伪搜索栏**而不是真输入框 ——
     真输入框会带来"输入后还要再点一次搜索"的两步操作，而"换个关键词"这个诉求在结果页里做更合适。
- **守卫同步**：`check-dual-client.ps1` 的 `$expectedMissingMp` 已移除 `'search'`，
  并把 §3 的 D3 改为「已补齐」—— 这正是该守卫设计时约定的行为
  （"一旦有人补齐，守卫会报红提醒把缺口清单改为已完成"）。
- **未覆盖**：结果页的"筛选"（网页端 `/search` 有）没有做；小程序端的筛选仍走分类维度。

### D4 · 营销活动 / 秒杀报名记录 · **网页端缺失（轻）** · 待补（可选）

- 小程序的秒杀页含「我的抢购记录」区块（`pages/seckill` 内联）。网页 `/seckill` 只有活动列表 + 抢购，
  记录需回 `/orders` 自行筛。**优先级最低**，可归入 D3 同一次改动里顺手补一个筛选参数。

---

## 4. 权限与鉴权对照

两端**鉴权口径完全同源**（后端同一套 E8/E20/E23 规则），差异只在**前端如何呈现拦截**：

| 场景 | 小程序 | 网页端 | 后端权威 |
| --- | --- | --- | --- |
| 未登录访问需登录页 | `api.ensureSession()` 失败 → 引导 `openLoginPage()` | `<RequireAuth>` 包裹 + `?next=` 原路回跳（`App.tsx:59`） | 网关 401 |
| 发布/管理闲置 | 要求登录 + 实名，**不要求 SELLER** | 同左（`App.tsx:108` 注释明确「刻意不套 `RequireSeller`」） | `POST /api/listings` 校验实名 |
| 免登录可读页 | 首页 / 分类 / 商品详情 / 优惠券 / 秒杀 / AI / 分享圈 | 同左（`/`、`/search`、`/listings`、`/product/:id`、`/listing/:id`、`/coupons`、`/seckill`、`/ai` **不套** `RequireAuth`） | 网关白名单 |
| 商家端 / 管理后台 | ❌ 无入口 | 按 `canAccessSellerConsole(role)` / `isOperator(role)` 显示跨站链接 | 网关只收窄 `/api/admin/**`、`/api/seller/**` |
| 角色判定 | 后端下发 `role`，前端不硬编码 | 同源：`canAccessSellerConsole` 与后端判权同一份矩阵（忽略大小写） | `RolePermissionMatrix` |

★ **两端都不在前端做角色拦截** —— 前端只管「显不显示入口」，**真正的拒绝全部在后端**（网关 + 服务端两层）。
这与铁律 4 一致：判权失败必须表现为 403/404，而不是前端藏掉按钮。

---

## 5. 维护方式（防漂移）

本文的每一条都能被机器检查。守卫脚本 `scripts/lib/check-dual-client.ps1` 做三件事：

1. **页面存在性**：文档里引用的每个 `miniprogram/pages/<x>/<x>` 与 `frontend/apps/mall/src/pages/<X>.tsx`
   都必须真实存在（路径不存在 → 判违规）。
2. **tabBar 一致性**：文档 §1 标注的 5 个 tab 页必须与 `miniprogram/app.json` 的 `tabBar.list` **集合相等**（双向）。
3. **缺口清单自证**：§3 中标记为「待补」的 D 项，必须能在对应端**确实找不到**（例如 D1 断言
   `grep "UserProfile" frontend/apps/mall/src/` 命中数为 0）；一旦有人补齐，守卫会报红提醒把 D1 从缺口清单改为已完成。

**自证纪律**（沿用铁律 29）：守卫自身必须声明「扫描数 == 0 判违规」——
即文档里一个路径都没扫到 / tabBar 一个都没比 → **判失败**，不许降级成 WARN。

---

## 6. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-10-12 | 首版。小程序 30 页 / 网页 31 组件、34 条路由逐项对齐；识别 D1~D4 四条真实缺口（D2 判为有意不做、D4 低优）；新增守卫 `check-dual-client.ps1`。 |
